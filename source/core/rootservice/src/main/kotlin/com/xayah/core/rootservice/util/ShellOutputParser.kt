package com.xayah.core.rootservice.util

/**
 * Pengurai keluaran perintah shell.
 *
 * Dipisahkan dari [ShellFileOps] supaya bisa diuji tanpa perangkat: fungsi di
 * sini murni menerima baris keluaran dan mengembalikan nilai, tanpa menyentuh
 * API Android sama sekali.
 *
 * ## Kenapa memindai, bukan mengambil baris pertama
 *
 * [com.xayah.core.util.command.ShizukuShell.exec] menggabungkan stdout dan
 * stderr menjadi satu daftar baris. Artinya baris pertama bisa saja pesan galat
 * alih-alih data — misalnya `du: /path: Permission denied` sebelum angkanya.
 *
 * Karena itu setiap pengurai memindai sampai menemukan baris yang benar-benar
 * cocok dengan polanya, bukan sekadar mengambil `first()`. Tanpa ini, ukuran
 * direktori bisa terbaca 0 atau checksum salah tanpa penjelasan.
 */
internal object ShellOutputParser {
    private val WHITESPACE = Regex("\\s+")
    private const val MD5_LENGTH = 32
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Keluaran `du -sk`, dalam byte.
     *
     * Barisnya berbentuk `"<kb>\t<path>"`. Baris yang bukan angka — termasuk
     * pesan galat di stderr — dilewati.
     */
    fun parseDuSize(lines: List<String>): Long {
        for (line in lines) {
            val first = line.trim().split(WHITESPACE).firstOrNull() ?: continue
            val kb = first.toLongOrNull() ?: continue
            if (kb >= 0) return kb * 1024L
        }
        return 0L
    }

    /**
     * Keluaran `md5sum`, huruf kecil, atau null kalau tidak ada yang valid.
     *
     * Barisnya berbentuk `"<hash>  <path>"`.
     */
    fun parseMd5Sum(lines: List<String>): String? {
        for (line in lines) {
            val first = line.trim().split(WHITESPACE).firstOrNull()?.lowercase() ?: continue
            if (first.length == MD5_LENGTH && first.all { it in HEX_DIGITS }) return first
        }
        return null
    }

    /**
     * Keluaran `ls -1 -p` menjadi daftar jalur lengkap.
     *
     * `-p` menambahkan garis miring pada nama direktori, jadi pemisahan berkas
     * dan direktori cukup dari satu kali pemanggilan shell.
     *
     * @param dir direktori yang didaftar, tanpa garis miring di akhir
     * @param listFiles sertakan berkas biasa
     * @param listDirs sertakan direktori
     */
    fun parseLsEntries(
        lines: List<String>,
        dir: String,
        listFiles: Boolean,
        listDirs: Boolean,
    ): List<String> {
        if (listFiles.not() && listDirs.not()) return emptyList()

        val base = dir.trimEnd('/')
        return lines.mapNotNull { raw ->
            val name = raw.trim()
            if (name.isEmpty()) return@mapNotNull null

            // Garis miring di akhir menandai direktori; sisanya adalah namanya.
            // "." dan ".." harus dibuang dalam kedua bentuk, baik ".." maupun
            // "../" — kalau tidak, keduanya menjadi jalur palsu seperti
            // "/base/..".
            val isDir = name.endsWith("/")
            val bare = name.trimEnd('/')
            if (bare.isEmpty() || bare == "." || bare == "..") return@mapNotNull null

            if ((isDir && listDirs) || (isDir.not() && listFiles)) "$base/$bare" else null
        }
    }

    /**
     * Merapikan keluaran `base64`.
     *
     * toybox membungkus keluarannya per 76 karakter, jadi baris-barisnya harus
     * disambung kembali sebelum didekode. Spasi dan baris kosong dibuang.
     */
    fun normalizeBase64(lines: List<String>): String =
        lines.joinToString(separator = "") { it.trim() }
}
