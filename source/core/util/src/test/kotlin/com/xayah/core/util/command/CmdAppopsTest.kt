package com.xayah.core.util.command

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pengurai `cmd appops get`.
 *
 * Hasil peta ini yang dipulihkan bersama izin runtime: tanpa mode yang benar,
 * restore izin hanya mengembalikan izin dasar (grant/revoke) dan op seperti
 * "hanya saat dipakai" (foreground) kembali ke bawaan.
 */
class CmdAppopsTest {
    @Test
    fun `parseOps menerima format mode sama dengan`() {
        val output = listOf(
            "Uid u0a268:",
            "  GET_USAGE_STATS: mode=allow",
            "  RECORD_AUDIO: mode=ignore",
            "  CAMERA: mode=deny",
            "  LOCATION: mode=foreground",
        )

        val ops = CmdAppops.parseOps(output)

        assertEquals(AppOpsManager.MODE_ALLOWED, ops["GET_USAGE_STATS"])
        assertEquals(AppOpsManager.MODE_IGNORED, ops["RECORD_AUDIO"])
        assertEquals(AppOpsManager.MODE_ERRORED, ops["CAMERA"])
        assertEquals(AppOpsManager.MODE_FOREGROUND, ops["LOCATION"])
    }

    @Test
    fun `parseOps menerima format mode tanpa awalan`() {
        val output = listOf(
            "Uid u0a268:",
            "  CAMERA: allow",
            "  READ_EXTERNAL_STORAGE: deny",
        )

        val ops = CmdAppops.parseOps(output)

        assertEquals(AppOpsManager.MODE_ALLOWED, ops["CAMERA"])
        assertEquals(AppOpsManager.MODE_ERRORED, ops["READ_EXTERNAL_STORAGE"])
    }

    @Test
    fun `parseOps menerima baris dengan indentasi dan paket`() {
        val output = listOf(
            "Uid u0a268:",
            "  Package com.example.game:",
            "    CAMERA: mode=allow",
        )

        val ops = CmdAppops.parseOps(output)

        assertEquals(1, ops.size)
        assertEquals(AppOpsManager.MODE_ALLOWED, ops["CAMERA"])
    }

    @Test
    fun `parseOps mengabaikan baris header dan mode tak dikenal`() {
        val output = listOf(
            "Uid u0a268:",
            "No operations.",
            "  CAMERA: mode=mungkin",
            "  bukan op: allow",
        )

        assertTrue(CmdAppops.parseOps(output).isEmpty())
    }
}
