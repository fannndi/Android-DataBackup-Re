package com.xayah.core.rootservice.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pengurai keluaran shell.
 *
 * Ini bagian dari lapisan Shizuku yang bisa diuji tanpa perangkat.
 *
 * ## Kontrak yang diuji
 *
 * Pengurai hanya menerima **stdout**. Perintah yang keluarannya diurai sudah
 * diberi `2>/dev/null` di [ShellFileOps], karena `ShizukuShell.exec`
 * menggabungkan stdout dan stderr sehingga pesan galat bisa terbaca sebagai
 * data — misalnya `ls: Permission denied` menjadi nama berkas palsu.
 *
 * Menutup stderr lebih andal daripada menebak di pengurai, sebab nama berkas
 * boleh mengandung titik dua dan spasi.
 */
class ShellOutputParserTest {
    // ------------------------------------------------------------------
    // du -sk
    // ------------------------------------------------------------------

    @Test
    fun `parseDuSize membaca ukuran dari keluaran normal`() {
        assertEquals(1024L * 1024L, ShellOutputParser.parseDuSize(listOf("1024\t/data/local/tmp")))
    }

    @Test
    fun `parseDuSize mengabaikan pesan galat sebelum angkanya`() {
        val lines = listOf(
            "du: /data/user/0/com.example: Permission denied",
            "2048\t/data/user/0/com.example",
        )
        assertEquals(2048L * 1024L, ShellOutputParser.parseDuSize(lines))
    }

    @Test
    fun `parseDuSize mengembalikan nol kalau tidak ada angka sama sekali`() {
        assertEquals(0L, ShellOutputParser.parseDuSize(listOf("du: No such file or directory")))
        assertEquals(0L, ShellOutputParser.parseDuSize(emptyList()))
    }

    @Test
    fun `parseDuSize menerima nol sebagai ukuran yang sah`() {
        assertEquals(0L, ShellOutputParser.parseDuSize(listOf("0\t/empty/dir")))
    }

    // ------------------------------------------------------------------
    // md5sum
    // ------------------------------------------------------------------

    @Test
    fun `parseMd5Sum membaca hash dari keluaran normal`() {
        val hash = "d41d8cd98f00b204e9800998ecf8427e"
        assertEquals(hash, ShellOutputParser.parseMd5Sum(listOf("$hash  /path/to/file")))
    }

    @Test
    fun `parseMd5Sum menormalkan huruf besar menjadi kecil`() {
        val hash = "D41D8CD98F00B204E9800998ECF8427E"
        assertEquals(hash.lowercase(), ShellOutputParser.parseMd5Sum(listOf("$hash  /path/to/file")))
    }

    @Test
    fun `parseMd5Sum melewati baris yang bukan hash`() {
        val hash = "0cc175b9c0f1b6a831c399e269772661"
        val lines = listOf("md5sum: /missing: No such file or directory", "$hash  /path")
        assertEquals(hash, ShellOutputParser.parseMd5Sum(lines))
    }

    @Test
    fun `parseMd5Sum menolak panjang atau karakter yang salah`() {
        assertNull(ShellOutputParser.parseMd5Sum(listOf("abc123  /path")))
        assertNull(ShellOutputParser.parseMd5Sum(listOf("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz  /path")))
        assertNull(ShellOutputParser.parseMd5Sum(emptyList()))
    }

    // ------------------------------------------------------------------
    // ls -1 -p
    // ------------------------------------------------------------------

    @Test
    fun `parseLsEntries membedakan direktori dan berkas dari garis miring`() {
        val lines = listOf("subdir/", "file.txt", "another/")
        assertEquals(
            listOf("/base/subdir", "/base/another"),
            ShellOutputParser.parseLsEntries(lines, "/base", listFiles = false, listDirs = true),
        )
        assertEquals(
            listOf("/base/file.txt"),
            ShellOutputParser.parseLsEntries(lines, "/base", listFiles = true, listDirs = false),
        )
    }

    @Test
    fun `parseLsEntries menyertakan keduanya saat diminta`() {
        val lines = listOf("subdir/", "file.txt")
        assertEquals(
            listOf("/base/subdir", "/base/file.txt"),
            ShellOutputParser.parseLsEntries(lines, "/base", listFiles = true, listDirs = true),
        )
    }

    @Test
    fun `parseLsEntries mengembalikan kosong kalau tidak ada yang diminta`() {
        assertTrue(
            ShellOutputParser.parseLsEntries(listOf("a/", "b"), "/base", listFiles = false, listDirs = false).isEmpty()
        )
    }

    @Test
    fun `parseLsEntries mengabaikan titik dan baris kosong`() {
        val lines = listOf("", "  ", ".", "..", "./", "../", "real.txt")
        assertEquals(
            listOf("/base/real.txt"),
            ShellOutputParser.parseLsEntries(lines, "/base", listFiles = true, listDirs = true),
        )
    }

    @Test
    fun `parseLsEntries tidak menggandakan garis miring`() {
        assertEquals(
            listOf("/base/file.txt"),
            ShellOutputParser.parseLsEntries(listOf("file.txt"), "/base/", listFiles = true, listDirs = true),
        )
    }

    @Test
    fun `parseLsEntries mempertahankan nama berkas yang mengandung spasi dan titik dua`() {
        // Nama berkas boleh mengandung keduanya, jadi pengurai tidak boleh
        // menebak-nebak berdasarkan isi baris. Pesan galat dibuang di level
        // perintah lewat 2>/dev/null.
        val lines = listOf("my save: chapter 1.dat", "sub dir/")
        assertEquals(
            listOf("/base/my save: chapter 1.dat", "/base/sub dir"),
            ShellOutputParser.parseLsEntries(lines, "/base", listFiles = true, listDirs = true),
        )
    }

    // ------------------------------------------------------------------
    // base64
    // ------------------------------------------------------------------

    @Test
    fun `normalizeBase64 menyambung baris yang dibungkus`() {
        val lines = listOf("SGVsbG8s", "IGR1bmlh", "IQ==")
        assertEquals("SGVsbG8sIGR1bmlhIQ==", ShellOutputParser.normalizeBase64(lines))
    }

    @Test
    fun `normalizeBase64 membuang spasi dan baris kosong`() {
        assertEquals("QUJD", ShellOutputParser.normalizeBase64(listOf(" QUJD ", "", "  ")))
    }

    @Test
    fun `normalizeBase64 mengembalikan string kosong untuk keluaran kosong`() {
        assertEquals("", ShellOutputParser.normalizeBase64(emptyList()))
    }
}
