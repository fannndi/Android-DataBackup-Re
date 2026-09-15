package com.xayah.core.util.command

import com.xayah.core.util.model.ShellResult

object Appops {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("appops", *args)

    suspend fun reset(userId: Int, packageName: String): ShellResult = run {
        // appops reset --user $userId $packageName
        execute(
            "reset",
            "--user",
            "$userId",
            packageName,
        )
    }

    /**
     * Memberi aplikasi ini sendiri appop `GET_USAGE_STATS`.
     *
     * Tanpa ini `StorageStatsManager.queryStatsForPackage` menolak, sehingga
     * daftar game tidak bisa menampilkan ukuran paket. Shell (uid 2000) selalu
     * boleh memberi appop; kegagalannya tidak fatal — hanya kolom ukuran yang
     * kosong seperti sebelumnya.
     */
    suspend fun allowUsageStats(packageName: String): Boolean =
        execute("set", packageName, "GET_USAGE_STATS", "allow").isSuccess
}
