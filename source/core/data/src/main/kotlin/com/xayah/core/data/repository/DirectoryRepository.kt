package com.xayah.core.data.repository

import android.content.Context
import android.os.StatFs
import com.xayah.core.common.util.toSpaceString
import com.xayah.core.data.R
import com.xayah.core.database.dao.DirectoryDao
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.ConstantUtil.DEFAULT_PATH_PARENT
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.model.database.DirectoryUpsertEntity
import com.xayah.core.rootservice.parcelables.StatFsParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.PreparationUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

class DirectoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryDao: DirectoryDao,
    private val packageDao: PackageDao,
    private val rootService: RemoteRootService,
) {
    // ------------------------------------------------------------------
    // Sumber data: root vs shell (Shizuku)
    //
    // Pembacaan ruang penyimpanan dan ukuran direktori dilakukan lewat daemon
    // root saat root tersedia. Tanpa root, StatFs dan listing direktori bisa
    // dikerjakan langsung oleh proses aplikasi, dan ukuran direktori dihitung
    // shell lewat `du`.
    // ------------------------------------------------------------------

    private suspend fun listDirPaths(path: String): List<String> =
        if (BaseUtil.isShellMode()) {
            runCatching { File(path).listFiles()?.map { it.absolutePath } ?: emptyList() }
                .getOrDefault(emptyList())
        } else {
            rootService.listFilePaths(path, listFiles = false)
        }

    /**
     * Ruang kosong dan total sebuah direktori.
     *
     * `StatFs` bekerja untuk aplikasi biasa selama direktorinya terjangkau,
     * jadi tidak perlu root.
     */
    private suspend fun statFsOf(path: String): StatFsParcelable =
        if (BaseUtil.isShellMode()) {
            runCatching {
                val stat = StatFs(path)
                StatFsParcelable(availableBytes = stat.availableBytes, totalBytes = stat.totalBytes)
            }.getOrDefault(StatFsParcelable())
        } else {
            rootService.readStatFs(path)
        }

    /**
     * Ukuran direktori dalam byte.
     *
     * Percabangan mode ada di [RemoteRootService.calculateSize]: daemon root
     * atau `du -sk` lewat shell. Penguraian keluaran TIDAK boleh dilakukan di
     * sini — pesan galat seperti `du: Permission denied` bisa terbaca sebagai
     * angka dan menghasilkan 0 tanpa penjelasan. Jalur shell sudah menutup
     * stderr dan memindai baris angka, jadi repository cukup memanggilnya.
     */
    private suspend fun sizeOf(path: String): Long = rootService.calculateSize(path)

    /**
     * Jalur yang bisa dipakai untuk mengakses penyimpanan eksternal.
     *
     * Root memakai jalur mentah `/mnt/media_rw/<uuid>`. Tanpa root jalur itu
     * tidak terjangkau — aplikasi dan shell hanya melihat volume yang sudah
     * dipasang FUSE di `/storage/<uuid>`.
     */
    private fun externalAccessPath(rawPath: String): String =
        if (BaseUtil.isShellMode() && rawPath.startsWith("/mnt/media_rw/")) {
            rawPath.replace("/mnt/media_rw/", "/storage/")
        } else {
            rawPath
        }

    fun queryActiveDirectoriesFlow(storageType: StorageType) = directoryDao.queryActiveDirectoriesFlow(storageType).distinctUntilChanged()

    /**
     * Fork ini mengutamakan kartu SD sebagai lokasi backup.
     *
     * Kalau ada penyimpanan eksternal yang aktif, pilih itu sebagai default
     * supaya backup tidak menumpuk di penyimpanan internal. Kalau tidak ada
     * kartu SD, kembali ke penyimpanan internal seperti semula.
     *
     * Pengguna tetap bisa menggantinya lewat pemilih folder.
     */
    private suspend fun resetDir() {
        val external = directoryDao.queryActiveDirectories()
            .firstOrNull { it.storageType == StorageType.EXTERNAL && it.enabled }
        if (external != null) {
            selectDir(path = external.path, id = external.id)
        } else {
            selectDir(
                path = ConstantUtil.DEFAULT_PATH,
                id = directoryDao.queryDefaultDirectoryId(StorageType.INTERNAL),
            )
        }
    }

    suspend fun deleteDir(entity: DirectoryEntity) = run {
        if (entity.selected) resetDir()
        directoryDao.delete(entity)
    }

    suspend fun addDir(pathList: List<String>) {
        val customDirList = mutableListOf<DirectoryUpsertEntity>()
        pathList.forEach { pathString ->
            if (pathString.isNotEmpty()) {
                val parent = PathUtil.getParentPath(pathString)
                val child = PathUtil.getFileName(pathString)

                // Custom storage
                val dir = DirectoryUpsertEntity(
                    id = directoryDao.queryId(parent = parent, child = child),
                    title = "",
                    parent = parent,
                    child = child,
                    storageType = StorageType.CUSTOM,
                )
                customDirList.add(dir)
            }
        }

        directoryDao.upsert(customDirList)
    }

    suspend fun selectDir(entity: DirectoryEntity) = run {
        packageDao.delete(context.readBackupSavePath().first())
        selectDir(entity.path, entity.id)
    }

    private suspend fun selectDir(path: String, id: Long?) = run {
        if (id != null) {
            context.saveBackupSavePath(path)
            directoryDao.select(id = id)
        }
    }

    suspend fun update() {
        withIOContext {
            // Inactivate all directories
            directoryDao.updateActive(active = false)

            // Internal storage
            val internalList = listDirPaths(ConstantUtil.STORAGE_EMULATED_PATH)
                .filter { it.substring(it.lastIndexOf("/") + 1).toIntOrNull() != null }.toMutableList() // Just select 0 10 999 etc.
            if (internalList.contains(DEFAULT_PATH_PARENT).not()) {
                internalList.add(DEFAULT_PATH_PARENT)
            }
            val internalDirs = mutableListOf<DirectoryUpsertEntity>()
            for (storageItem in internalList) {
                // e.g. /data/media/0
                runCatching {
                    val child = ConstantUtil.DEFAULT_PATH_CHILD
                    internalDirs.add(
                        DirectoryUpsertEntity(
                            id = directoryDao.queryId(parent = storageItem, child = child),
                            title = "",
                            parent = storageItem,
                            child = child,
                            storageType = StorageType.INTERNAL,
                        )
                    )
                }
            }
            directoryDao.upsert(internalDirs)

            // External storage
            val externalList = PreparationUtil.listExternalStorage().out.map { externalAccessPath(it) }
            val externalDirs = mutableListOf<DirectoryUpsertEntity>()
            for (storageItem in externalList) {
                // e.g. /mnt/media_rw/E7F9-FA61 (root) atau /storage/E7F9-FA61 (shell)
                runCatching {
                    val child = ConstantUtil.DEFAULT_PATH_CHILD
                    externalDirs.add(
                        DirectoryUpsertEntity(
                            id = directoryDao.queryId(parent = storageItem, child = child),
                            title = "",
                            parent = storageItem,
                            child = child,
                            storageType = StorageType.EXTERNAL,
                            active = true,
                        )
                    )
                }
            }
            directoryDao.upsert(externalDirs)

            // Activate backup/restore directories except external directories
            directoryDao.updateActive(excludeType = StorageType.EXTERNAL, active = true)

            // Read statFs of each storage
            directoryDao.queryActiveDirectories().forEach { entity ->
                val parent = entity.parent
                entity.error = ""
                val statFs = statFsOf(parent)
                entity.childUsedBytes = sizeOf(entity.path)
                entity.availableBytes = statFs.availableBytes
                entity.totalBytes = statFs.totalBytes
                if (entity.storageType == StorageType.EXTERNAL) {
                    val tags = mutableListOf<String>()
                    // Tabel mount memuat jalur mentah; jalur FUSE yang dipakai mode
                    // shell tidak ada di sana, jadi pemeriksaan format dilewati.
                    val type = if (BaseUtil.isShellMode()) {
                        ""
                    } else {
                        PreparationUtil.getExternalStorageType(parent).out.firstOrNull() ?: ""
                    }
                    tags.add(type)
                    // Check the format
                    val supported = type.isEmpty() || type.lowercase() in ConstantUtil.SupportedExternalStorageFormat
                    if (supported.not()) {
                        tags.add(context.getString(R.string.limited_4gb))
                        entity.error = "${context.getString(R.string.outdated_fs_warning)}\n\n" +
                                "${context.getString(R.string.recommend)}: ${ConstantUtil.SupportedExternalStorageFormat.toSpaceString()}"
                        entity.enabled = true
                    } else {
                        entity.error = ""
                        entity.enabled = true
                    }
                    entity.tags = tags
                    entity.type = type
                }

                directoryDao.upsert(entity)
            }

            val selectedDirectory = directoryDao.querySelectedByDirectoryType()
            if (selectedDirectory == null || (selectedDirectory.storageType == StorageType.EXTERNAL && selectedDirectory.enabled.not()) || selectedDirectory.active.not()) {
                resetDir()
            }
        }
    }

    suspend fun updateSelected() {
        withIOContext {
            directoryDao.querySelectedByDirectoryType()?.apply {
                val statFs = statFsOf(parent)
                childUsedBytes = sizeOf(path)
                availableBytes = statFs.availableBytes
                totalBytes = statFs.totalBytes
                directoryDao.upsert(this)
            }
        }
    }

    fun querySelectedByDirectoryTypeFlow() = directoryDao.querySelectedByDirectoryTypeFlow()
}
