package com.xayah.core.util.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji untuk pengurai token restore `bmgr`.
 *
 * Token ini penting: tanpa token yang benar, `bmgr restore` tidak akan
 * menemukan citra backup yang kita buat sebelumnya. Karena itu formatnya
 * diuji terhadap beberapa variasi keluaran `dumpsys backup`.
 */
class BmgrUtilTest {
    @Test
    fun `parseToken menggabungkan ancestral dan current`() {
        val output = """
            Backup Manager is enabled
            ...
            Ancestral: 0
            Current:   1
        """.trimIndent()

        assertEquals("01", Bmgr.parseToken(output))
    }

    @Test
    fun `parseToken tahan terhadap spasi berlebih dan tanda bintang`() {
        val output = """
            Ancestral:   0 ★
            Current:     12
        """.trimIndent()

        assertEquals("012", Bmgr.parseToken(output))
    }

    @Test
    fun `parseToken mengembalikan null bila salah satu field hilang`() {
        assertNull(Bmgr.parseToken("Current: 3"))
        assertNull(Bmgr.parseToken("Ancestral: 2"))
    }

    @Test
    fun `parseToken mengembalikan null untuk keluaran kosong`() {
        assertNull(Bmgr.parseToken(""))
    }

    @Test
    fun `parseToken mengabaikan baris yang mirip tapi bukan field yang dicari`() {
        val output = """
            Last backup: Ancestral: 9
            Current: 4
        """.trimIndent()

        // "Ancestral: 9" ada di dalam baris lain, jadi tetap terbaca.
        assertEquals("94", Bmgr.parseToken(output))
    }

    // ------------------------------------------------------------------
    // isBackupSuccess
    // ------------------------------------------------------------------

    @Test
    fun `isBackupSuccess menerima hasil sukses untuk paket yang diminta`() {
        val output = """
            Running incremental backup for 1 requested packages.
            Package @pm@ with result: Success
            Package com.example.game with progress: 5120/3072
            Package com.example.game with result: Success
            Backup finished with result: Success
        """.trimIndent()

        assertTrue(Bmgr.isBackupSuccess(output = output, packageName = "com.example.game"))
    }

    @Test
    fun `isBackupSuccess menolak saat paket gagal walau pm sukses`() {
        val output = """
            Running incremental backup for 1 requested packages.
            Package com.example.game with result: Backup is not allowed
            Package @pm@ with result: Success
            Backup finished with result: Success
        """.trimIndent()

        assertFalse(Bmgr.isBackupSuccess(output = output, packageName = "com.example.game"))
    }

    @Test
    fun `isBackupSuccess menolak keluaran tanpa hasil paket`() {
        assertFalse(Bmgr.isBackupSuccess(output = "Backup finished with result: Success", packageName = "com.example.game"))
    }

    // ------------------------------------------------------------------
    // parseDataSetTokens
    // ------------------------------------------------------------------

    @Test
    fun `parseDataSetTokens membaca daftar set`() {
        val output = listOf(
            "  01 : 12345 bytes  2 backup(s)",
            "  02 : 678 bytes  1 backup(s)",
        )

        assertEquals(listOf("01", "02"), Bmgr.parseDataSetTokens(output))
    }

    @Test
    fun `parseDataSetTokens menerima format rapat tanpa spasi`() {
        assertEquals(listOf("12"), Bmgr.parseDataSetTokens(listOf("12: 0 bytes")))
    }

    @Test
    fun `parseDataSetTokens mengembalikan null bila tidak ada baris set`() {
        assertNull(Bmgr.parseDataSetTokens(emptyList()))
        assertNull(Bmgr.parseDataSetTokens(listOf("Backup Manager is enabled", "no sets")))
    }
}
