package com.xayah.core.util.command

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Menyiapkan kembali backend shell saat aplikasi dibuka.
 *
 * Mode shell (Shizuku/ADB) hanya dipegang di memori, jadi hilang setiap proses
 * mati. Tanpa penyiapan ulang, perintah akan jatuh ke jalur root (libsu) dan
 * di perangkat tanpa root dijalankan sebagai uid aplikasi — hasilnya kosong
 * atau gagal tanpa sebab yang jelas.
 *
 * Root tidak perlu disiapkan: libsu menanganinya sendiri.
 */
object ShellModeInitializer {
    /** Shizuku menyerahkan binder lewat provider secara asinkron saat start. */
    private const val ATTEMPTS = 6
    private const val RETRY_DELAY_MILLIS = 500L

    /**
     * Menyambung ulang backend yang dipakai terakhir.
     *
     * Urutannya: Shizuku kalau izinnya masih ada, kalau tidak ADB kalau
     * kuncinya tersimpan. Aman dipanggil di latar belakang; pengguna tidak
     * perlu menekan apa pun selama kunci/izinnya masih berlaku.
     *
     * @return true kalau backend siap dipakai
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        repeat(ATTEMPTS) { attempt ->
            if (ShizukuShell.hasPermission()) {
                BaseUtil.expectShellMode(context)
                return@withContext BaseUtil.initializeShizukuMode(context = context)
            }
            if (AdbShell.hasSavedKey(context)) {
                BaseUtil.expectShellMode(context)
                return@withContext BaseUtil.initializeAdbMode(context = context)
            }
            if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        false
    }
}
