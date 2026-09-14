package com.xayah.core.util.command

object PackageUtil {
    /**
     * Apakah paket punya entri di keystore.
     *
     * Memeriksanya butuh `keystore_cli_v2` sebagai root, jadi pada mode Shizuku
     * jawabannya selalu false. Ini hanya memengaruhi penandaan opsional saat
     * backup, bukan jalannya backup itu sendiri.
     */
    suspend fun hasKeystore(su: String, uid: Int): Boolean {
        if (BaseUtil.isShizukuMode()) return false

        // su $uid -c keystore_cli_v2 list
        return BaseUtil.execute(
            su,
            uid.toString(),
            "-c",
            "keystore_cli_v2",
            "list",
            log = false
        ).out.size > 1
    }
}
