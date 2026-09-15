package com.xayah.core.util.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pengurai jalur `run-as`.
 *
 * Jalur ini dipakai untuk data privat paket debuggable: tar sebagai uid
 * aplikasi, lalu arsipnya dipulihkan dengan cara yang sama. Dua hal yang
 * diuji di sini menentukan apakah restore memulihkan data atau malah
 * menaruhnya di tempat yang salah:
 *
 *  1. [RunAs.classifyEntry] — membedakan arsip buatan mode root (berawalan
 *     nama paket) dari arsip `run-as` (isi direktori apa adanya);
 *  2. [RunAs.excludeArgs] — daftar `--exclude` supaya cache tidak ikut
 *     tersimpan (dukungan opsinya diuji langsung di perangkat).
 */
class RunAsTest {
    @Test
    fun `classifyEntry mengenali arsip run-as`() {
        assertEquals(RunAs.ArchiveLayout.RUN_AS, RunAs.classifyEntry("com.example.game", "./shared_prefs/account.xml"))
        assertEquals(RunAs.ArchiveLayout.RUN_AS, RunAs.classifyEntry("com.example.game", "."))
        assertEquals(RunAs.ArchiveLayout.RUN_AS, RunAs.classifyEntry("com.example.game", "files/savegame.dat"))
    }

    @Test
    fun `classifyEntry mengenali arsip mode root`() {
        assertEquals(RunAs.ArchiveLayout.ROOT, RunAs.classifyEntry("com.example.game", "com.example.game"))
        assertEquals(RunAs.ArchiveLayout.ROOT, RunAs.classifyEntry("com.example.game", "com.example.game/shared_prefs/account.xml"))
    }

    @Test
    fun `classifyEntry tidak tertipu nama paket lain`() {
        assertEquals(RunAs.ArchiveLayout.RUN_AS, RunAs.classifyEntry("com.example.game", "com.example.game2/file"))
        assertEquals(RunAs.ArchiveLayout.RUN_AS, RunAs.classifyEntry("com.example.game", "com.example.games/file"))
    }

    @Test
    fun `classifyEntry mengembalikan UNKNOWN untuk entri kosong`() {
        assertEquals(RunAs.ArchiveLayout.UNKNOWN, RunAs.classifyEntry("com.example.game", ""))
        assertEquals(RunAs.ArchiveLayout.UNKNOWN, RunAs.classifyEntry("com.example.game", "   "))
    }

    @Test
    fun `excludeArgs menutup folder yang tidak perlu`() {
        val args = RunAs.excludeArgs()
        assertTrue(args.all { it.startsWith("--exclude=") })
        assertTrue(args.any { it.contains("./cache") })
        assertTrue(args.any { it.contains("./code_cache") })
        assertTrue(args.any { it.contains("./no_backup") })
    }

    @Test
    fun `parseDuSize membaca du -sk`() {
        assertEquals(1024L, RunAs.parseDuSize(listOf("1\t/data/data/com.example.game")))
        assertEquals(2048L, RunAs.parseDuSize(listOf("  2 /data/data/com.example.game")))
        assertEquals(0L, RunAs.parseDuSize(listOf("du: cannot access")))
        assertEquals(0L, RunAs.parseDuSize(emptyList()))
    }
}
