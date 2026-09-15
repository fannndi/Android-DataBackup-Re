package com.xayah.core.util.command

import android.content.Context
import android.util.Log
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
    private const val TAG = "ShellModeInitializer"

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
     * **Shizuku yang punya izin tetapi gagal disiapkan tidak langsung
     * menyerah.** Sebelumnya fungsi ini mengembalikan hasil Shizuku apa adanya,
     * sehingga kegagalan menyinggahkan binary (mis. `/data/local/tmp` tidak
     * bisa ditulis) membuat aplikasi tidak punya backend sama sekali — padahal
     * kunci ADB mungkin masih tersimpan dan bisa dipakai. Sekarang jalur ADB
     * tetap dicoba.
     *
     * @return true kalau backend siap dipakai
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        repeat(ATTEMPTS) { attempt ->
            if (ShizukuShell.hasPermission()) {
                BaseUtil.expectShellMode(context)
                if (BaseUtil.initializeShizukuMode(context = context)) return@withContext true
                Log.w(TAG, "Shizuku punya izin tetapi gagal disiapkan, mencoba ADB.")
            }
            if (AdbShell.hasSavedKey(context)) {
                BaseUtil.expectShellMode(context)
                if (BaseUtil.initializeAdbMode(context = context)) return@withContext true
            }
            if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
        }
        false
    }
}
