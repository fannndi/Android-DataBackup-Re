package com.xayah.core.util.command

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.binArchivePath
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Backend eksekusi perintah lewat Shizuku — berjalan sebagai `uid 2000` (shell)
 * pada perangkat tanpa root.
 *
 * Ini pengganti libsu untuk perangkat yang tidak di-root. Shizuku sudah
 * menyiapkan proses server dengan hak ADB; kita meminjam hak itu lewat
 * `IShizukuService.newProcess`.
 *
 * ## Kenapa perlu menyinggahkan binary
 *
 * Aplikasi ini membawa `busybox`, `tar`, dan `zstd` di `filesDir` privatnya.
 * Direktori privat aplikasi ber-mode `0700` milik uid aplikasi, sehingga
 * **shell tidak bisa menembusnya** — binary itu jadi tidak terjangkau.
 *
 * Solusinya: salin binary ke direktori eksternal aplikasi
 * (`/storage/emulated/0/Android/data/<pkg>/files/`) yang bisa dibaca shell,
 * lalu minta shell menyalinnya ke `/data/local/tmp/` yang bisa dieksekusi.
 * Hasilnya diringkas di [BinStagingDir] dan dipakai sebagai `PATH`.
 *
 * ## Batasan
 *
 * - Tidak ada `nsenter --mount=/proc/1/ns/mnt` (butuh uid 0).
 * - Tidak ada `FLAG_MOUNT_MASTER`.
 * - Tidak bisa `chown`/`chcon` ke uid aplikasi lain.
 * - Data privat `/data/user/<id>/<pkg>` tetap tidak terbaca; pakai [Bmgr].
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"

    /** Direktori singgahan binary, bisa dieksekusi oleh shell. */
    const val BinStagingDir = "/data/local/tmp/databackup-bin"

    /** Direktori eksternal aplikasi, jembatan antara aplikasi dan shell. */
    private const val ExternalBinDirName = "shell-bin"

    /** Kode permintaan izin Shizuku. Bebas, asal konsisten. */
    const val PERMISSION_REQUEST_CODE = 0x5A17

    private var cachedService: IShizukuService? = null
    private var staged = false

    // ------------------------------------------------------------------
    // Ketersediaan dan izin
    // ------------------------------------------------------------------

    /** Shizuku terpasang dan servernya hidup. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Binder sudah diterima dan izin sudah diberikan.
     *
     * Selalu false sebelum Android 11 karena Shizuku versi lama tidak
     * mendukung permintaan izin.
     */
    fun hasPermission(): Boolean = runCatching {
        isAvailable() && Shizuku.isPreV11().not() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Meminta izin Shizuku ke pengguna. Hasilnya lewat listener di pemanggil. */
    fun requestPermission() {
        if (isAvailable() && Shizuku.isPreV11().not()) {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { Log.e(TAG, "Gagal meminta izin Shizuku", it) }
        }
    }

    /**
     * uid proses server Shizuku.
     *
     * `0` berarti perangkat ini di-root dan Shizuku dijalankan sebagai root —
     * dalam kondisi itu jalur libsu biasa lebih baik. `2000` berarti shell.
     */
    fun serverUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** True kalau Shizuku berjalan sebagai shell (bukan root). */
    fun isShellMode(): Boolean = serverUid() == 2000

    // ------------------------------------------------------------------
    // Eksekusi
    // ------------------------------------------------------------------

    private fun service(): IShizukuService? {
        cachedService?.let { return it }
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return null
        return runCatching { IShizukuService.Stub.asInterface(binder) }
            .onFailure { Log.e(TAG, "Gagal mengambil IShizukuService", it) }
            .getOrNull()
            ?.also { cachedService = it }
    }

    /** Lupakan service yang di-cache, dipakai saat binder mati. */
    fun reset() {
        cachedService = null
        staged = false
    }

    /**
     * Menjalankan [command] sebagai shell dan mengembalikan hasilnya.
     *
     * Satu perintah = satu proses. Ini lebih sederhana dan tidak bisa macet
     * seperti sesi shell persisten, dengan harga satu kali pembuatan proses
     * per perintah.
     */
    suspend fun exec(command: String, timeoutSeconds: Long = 30): ShellResult = withContext(Dispatchers.IO) {
        val result = ShellResult(code = -1, input = listOf(command), out = listOf())

        val svc = service() ?: run {
            Log.e(TAG, "Shizuku tidak tersedia")
            return@withContext result
        }

        val wrapped = buildCommand(command)
        val process = runCatching {
            svc.newProcess(arrayOf("sh", "-c", wrapped), null, null)
        }.onFailure {
            Log.e(TAG, "newProcess gagal", it)
            // Binder kemungkinan mati; paksa ambil ulang lain kali.
            reset()
        }.getOrNull() ?: return@withContext result

        coroutineScope {
            val out = async(Dispatchers.IO) {
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).bufferedReader()
                        .use { it.readLines() }
                }.getOrDefault(emptyList())
            }
            val err = async(Dispatchers.IO) {
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).bufferedReader()
                        .use { it.readLines() }
                }.getOrDefault(emptyList())
            }

            // stdin tidak dipakai karena perintah dijalankan lewat `sh -c`.
            // Menutupnya mencegah perintah yang kebetulan membaca stdin menggantung.
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).close()
            }

            val wait = async(Dispatchers.IO) { runCatching { process.waitFor() }.getOrDefault(-1) }
            val code = withTimeoutOrNull(timeoutSeconds * 1000) { wait.await() }

            if (code == null) {
                Log.e(TAG, "Perintah melewati batas waktu: $command")
                runCatching { process.destroy() }
            }

            result.code = code ?: -1
            result.out = (out.await() + err.await()).filter { it.isNotBlank() }
        }

        result
    }

    // ------------------------------------------------------------------
    // Penyiapan lingkungan
    // ------------------------------------------------------------------

    /**
     * Menyiapkan `PATH`, `HOME`, `pipefail`, dan alias yang dipakai aplikasi.
     *
     * Perintah dijalankan lewat `sh -c`, jadi setiap variabel harus diekspor
     * ulang pada setiap pemanggilan.
     */
    private fun buildCommand(command: String): String = buildString {
        append("export PATH=")
        append(QUOTE)
        append(BinStagingDir)
        append(":")
        append(USD)
        append("PATH")
        append(QUOTE)
        append("; ")
        append("export HOME=/data/local/tmp")
        append("; ")
        append("set -o pipefail")
        append("; ")
        append("alias awk=")
        append(QUOTE)
        append("busybox awk")
        append(QUOTE)
        append("; ")
        append("alias ps=")
        append(QUOTE)
        append("busybox ps")
        append(QUOTE)
        append("; ")
        append(command)
    }

    /**
     * Menyalin binary bawaan aplikasi supaya bisa dijalankan shell.
     *
     * Dua langkah, karena satu direktori tidak bisa dijangkau langsung:
     *  1. aplikasi menulis binary ke direktori eksternalnya sendiri
     *     (`/storage/emulated/0/Android/data/<pkg>/files/shell-bin`) — shell
     *     bisa membacanya lewat grup `ext_data_rw`
     *  2. shell menyalinnya ke [BinStagingDir] dan memberi bit eksekusi
     *
     * Aman dipanggil berkali-kali; hanya benar-benar bekerja sekali.
     */
    suspend fun stageBinaries(context: Context): Boolean {
        if (staged) return true

        val externalDir = File(context.getExternalFilesDir(null), ExternalBinDirName)
        if (copyBundledBinaries(context, externalDir).not()) return false

        val copy = exec(
            "rm -rf $BinStagingDir; mkdir -p $BinStagingDir; " +
                "cp $QUOTE${externalDir.absolutePath}$QUOTE/* $BinStagingDir/; " +
                "chmod 755 $BinStagingDir/*; " +
                "ls $BinStagingDir",
            timeoutSeconds = 60,
        )
        if (copy.isSuccess.not() || copy.outString.isBlank()) {
            Log.e(TAG, "Gagal menyinggahkan binary: ${copy.outString}")
            return false
        }

        staged = true
        return true
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
            }.onFailure { Log.e(TAG, "Gagal menyalin binary", it) }.getOrDefault(false)
        }
}
