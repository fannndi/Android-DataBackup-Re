package com.xayah.core.util.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
