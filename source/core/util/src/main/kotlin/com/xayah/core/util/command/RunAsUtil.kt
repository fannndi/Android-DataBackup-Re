package com.xayah.core.util.command

import android.content.pm.ApplicationInfo
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.model.ShellResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Data privat lewat `run-as` — pendamping `bmgr` untuk mode shell.
 *
 * `run-as` hanya bekerja untuk paket yang `android:debuggable="true"`, tetapi
 * kalau bisa dipakai hasilnya lebih baik daripada `bmgr`:
 *
 *  1. arsipnya berkas biasa (`user.tar.zst`), jadi **portabel** — tidak
 *     terikat perangkat seperti citra `com.android.localtransport`;
 *  2. bisa dipulihkan sebagian (mis. hanya `shared_prefs/`) karena isinya
 *     berkas, bukan citra buram;
 *  3. tetap bekerja untuk paket `allowBackup="false"`, yang oleh `bmgr`
 *     ditolak mentah-mentah.
 *
 * Tar dijalankan sebagai uid aplikasi lewat `run-as`, jadi berkas hasil
 * restore sudah dimiliki uid yang benar tanpa `chown`. Karena paket debuggable
 * adalah satu-satunya syarat, jalur ini juga otomatis tersedia untuk game
 * sideload bertanda debug — kasus yang justru sering dipakai untuk uji coba.
 *
 * ## Seluk-beluk yang mahal ditemukan
 *
 * - `tar` yang dipakai **harus** `/system/bin/tar`, bukan tar singgahan
 *   aplikasi: proses `run-as` berjalan dalam domain aplikasi dan tidak boleh
 *   mengeksekusi berkas di `/data/local/tmp`.
 * - Pipe-nya dijalankan di dalam `set -o pipefail` supaya kegagalan `tar`
 *   tidak tertutup oleh suksesnya `zstd` (tanpa itu arsip kosong dianggap
 *   sukses).
 * - Opsi `--exclude` tidak ada di sebagian ROM lawas; dukungannya diprobe
 *   sekali lalu di-cache.
 * - Redireksi ke berkas tujuan dilakukan oleh shell, bukan oleh `run-as`:
 *   uid aplikasi tidak bisa menulis ke kartu SD.
 */
object RunAs {
    /** Tar bawaan sistem: satu-satunya yang boleh dieksekusi domain aplikasi. */
    const val SYSTEM_TAR = "/system/bin/tar"

    /** `du` dan `sh` sistem; binary singgahan tidak bisa dieksekusi `run-as`. */
    private const val SYSTEM_DU = "/system/bin/du"
    private const val SYSTEM_SH = "/system/bin/sh"

    private const val STDERR_NULL = "2>/dev/null"

    /** Sama dengan daftar pengecualian mode root. */
    private val EXCLUDED_FOLDERS = listOf(".ota", "cache", "code_cache", "lib", "no_backup")

    @Volatile
    private var excludeProbed = false

    @Volatile
    private var excludeSupported = false

    private val availability = ConcurrentHashMap<String, Boolean>()

    /** Penanda murah sebelum memanggil shell: paket harus debuggable. */
    fun isDebuggable(flags: Int): Boolean = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * True kalau `run-as` benar-benar bisa dipakai untuk paket ini.
     *
     * Hasilnya di-cache: probe pertama menjalankan `run-as <pkg> id`, dan
     * jawaban sebuah paket tidak berubah selama paket itu terpasang.
     */
    suspend fun isAvailable(packageName: String): Boolean {
        availability[packageName]?.let { return it }
        val result = BaseUtil.execute(
            "run-as",
            quote(packageName),
            "id",
            STDERR_NULL,
            log = false,
        )
        val ready = result.isSuccess && result.outString.contains("uid=")
        availability[packageName] = ready
        return ready
    }

    /** Ukuran direktori privat menurut uid aplikasi, lewat `du`. */
    suspend fun calculateSize(packageName: String, path: String): Long =
        parseDuSize(
            BaseUtil.execute(
                "run-as",
                quote(packageName),
                SYSTEM_DU,
                "-sk",
                quote(path),
                STDERR_NULL,
                log = false,
            ).out
        )

    /** True kalau direktori ada menurut uid aplikasi. */
    suspend fun hasDirectory(packageName: String, path: String): Boolean =
        BaseUtil.execute(
            "run-as",
            quote(packageName),
            SYSTEM_SH,
            "-c",
            quote("test -d $path && echo ok"),
            log = false,
        ).outString.contains("ok")

    /**
     * `du -sk` mengeluarkan `<kibibyte>\t<jalur>`; hanya baris pertama yang
     * dipakai karena pemanggil meminta satu jalur saja.
     */
    internal fun parseDuSize(lines: List<String>): Long {
        val token = lines.firstOrNull { it.isNotBlank() }?.trim()?.substringBefore('\t')?.substringBefore(' ') ?: return 0
        val kib = token.toLongOrNull() ?: return 0
        return kib * 1024
    }

    /**
     * Mengompres isi [cur] (direktori privat paket) ke arsip [dst].
     *
     * [cur] memakai jalur `/data/data/<pkg>` atau `/data/user_de/<id>/<pkg>`
     * yang diterjemahkan sendiri oleh `run-as`; [dst] ditulis oleh shell.
     */
    suspend fun compress(packageName: String, cur: String, dst: String, extra: String): ShellResult {
        val parts = mutableListOf("run-as", quote(packageName), SYSTEM_TAR)
        if (systemTarSupportsExclude()) {
            EXCLUDED_FOLDERS.forEach { parts.add("--exclude=${quote("./$it")}") }
        }
        parts.addAll(listOf("-cpf", "-", "-C", quote(cur), "."))

        // zstd menulis arsipnya sebagai shell; `cat` dipakai kalau tanpa kompresi.
        val pipeline = if (extra.isEmpty()) "cat" else extra
        return BaseUtil.execute(
            "set -o pipefail; ${parts.joinToString(" ")} | $pipeline > ${quote(dst)}"
        )
    }

    /** Memulihkan arsip [src] ke direktori privat [dst] sebagai uid aplikasi. */
    suspend fun decompress(packageName: String, src: String, dst: String, extra: String): ShellResult {
        val source = if (extra.isEmpty()) "cat ${quote(src)}" else "zstd -d -c ${quote(src)}"
        val target = listOf(
            "run-as", quote(packageName), SYSTEM_TAR, "-xpf", "-", "-C", quote(dst),
        ).joinToString(" ")
        return BaseUtil.execute("set -o pipefail; $source | $target")
    }

    /**
     * Entri pertama arsip privat; dipakai untuk memastikan arsip dibuat
     * `run-as` (tanpa awalan nama paket) dan bukan oleh mode root.
     */
    suspend fun firstEntry(src: String, extra: String): String {
        val source = if (extra.isEmpty()) "cat ${quote(src)}" else "zstd -d -c ${quote(src)}"
        val result = BaseUtil.execute(
            "$source $STDERR_NULL | tar -tf - $STDERR_NULL | head -n 1",
            log = false,
        )
        return result.out.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    enum class ArchiveLayout { RUN_AS, ROOT, UNKNOWN }

    /**
     * Mode root menyimpan arsip dengan awalan `<pkg>/`; `run-as` menyimpan
     * isi direktori apa adanya (`.`). Perbedaannya menentukan cara memulihkan.
     */
    fun classifyEntry(packageName: String, firstEntry: String): ArchiveLayout = when {
        firstEntry.isBlank() -> ArchiveLayout.UNKNOWN
        firstEntry == packageName || firstEntry.startsWith("$packageName/") -> ArchiveLayout.ROOT
        else -> ArchiveLayout.RUN_AS
    }

    /** Dipisah supaya bisa diuji tanpa perangkat. */
    internal fun supportsExclude(helpText: String): Boolean = helpText.contains("--exclude")

    private suspend fun systemTarSupportsExclude(): Boolean {
        if (excludeProbed) return excludeSupported
        val help = BaseUtil.execute(SYSTEM_TAR, "--help", STDERR_NULL, log = false).outString
        excludeSupported = supportsExclude(help)
        // Gagal membaca help dianggap tidak didukung; arsip tetap benar,
        // hanya berukuran lebih besar karena cache ikut masuk.
        excludeProbed = true
        return excludeSupported
    }

    private fun quote(s: String): String = "$QUOTE$s$QUOTE"
}
