package com.xayah.core.util.command

import com.xayah.core.util.model.ShellResult

/**
 * Pembungkus perkakas shell `bmgr` (BackupManager).
 *
 * Ini jalur untuk menangkap data privat game (`/data/data/<pkg>`) **tanpa root**.
 *
 * Kita tidak membaca folder itu sendiri — shell memang tidak punya haknya.
 * Kita memerintahkan `BackupManagerService`, yang berjalan sebagai `uid system`,
 * untuk membacanya dan menyerahkan datanya ke sebuah *transport*. Saat restore,
 * sistem pula yang menulis kembali berkasnya dengan kepemilikan uid/gid dan
 * konteks SELinux yang benar.
 *
 * Karena itu jalur ini menyelesaikan dua masalah sekaligus:
 *  1. hak baca — dilakukan oleh `uid system`, bukan oleh kita
 *  2. `chown` / `chcon` saat restore — mustahil dilakukan shell, tapi sistem bisa
 *
 * Seluruh perintah di sini berjalan sebagai shell (uid 2000), sehingga bisa
 * dijalankan baik lewat adb maupun lewat Shizuku.
 *
 * ## Batasan yang perlu diketahui
 *
 * - Transport [TRANSPORT_LOCAL] menyimpan citranya di penyimpanan internal
 *   perangkat. Shell tidak bisa membacanya, jadi backup ini **tidak bisa
 *   di-export** dan **tidak selamat dari factory reset**. Cocok untuk rollback
 *   atau memulihkan setelah salah hapus, bukan untuk pindah perangkat.
 * - Untuk lintas perangkat gunakan [TRANSPORT_CLOUD], dengan konsekuensi kuota
 *   Auto Backup 25 MB per aplikasi.
 * - Hanya berlaku untuk aplikasi yang `allowBackup` efektif true. Sejak
 *   Android 12, `allowBackup="false"` mematikan backup cloud tetapi **tidak**
 *   mematikan transfer device-to-device.
 * - Direktori `cache/` dan `no_backup/` selalu dikecualikan oleh BackupManager.
 */
object Bmgr {
    /** Transport lokal: citra disimpan di penyimpanan internal perangkat. */
    const val TRANSPORT_LOCAL = "com.android.localtransport/.LocalTransport"

    /** Transport cloud: Auto Backup ke Google Drive, kuota 25 MB per aplikasi. */
    const val TRANSPORT_CLOUD = "com.google.android.gms/.backup.BackupTransportService"

    /** Transport device-to-device: dipakai saat migrasi antar perangkat. */
    const val TRANSPORT_D2D = "com.google.android.gms/.backup.migrate.service.D2dTransport"

    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute(*args)

    /**
     * `bmgr enabled` — keluaran mentah, mis. "Backup Manager is currently enabled".
     */
    suspend fun status(): String = execute("bmgr", "enabled").outString.trim()

    suspend fun isEnabled(): Boolean {
        val text = status().lowercase()
        return text.contains("enabled") && text.contains("not enabled").not()
    }

    /** `bmgr enable true` */
    suspend fun setEnabled(enabled: Boolean = true): ShellResult =
        execute("bmgr", "enable", if (enabled) "true" else "false")

    /** `bmgr list transports` — nama transport, tanda `*` menandai yang aktif. */
    suspend fun listTransports(): List<String> =
        execute("bmgr", "list transports").out.map { it.trim() }.filter { it.isNotEmpty() }

    /** `bmgr transport` tanpa argumen — nama transport yang sedang aktif. */
    suspend fun currentTransport(): String = execute("bmgr", "transport").outString.trim()

    /** `bmgr transport <nama>` — memilih transport. */
    suspend fun selectTransport(transport: String): ShellResult =
        execute("bmgr", "transport", transport)

    /**
     * `bmgr backupnow <pkg>` — meminta sistem membackup satu paket.
     *
     * Mengembalikan [BackupOutcome] berisi status, token restore (kalau berhasil),
     * dan keluaran mentah untuk ditampilkan ke pengguna.
     */
    suspend fun backupNow(packageName: String): BackupOutcome {
        val result = execute("bmgr", "backupnow", packageName)
        val output = result.outString.trim()
        // Harus hasil untuk paket ini. Keluaran bmgr juga memuat pseudo-paket
        // `@pm@` yang selalu sukses, sehingga pencarian "with result: Success"
        // biasa bisa menutupi kegagalan paket yang diminta.
        val success = isBackupSuccess(output = output, packageName = packageName)
        return BackupOutcome(
            success = success,
            token = if (success) restoreToken() else null,
            output = output,
        )
    }

    /**
     * Dipisah supaya bisa diuji tanpa menjalankan perintah.
     */
    internal fun isBackupSuccess(output: String, packageName: String): Boolean =
        Regex("""Package\s+${Regex.escape(packageName)}\s+with result:\s*Success""")
            .containsMatchIn(output)

    /**
     * `bmgr restore <token> <pkg>` — memulihkan satu paket dari citra backup.
     *
     * Pemanggil bertanggung jawab mengosongkan data aplikasi lebih dulu
     * (`pm clear`) bila menginginkan restore bersih.
     */
    suspend fun restore(token: String, packageName: String): ShellResult =
        execute("bmgr", "restore", token, packageName)

    /** `bmgr run` — menjalankan operasi backup/restore yang terjadwal sekarang. */
    suspend fun run(): ShellResult = execute("bmgr", "run")

    /** `bmgr wipe <transport> <pkg>` — menghapus citra backup sebuah paket. */
    suspend fun wipe(transport: String, packageName: String): ShellResult =
        execute("bmgr", "wipe", transport, packageName)

    /**
     * Apakah citra dengan [token] ada di perangkat ini.
     *
     * Transport `local` menyimpan citranya di penyimpanan internal perangkat,
     * jadi citra yang dibuat di perangkat lain (atau sebelum factory reset)
     * tidak akan ditemukan. Mengembalikan `null` kalau daftarnya tidak bisa
     * dibaca — jangan menuduh citra hilang hanya karena penguraian gagal.
     */
    suspend fun hasDataSet(token: String): Boolean? =
        parseDataSetTokens(execute("bmgr", "list", "sets").out)
            ?.any { it.startsWith(token) }

    /** Dipisah supaya bisa diuji tanpa perangkat. */
    internal fun parseDataSetTokens(lines: List<String>): List<String>? {
        val tokens = lines.map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { Regex("""^(\d+)\s*:""").find(it)?.groupValues?.get(1) }
        return tokens.ifEmpty { null }
    }

    /**
     * Token restore, dibaca dari `dumpsys backup`.
     *
     * Keluarannya berbentuk:
     * ```
     * Ancestral: 0
     * Current:   1
     * ```
     * Token adalah gabungan kedua angka tersebut, mis. `"01"`.
     */
    suspend fun restoreToken(): String? = parseToken(execute("dumpsys", "backup").outString)

    /**
     * Dipisah dari [restoreToken] supaya bisa diuji tanpa menjalankan perintah.
     */
    internal fun parseToken(dumpsysOutput: String): String? {
        val ancestral = Regex("""Ancestral:\s*(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)
        val current = Regex("""Current:\s*(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)
        return if (ancestral != null && current != null) "$ancestral$current" else null
    }
}

/**
 * Hasil satu operasi `bmgr backupnow`.
 *
 * @property success apakah sistem melaporkan `with result: Success`
 * @property token token restore yang dipakai nanti, null bila backup gagal
 * @property output keluaran mentah bmgr untuk ditampilkan atau dicatat
 */
data class BackupOutcome(
    val success: Boolean,
    val token: String?,
    val output: String,
)
