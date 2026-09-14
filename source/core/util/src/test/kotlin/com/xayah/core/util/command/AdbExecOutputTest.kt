package com.xayah.core.util.command

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji pengurai keluaran [AdbShell.exec].
 *
 * Exit code tidak dibawa oleh stream ADB, jadi [AdbShell] menambahkan baris
 * penanda `echo <id>$?` di akhir perintah. Pengurai ini yang mengambil kode dan
 * membuang barisnya. Dipisah supaya bisa diuji tanpa perangkat.
 */
class AdbExecOutputTest {
    private val marker = "__DBX_1a2b__"

    @Test
    fun `parse mengambil exit code dan membuang baris penanda`() {
        val raw = "halo\nbaris dua\n$marker 0\n"
        val (code, lines) = AdbExecOutput.parse(raw, marker)
        assertEquals(0, code)
        assertEquals(listOf("halo", "baris dua"), lines)
    }

    @Test
    fun `parse membaca exit code selain nol`() {
        val (code, lines) = AdbExecOutput.parse("ls: /nope: Permission denied\n$marker 2\n", marker)
        assertEquals(2, code)
        assertEquals(listOf("ls: /nope: Permission denied"), lines)
    }

    @Test
    fun `parse mengabaikan baris kosong dan CR`() {
        val raw = "baris satu\r\n\r\n$marker 0\r\n"
        val (code, lines) = AdbExecOutput.parse(raw, marker)
        assertEquals(0, code)
        assertEquals(listOf("baris satu"), lines)
    }

    @Test
    fun `parse kembali ke kode -1 kalau penanda tidak ada`() {
        val raw = "keluaran tanpa penanda\n"
        val (code, lines) = AdbExecOutput.parse(raw, marker)
        assertEquals(-1, code)
        assertEquals(listOf("keluaran tanpa penanda"), lines)
    }

    @Test
    fun `parse memakai penanda terakhir kalau ada beberapa`() {
        val raw = "$marker 1\nfoo\n$marker 0\n"
        val (code, lines) = AdbExecOutput.parse(raw, marker)
        assertEquals(0, code)
        assertEquals(listOf("$marker 1", "foo"), lines)
    }

    @Test
    fun `parse menangani penanda yang menempel tanpa newline`() {
        val raw = "hasil tanpa newline${marker}0"
        val (code, lines) = AdbExecOutput.parse(raw, marker)
        assertEquals(0, code)
        assertEquals(listOf("hasil tanpa newline"), lines)
    }

    @Test
    fun `parse mengembalikan keluaran kosong untuk keluaran kosong`() {
        val (code, lines) = AdbExecOutput.parse("$marker 0\n", marker)
        assertEquals(0, code)
        assertEquals(emptyList<String>(), lines)
    }
}
