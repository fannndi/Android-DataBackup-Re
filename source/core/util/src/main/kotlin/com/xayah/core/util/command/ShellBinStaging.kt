package com.xayah.core.util.command

import android.content.Context
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.binArchivePath
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Menyinggahkan binary bawaan aplikasi supaya bisa dijalankan shell.
 *
 * Dipakai bersama oleh [ShizukuShell] dan [AdbShell]: keduanya berjalan sebagai
 * `uid 2000`, dan direktori privat aplikasi (0700) tidak bisa ditembus uid itu.
 * Dua langkah, karena satu direktori tidak bisa dijangkau langsung:
 *  1. aplikasi menulis binary ke direktori eksternalnya sendiri
 *     (`/storage/emulated/0/Android/data/<pkg>/files/shell-bin`) — shell bisa
 *     membacanya lewat grup `ext_data_rw`
 *  2. shell menyalinnya ke [STAGING_DIR] dan memberi bit eksekusi
 *
 * Aman dipanggil berkali-kali; pemanggil yang menyimpan status "sudah".
 */
internal object ShellBinStaging {
    /** Direktori singgahan binary, bisa dieksekusi oleh shell. */
    const val STAGING_DIR = "/data/local/tmp/databackup-bin"

    /** Direktori eksternal aplikasi, jembatan antara aplikasi dan shell. */
    private const val EXTERNAL_DIR_NAME = "shell-bin"

    /** Menyalin binary butuh waktu lebih lama daripada perintah biasa. */
    private const val STAGE_TIMEOUT_SECONDS = 60L

    suspend fun stage(
        context: Context,
        exec: suspend (command: String, timeoutSeconds: Long) -> ShellResult,
    ): Boolean {
        val externalDir = File(context.getExternalFilesDir(null), EXTERNAL_DIR_NAME)
        if (copyBundledBinaries(context, externalDir).not()) return false

        val copy = exec(
            "rm -rf $STAGING_DIR; mkdir -p $STAGING_DIR; " +
                "cp $QUOTE${externalDir.absolutePath}$QUOTE/* $STAGING_DIR/; " +
                "chmod 755 $STAGING_DIR/*; " +
                "ls $STAGING_DIR",
            STAGE_TIMEOUT_SECONDS,
        )
        return copy.isSuccess && copy.outString.isNotBlank()
    }

    /**
     * Menyalin isi `bin.zip` ke [targetDir] tanpa perlu root.
     *
     * Berbeda dengan [BaseUtil.releaseBase] yang menulis ke `filesDir` privat,
     * di sini tujuannya direktori eksternal supaya shell bisa membacanya.
     */
    private suspend fun copyBundledBinaries(context: Context, targetDir: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) return@runCatching true

                targetDir.mkdirs()
                val archive = File(context.binArchivePath())
                if (archive.exists().not()) {
                    // bin.zip belum diekstrak; ambil dari aset.
                    context.resources.assets.open("bin.zip").use { input ->
                        archive.outputStream().use { input.copyTo(it) }
                    }
                }

                val zip = net.lingala.zip4j.ZipFile(archive)
                zip.extractAll(targetDir.absolutePath)
                targetDir.listFiles()?.forEach { it.setReadable(true, false); it.setExecutable(true, false) }
                true
            }.getOrDefault(false)
        }
}
