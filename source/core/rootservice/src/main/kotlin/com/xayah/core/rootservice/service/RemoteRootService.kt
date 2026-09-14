package com.xayah.core.rootservice.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.UserInfo
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.ipc.RootService
import com.xayah.core.datastore.ConstantUtil.DEFAULT_TIMEOUT
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.impl.RemoteRootServiceImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.parcelables.StatFsParcelable
import com.xayah.core.rootservice.parcelables.StorageStatsParcelable
import com.xayah.core.rootservice.util.ExceptionUtil.tryOnScope
import com.xayah.core.rootservice.util.ShellFileOps
import com.xayah.core.rootservice.util.withMainContext
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class RemoteRootService(private val context: Context) {
    private var mService: IRemoteRootService? = null
    private var mConnection: ServiceConnection? = null
    private var mutex = Mutex()
    private var retries = 0
    private val intent by lazy {
        Intent().apply {
            component = ComponentName(context.packageName, RemoteRootService::class.java.name)
        }
    }
    val gsonUtil by lazy { GsonUtil() }

    // TODO: Will this cause memory leak? It needs to test.
    var onFailure: (Throwable) -> Unit = {}

    /**
     * True kalau operasi berkas harus dijalankan lewat shell, bukan daemon root.
     *
     * Saat mode Shizuku aktif, libsu sama sekali tidak boleh disentuh — kalau
     * disentuh ia akan mencoba membangun proses root dan gagal berulang kali.
     */
    private fun shellMode(): Boolean = BaseUtil.isShizukuMode()

    init {
        // ShellFileOps perlu tahu direktori sementara yang bisa dibaca shell.
        ShellFileOps.configure(context)
    }

    private fun log(msg: () -> String) = LogUtil.log { "RemoteRootService" to msg() }

    class RemoteRootService : RootService() {
        init {
            if (Process.myUid() == 0)
                System.loadLibrary("nativelib")
        }

        override fun onBind(intent: Intent): IBinder = RemoteRootServiceImpl(applicationContext)
    }

    private suspend fun bindService(): IRemoteRootService = run {
        delay(1000)
        suspendCoroutine { continuation ->
            if (mService == null) {
                retries++
                destroyService()
                log { "Trying to bind the service..." }
                mConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        mService = IRemoteRootService.Stub.asInterface(service)
                        if (continuation.context.isActive) continuation.resume(mService!!)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        mService = null
                        mConnection = null
                        val msg = "Service disconnected."
                        log { msg }
                        if (continuation.context.isActive) continuation.resumeWithException(RemoteException(msg))
                    }

                    override fun onBindingDied(name: ComponentName) {
                        mService = null
                        mConnection = null
                        val msg = "Binding died."
                        log { msg }
                        if (continuation.context.isActive) continuation.resumeWithException(RemoteException(msg))
                    }

                    override fun onNullBinding(name: ComponentName) {
                        mService = null
                        mConnection = null
                        val msg = "Null binding."
                        log { msg }
                        if (continuation.context.isActive) continuation.resumeWithException(RemoteException(msg))
                    }
                }
                RootService.bind(intent, mConnection!!)
            } else {
                retries = 0
                mService
            }
        }
    }

    /**
     * Destroy the service.
     */
    fun destroyService(killDaemon: Boolean = false) {
        log { "Trying to destroy the service..." }
        if (killDaemon) {
            if (mConnection != null) {
                RootService.unbind(mConnection!!)
            }
            RootService.stopOrTask(intent)
        }

        mConnection = null
        mService = null
    }

    private suspend fun getService(): IRemoteRootService = mutex.withLock {
        return tryOnScope(
            block = {
                withMainContext {
                    if (mService == null) {
                        val msg = "Service is null, trying to bind: $retries."
                        log { msg }
                        bindService()
                    } else if (mService!!.asBinder().isBinderAlive.not()) {
                        mService = null
                        val msg = "Service is dead, trying to bind: $retries."
                        log { msg }
                        bindService()
                    } else {
                        mService!!
                    }
                }
            },
            onException = {
                withMainContext {
                    mService = null
                    val msg = it.message
                    if (msg != null)
                        log { msg }
                    bindService()
                }
            }
        )
    }

    suspend fun readStatFs(path: String): StatFsParcelable =
        // DirectoryRepository memakai android.os.StatFs sendiri pada mode Shizuku.
        if (shellMode()) StatFsParcelable()
        else runCatching { getService().readStatFs(path) }.onFailure(onFailure).getOrElse { StatFsParcelable() }

    suspend fun mkdirs(path: String): Boolean =
        if (shellMode()) ShellFileOps.mkdirs(path)
        else runCatching { getService().mkdirs(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun copyRecursively(path: String, targetPath: String, overwrite: Boolean): Boolean =
        if (shellMode()) ShellFileOps.copyRecursively(path, targetPath, overwrite)
        else runCatching { getService().copyRecursively(path, targetPath, overwrite) }.onFailure(onFailure).getOrElse { false }

    suspend fun copyTo(path: String, targetPath: String, overwrite: Boolean): Boolean =
        if (shellMode()) ShellFileOps.copyTo(path, targetPath, overwrite)
        else runCatching { getService().copyTo(path, targetPath, overwrite) }.onFailure(onFailure).getOrElse { false }

    suspend fun renameTo(src: String, dst: String): Boolean =
        if (shellMode()) ShellFileOps.renameTo(src, dst)
        else runCatching { getService().renameTo(src, dst) }.onFailure(onFailure).getOrElse { false }

    suspend fun writeText(text: String, dst: String): Boolean = if (shellMode()) {
        ShellFileOps.writeText(text, dst)
    } else runCatching {
        var isSuccess = true
        val tmpFilePath = "${context.cacheDir.path}/tmp"
        val tmpFile = File(tmpFilePath)
        tmpFile.writeText(text)
        if (getService().mkdirs(PathUtil.getParentPath(dst)).not()) isSuccess = false
        if (getService().copyTo(tmpFilePath, dst, true).not()) isSuccess = false
        tmpFile.deleteRecursively()
        isSuccess
    }.onFailure(onFailure).getOrElse { false }

    suspend fun writeBytes(bytes: ByteArray, dst: String): Boolean = if (shellMode()) {
        ShellFileOps.writeBytes(bytes, dst)
    } else runCatching {
        var isSuccess = true
        val tmpFilePath = "${context.cacheDir.path}/tmp"
        val tmpFile = File(tmpFilePath)
        tmpFile.writeBytes(bytes)
        getService().mkdirs(PathUtil.getParentPath(dst))
        isSuccess = isSuccess and getService().copyTo(tmpFilePath, dst, true)
        tmpFile.deleteRecursively()
        isSuccess
    }.onFailure(onFailure).getOrElse { false }

    suspend fun exists(path: String): Boolean =
        if (shellMode()) ShellFileOps.exists(path)
        else runCatching { getService().exists(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun createNewFile(path: String): Boolean =
        if (shellMode()) ShellFileOps.createNewFile(path)
        else runCatching { getService().createNewFile(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun deleteRecursively(path: String): Boolean =
        if (shellMode()) ShellFileOps.deleteRecursively(path)
        else runCatching { getService().deleteRecursively(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun listFilePaths(path: String, listFiles: Boolean = true, listDirs: Boolean = true): List<String> =
        if (shellMode()) ShellFileOps.listFilePaths(path, listFiles, listDirs)
        else runCatching { getService().listFilePaths(path, listFiles, listDirs) }.onFailure(onFailure).getOrElse { listOf() }

    private fun readFromParcel(pfd: ParcelFileDescriptor, block: (Parcel) -> Unit) = run {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val bytes = stream.readBytes()
        val parcel = Parcel.obtain()
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        block(parcel)
        parcel.recycle()
    }

    suspend fun readText(path: String): String = if (shellMode()) {
        ShellFileOps.readText(path)
    } else runCatching {
        val pfd = getService().readText(path)
        var text: String? = null
        readFromParcel(pfd) {
            text = it.readString()
        }
        text ?: ""
    }.onFailure(onFailure).getOrElse { "" }

    suspend fun readBytes(src: String): ByteArray = if (shellMode()) {
        ShellFileOps.readBytes(src)
    } else runCatching {
        val pfd = getService().readBytes(src)
        var bytes = ByteArray(0)
        readFromParcel(pfd) {
            bytes = ByteArray(it.readInt())
            it.readByteArray(bytes)
        }
        bytes
    }.onFailure(onFailure).getOrElse { ByteArray(0) }

    suspend fun calculateSize(path: String): Long =
        if (shellMode()) ShellFileOps.calculateSize(path)
        else runCatching { getService().calculateSize(path) }.onFailure(onFailure).getOrElse { 0 }

    suspend fun clearEmptyDirectoriesRecursively(path: String) =
        if (shellMode()) ShellFileOps.clearEmptyDirectoriesRecursively(path)
        else runCatching { getService().clearEmptyDirectoriesRecursively(path) }.onFailure(onFailure)

    suspend fun setAllPermissions(src: String) =
        if (shellMode()) ShellFileOps.setAllPermissions(src)
        else runCatching { getService().setAllPermissions(src) }.onFailure(onFailure)

    /**
     * Get the uid and gid of the file/directory
     *
     * @param path
     * @return Uid to Gid
     */
    suspend fun getUidGid(path: String): Pair<UInt, UInt> =
        // chown butuh root; pada mode shell kepemilikan tidak bisa dibaca.
        if (shellMode()) UInt.MAX_VALUE to UInt.MAX_VALUE
        else runCatching { getService().getUidGid(path).let { it[0].toUInt() to it[1].toUInt() } }.onFailure(onFailure).getOrElse { UInt.MAX_VALUE to UInt.MAX_VALUE }

    suspend fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> = if (shellMode()) {
        // AppsRepo memakai PackageManager biasa pada mode Shizuku.
        listOf()
    } else runCatching {
        val pfd = getService().getInstalledPackagesAsUser(flags, userId)
        val packages = mutableListOf<PackageInfo>()
        readFromParcel(pfd) {
            it.readTypedList(packages, PackageInfo.CREATOR)
        }
        packages
    }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun getPackageInfoAsUser(packageName: String, flags: Int, userId: Int) =
        if (shellMode()) null
        else runCatching { getService().getPackageInfoAsUser(packageName, flags, userId) }.onFailure(onFailure).getOrNull()

    suspend fun grantRuntimePermission(packageName: String, permName: String, user: UserHandle) =
        if (shellMode()) ShellFileOps.grantRuntimePermission(packageName, permName)
        else runCatching { getService().grantRuntimePermission(packageName, permName, user) }.withLog()

    suspend fun revokeRuntimePermission(packageName: String, permName: String, user: UserHandle) =
        if (shellMode()) ShellFileOps.revokeRuntimePermission(packageName, permName)
        else runCatching { getService().revokeRuntimePermission(packageName, permName, user) }.withLog()

    suspend fun getPermissionFlags(packageName: String, permName: String, user: UserHandle) =
        if (shellMode()) 0
        else runCatching { getService().getPermissionFlags(packageName, permName, user) }.onFailure(onFailure).getOrElse { 0 }

    suspend fun updatePermissionFlags(packageName: String, permName: String, user: UserHandle, flagMask: Int, flagValues: Int) =
        if (shellMode()) Unit
        else runCatching { getService().updatePermissionFlags(packageName, permName, user, flagMask, flagValues) }.onFailure(onFailure)

    suspend fun getPackageSourceDir(packageName: String, userId: Int): List<String> =
        if (shellMode()) ShellFileOps.getPackageSourceDir(packageName)
        else runCatching { getService().getPackageSourceDir(packageName, userId) }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun queryInstalled(packageName: String, userId: Int): Boolean =
        if (shellMode()) runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
        else runCatching { getService().queryInstalled(packageName, userId) }.onFailure(onFailure).getOrElse { false }

    suspend fun getPackageUid(packageName: String, userId: Int): Int =
        if (shellMode()) -1
        else runCatching { getService().getPackageUid(packageName, userId) }.onFailure(onFailure).getOrElse { -1 }

    suspend fun getUserHandle(userId: Int): UserHandle? =
        if (shellMode()) Process.myUserHandle()
        else runCatching { getService().getUserHandle(userId) }.onFailure(onFailure).getOrNull()

    suspend fun queryStatsForPackage(packageInfo: PackageInfo, user: UserHandle): StorageStatsParcelable? =
        // Butuh PACKAGE_USAGE_STATS; pada mode Shizuku ukuran dilewati.
        if (shellMode()) null
        else runCatching { getService().queryStatsForPackage(packageInfo, user) }.onFailure(onFailure).getOrNull()

    suspend fun getUsers(): List<UserInfo> =
        // AppsRepo membatasi diri ke pengguna aktif pada mode Shizuku.
        if (shellMode()) listOf()
        else runCatching { getService().users }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun walkFileTree(path: String): List<PathParcelable> = if (shellMode()) {
        ShellFileOps.walkFileTree(path)
    } else runCatching {
        val pfd = getService().walkFileTree(path)
        val list = mutableListOf<PathParcelable>()
        readFromParcel(pfd) {
            it.readTypedList(list, PathParcelable.CREATOR)
        }
        list
    }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun getPackageArchiveInfo(path: String): PackageInfo? =
        if (shellMode()) null
        else runCatching { getService().getPackageArchiveInfo(path) }.onFailure(onFailure).getOrNull()

    suspend fun getPackageSsaidAsUser(packageName: String, uid: Int, userId: Int): String =
        // settings_ssaid.xml hanya bisa dibaca uid 0.
        if (shellMode()) ""
        else runCatching { getService().getPackageSsaidAsUser(packageName, uid, userId) ?: "" }.onFailure(onFailure).getOrElse { "" }

    suspend fun setPackageSsaidAsUser(packageName: String, uid: Int, userId: Int, ssaid: String) =
        if (shellMode()) Unit
        else runCatching { getService().setPackageSsaidAsUser(packageName, uid, userId, ssaid) }.onFailure(onFailure)

    suspend fun setDisplayPowerMode(mode: Int) =
        if (shellMode()) ShellFileOps.setDisplayPowerMode(mode)
        else runCatching { getService().setDisplayPowerMode(mode) }.onFailure(onFailure)

    suspend fun getScreenOffTimeout() =
        if (shellMode()) ShellFileOps.getScreenOffTimeout()
        else runCatching { getService().getScreenOffTimeout() }.onFailure(onFailure).getOrElse { DEFAULT_TIMEOUT }

    suspend fun setScreenOffTimeout(timeout: Int) =
        if (shellMode()) ShellFileOps.setScreenOffTimeout(timeout)
        else runCatching { getService().setScreenOffTimeout(timeout) }.onFailure(onFailure)

    suspend fun forceStopPackageAsUser(packageName: String, userId: Int) =
        if (shellMode()) ShellFileOps.forceStopPackageAsUser(packageName, userId)
        else runCatching { getService().forceStopPackageAsUser(packageName, userId) }.onFailure(onFailure)

    suspend fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int, userId: Int, callingPackage: String?) =
        if (shellMode()) ShellFileOps.setApplicationEnabledSetting(packageName, newState, userId)
        else runCatching { getService().setApplicationEnabledSetting(packageName, newState, flags, userId, callingPackage) }.onFailure(onFailure)

    suspend fun getApplicationEnabledSetting(packageName: String, userId: Int): Int? =
        if (shellMode()) null
        else runCatching { getService().getApplicationEnabledSetting(packageName, userId) }.onFailure(onFailure).getOrNull()

    suspend fun getPermissions(packageInfo: PackageInfo): List<PackagePermission> =
        // AppsRepo membangun daftar izin dari PackageManager pada mode Shizuku.
        if (shellMode()) listOf()
        else runCatching { getService().getPermissions(packageInfo) }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun setOpsMode(code: Int, uid: Int, packageName: String?, mode: Int) =
        if (shellMode()) ShellFileOps.setOpsMode(code, packageName, mode)
        else runCatching { getService().setOpsMode(code, uid, packageName, mode) }.withLog()

    suspend fun calculateMD5(src: String): String? =
        if (shellMode()) ShellFileOps.calculateMD5(src)
        else runCatching { getService().calculateMD5(src) }.onFailure(onFailure).getOrNull()

    suspend fun writeJson(data: Any, dst: String): ShellResult = runCatching {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        writeText(text = gsonUtil.toJson(data), dst = dst).also {
            isSuccess = it
            if (isSuccess) {
                out.add("Succeed to write configs: $dst")
            } else {
                out.add("Failed to write configs: $dst")
            }
        }
        setAllPermissions(src = dst)

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }.onFailure(onFailure).getOrElse { ShellResult(code = -1, input = listOf(), out = listOf()) }

    suspend inline fun <reified T> readJson(src: String): T? = runCatching<T?> {
        val json = readText(path = src)
        gsonUtil.fromJson<T>(json, object : TypeToken<T>() {}.type)
    }.onFailure(onFailure).getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> writeProtoBuf(data: T, dst: String): ShellResult = runCatching {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        val bytes = ProtoBuf.encodeToByteArray<T>(data)
        writeBytes(bytes = bytes, dst = dst).also {
            isSuccess = it
            if (isSuccess) {
                out.add("Succeed to write configs: $dst")
            } else {
                out.add("Failed to write configs: $dst")
            }
        }
        setAllPermissions(src = dst)

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }.onFailure(onFailure).getOrElse { ShellResult(code = -1, input = listOf(), out = listOf()) }

    /**
     * @see <a href="https://github.com/Kotlin/kotlinx.serialization/issues/2185#issuecomment-1420612365">Unsupported start group or end group wire type: 7</a>
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> readProtoBuf(src: String): T? = runCatching<T?> {
        val bytes = readBytes(src = src)
        ProtoBuf.decodeFromByteArray<T>(bytes)
    }.onFailure(onFailure).getOrNull()
}
