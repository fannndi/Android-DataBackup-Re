package com.xayah.core.rootservice.util

import android.content.Context
import android.util.Base64
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.model.ShellResult
import java.io.File

/**
 * Padanan operasi berkas [com.xayah.core.rootservice.service.RemoteRootService]
 * yang dijalankan lewat shell, bukan lewat daemon root.
 *
 * Dipakai saat mode Shizuku aktif, sehingga `RemoteRootService` tidak perlu
 * menyentuh libsu sama sekali. Semua perintah di sini adalah perintah shell
 * biasa (`ls`, `cp`, `rm`, `find`, `du`, `md5sum`, `base64`) yang berjalan
 * sebagai uid 2000.
 *
 * ## Batasan yang melekat
 *
 * Shell tidak bisa membaca direktori privat aplikasi lain, jadi operasi ke
 * `/data/user/<id>/<pkg>` tetap gagal. Bagian itu ditangani jalur `bmgr`,
 * bukan di sini. Yang bisa dikerjakan lapisan ini adalah seluruh berkas di
 * penyimpanan bersama dan penyimpanan eksternal: APK, OBB, dan `Android/data`.
 */
object ShellFileOps {
    private fun quote(s: String): String = "$QUOTE$s$QUOTE"

    private suspend fun run(vararg args: String, log: Boolean = true): ShellResult =
        BaseUtil.execute(*args, log = log)

    private fun parentOf(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index <= 0) "/" else path.substring(0, index)
    }

    // ------------------------------------------------------------------
    // Direktori sementara
    //
    // Shell tidak bisa membaca `cacheDir` privat aplikasi, jadi berkas
    // sementara untuk tulis-menulis harus diletakkan di penyimpanan eksternal
    // aplikasi, yang bisa dibaca shell lewat grup ext_data_rw.
    // ------------------------------------------------------------------

    private var tmpDir: String = ""

    /**
     * Membuang stderr pada perintah yang keluarannya diurai.
     *
     * `ShizukuShell.exec` menggabungkan stdout dan stderr menjadi satu daftar
     * baris, sehingga pesan galat bisa terbaca sebagai data. Dua akibat nyata:
     * `ls: Permission denied` menjadi nama berkas palsu, dan galat `find`
     * menjadi jalur palsu yang ikut diproses.
     *
     * Menutup stderr membuat pengurai hanya melihat data sebenarnya. Ini lebih
     * andal daripada menebak di pengurai, karena nama berkas boleh mengandung
     * titik dua dan spasi.
     */
    private const val STDERR_TO_NULL = "2>/dev/null"

    fun configure(context: Context) {
        tmpDir = context.externalCacheDir?.absolutePath.orEmpty()
    }

    private fun tmpFile(): File? = tmpDir.takeIf { it.isNotEmpty() }?.let { File(it, "databackup-tmp") }

    // ------------------------------------------------------------------
    // Operasi berkas dasar
    // ------------------------------------------------------------------

    suspend fun mkdirs(path: String): Boolean = run("mkdir", "-p", quote(path), log = false).isSuccess

    suspend fun exists(path: String): Boolean = run("test", "-e", quote(path), log = false).isSuccess

    suspend fun createNewFile(path: String): Boolean = run("touch", quote(path), log = false).isSuccess

    suspend fun deleteRecursively(path: String): Boolean = run("rm", "-rf", quote(path), log = false).isSuccess

    suspend fun renameTo(src: String, dst: String): Boolean =
        run("mv", quote(src), quote(dst), log = false).isSuccess

    /**
     * `overwrite = false` memakai `cp -n` supaya berkas yang sudah ada tidak
     * tertimpa, sesuai perilaku daemon root.
     */
    suspend fun copyTo(path: String, targetPath: String, overwrite: Boolean): Boolean =
        run("cp", if (overwrite) "-f" else "-n", quote(path), quote(targetPath), log = false).isSuccess

    suspend fun copyRecursively(path: String, targetPath: String, overwrite: Boolean): Boolean =
        run("cp", "-R", if (overwrite) "-f" else "-n", quote(path), quote(targetPath), log = false).isSuccess

    /**
     * Anak langsung sebuah direktori.
     *
     * `ls -p` menambahkan garis miring pada nama direktori, jadi pemisahan
     * berkas dan direktori cukup dari satu kali pemanggilan shell.
     */
    suspend fun listFilePaths(path: String, listFiles: Boolean, listDirs: Boolean): List<String> =
        ShellOutputParser.parseLsEntries(
            lines = run("ls", "-1", "-p", quote(path), STDERR_TO_NULL, log = false).out,
            dir = path,
            listFiles = listFiles,
            listDirs = listDirs,
        )

    // ------------------------------------------------------------------
    // Baca tulis isi berkas
    // ------------------------------------------------------------------

    /**
     * Isi berkas sebagai teks.
     *
     * stderr ditutup karena pemanggil mengurai hasilnya sebagai JSON —
     * `cat: ...: No such file or directory` tidak boleh menjadi isi berkas.
     */
    suspend fun readText(path: String): String = run("cat", quote(path), STDERR_TO_NULL, log = false).outString

    suspend fun writeText(text: String, dst: String): Boolean = writeBytes(text.toByteArray(), dst)

    /**
     * Isi berkas biner.
     *
     * Dibaca sebagai base64 supaya perpindahan lewat shell tidak merusak byte.
     */
    suspend fun readBytes(src: String): ByteArray {
        val encoded = ShellOutputParser.normalizeBase64(run("base64", quote(src), STDERR_TO_NULL, log = false).out)
        if (encoded.isBlank()) return ByteArray(0)
        return runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
    }

    /**
     * Menulis berkas biner.
     *
     * Aplikasi menulis dulu ke direktori sementaranya sendiri, lalu shell
     * menyalinnya. Tidak bisa langsung karena shell tidak punya akses tulis ke
     * seluruh lokasi tujuan, dan tidak bisa memakai `stdin` karena perintah
     * dijalankan satu proses per perintah.
     */
    suspend fun writeBytes(bytes: ByteArray, dst: String): Boolean {
        val tmp = tmpFile() ?: return false
        return runCatching {
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            mkdirs(parentOf(dst)) && copyTo(tmp.absolutePath, dst, overwrite = true)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Ukuran, checksum, dan penelusuran
    // ------------------------------------------------------------------

    /** Ukuran direktori atau berkas dalam byte, lewat `du`. */
    suspend fun calculateSize(path: String): Long =
        ShellOutputParser.parseDuSize(run("du", "-sk", quote(path), STDERR_TO_NULL, log = false).out)

    /** MD5 heksadesimal huruf kecil, atau null kalau gagal. */
    suspend fun calculateMD5(src: String): String? =
        ShellOutputParser.parseMd5Sum(run("md5sum", quote(src), STDERR_TO_NULL, log = false).out)

    /**
     * Semua **berkas** di bawah [path], rekursif.
     *
     * Direktori sengaja tidak disertakan karena pemanggilnya memang hanya
     * memakai daftar berkas, sama seperti implementasi daemon root.
     */
    suspend fun walkFileTree(path: String): List<PathParcelable> =
        run("find", quote(path), "-type", "f", STDERR_TO_NULL, log = false).out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { PathParcelable(it) }

    /** Menghapus direktori kosong dari yang paling dalam. */
    suspend fun clearEmptyDirectoriesRecursively(path: String) {
        run("find", quote(path), "-mindepth", "1", "-type", "d", "-empty", "-delete", STDERR_TO_NULL, log = false)
    }

    /**
     * Memberi izin penuh secara rekursif.
     *
     * `chmod` tidak bisa mengubah pemilik; `chown` dan `chcon` tetap butuh
     * root, jadi keduanya tidak dilakukan di sini.
     */
    suspend fun setAllPermissions(src: String) {
        run("chmod", "-R", "777", quote(src), log = false)
    }

    // ------------------------------------------------------------------
    // Operasi paket yang bisa dilakukan sebagai shell
    // ------------------------------------------------------------------

    /** Jalur APK sebuah paket, lewat `pm path`. */
    suspend fun getPackageSourceDir(packageName: String): List<String> =
        run("pm", "path", quote(packageName), log = false).out
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }

    suspend fun grantRuntimePermission(packageName: String, permName: String): Boolean =
        run("pm", "grant", quote(packageName), quote(permName), log = false).isSuccess

    suspend fun revokeRuntimePermission(packageName: String, permName: String): Boolean =
        run("pm", "revoke", quote(packageName), quote(permName), log = false).isSuccess

    suspend fun forceStopPackageAsUser(packageName: String, userId: Int): Boolean =
        run("am", "force-stop", "--user", userId.toString(), quote(packageName), log = false).isSuccess

    /**
     * Mode operasi appops.
     *
     * `appops set <pkg> <kode-op> <mode>` menerima angka mode langsung, jadi
     * tidak perlu menerjemahkan ke nama.
     */
    suspend fun setOpsMode(code: Int, packageName: String?, mode: Int): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return run("appops", "set", quote(packageName), code.toString(), mode.toString(), log = false).isSuccess
    }

    /** Mengaktifkan atau menonaktifkan komponen aplikasi lewat `pm`. */
    suspend fun setApplicationEnabledSetting(packageName: String, newState: Int, userId: Int): Boolean {
        val action = when (newState) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
            else -> return true // DEFAULT: tidak ada yang perlu diubah
        }
        return run("pm", action, "--user", userId.toString(), quote(packageName), log = false).isSuccess
    }

    /**
     * Mengubah mode daya layar.
     *
     * Memaksa layar mati hanya bisa dilakukan sistem, jadi di sini tidak ada
     * yang dikerjakan. Aplikasi tetap berjalan di foreground service, dan
     * layar yang tetap menyala justru lebih aman untuk proses panjang.
     */
    suspend fun setDisplayPowerMode(mode: Int): Boolean = true

    /** Batas waktu layar mati dalam milidetik, lewat `settings`. */
    suspend fun getScreenOffTimeout(): Int =
        run("settings", "get", "system", "screen_off_timeout", log = false).outString.trim().toIntOrNull() ?: -1

    suspend fun setScreenOffTimeout(timeout: Int): Boolean =
        run("settings", "put", "system", "screen_off_timeout", timeout.toString(), log = false).isSuccess
}
