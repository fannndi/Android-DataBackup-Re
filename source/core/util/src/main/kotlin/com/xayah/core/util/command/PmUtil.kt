package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.model.ShellResult
import java.io.File

object Pm {
    /**
     * system_server menolak membaca APK dari `/sdcard` (SELinux `sdcardfs`),
     * sehingga di mode shell APK harus disalin dulu ke `/data/local/tmp`.
     */
    private const val INSTALL_STAGING_DIR = "/data/local/tmp/databackup-install"

    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("pm", *args)

    /**
     * Menyiapkan sumber APK agar bisa dibaca `pm install`.
     *
     * Di mode root berkas di direktori privat aplikasi sudah terjangkau, jadi
     * tidak ada yang dikerjakan. Di mode shell (Shizuku/ADB) berkas berada di
     * penyimpanan eksternal dan harus disalin ke `/data/local/tmp` lebih dulu.
     */
    internal suspend fun prepareSource(src: String): String {
        if (BaseUtil.isShellMode().not()) return src
        val staged = "$INSTALL_STAGING_DIR/${File(src).name}"
        val copy = BaseUtil.execute(
            "mkdir",
            "-p",
            "$QUOTE$INSTALL_STAGING_DIR$QUOTE",
            "&&",
            "cp",
            "$QUOTE$src$QUOTE",
            "$QUOTE$staged$QUOTE",
        )
        return if (copy.isSuccess) staged else src
    }

    /**
     * Menghapus seluruh data sebuah paket.
     *
     * Dipakai sebelum `bmgr restore`, karena BackupManager menulis ulang
     * seluruh data aplikasi dan sisa data lama bisa membuat hasilnya campur.
     * Ini juga yang membuat kepemilikan berkas benar tanpa perlu `chown`.
     */
    suspend fun clear(userId: Int, packageName: String): ShellResult =
        execute("clear", "--user", "$userId", "$QUOTE$packageName$QUOTE")

    suspend fun install(userId: Int, src: String): ShellResult {
        val prepared = prepareSource(src)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // pm install --user "$userId" -r -d -t "$src"
            execute(
                "install",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-r",
                "-d",
                "-t",
                "$QUOTE$prepared$QUOTE",
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // pm install -i com.android.vending --user "$userId" -r -d -t "$src"
            execute(
                "install",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-r",
                "-d",
                "-t",
                "$QUOTE$prepared$QUOTE",
            )
        } else {
            // pm install --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -r -d -t "$src"
            execute(
                "install",
                "--bypass-low-target-sdk-block",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-r",
                "-d",
                "-t",
                "$QUOTE$prepared$QUOTE",
            )
        }
    }

    object Install {
        suspend fun create(userId: Int): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // pm install-create --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // pm install-create -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else {
            // pm install-create --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--bypass-low-target-sdk-block",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        }

        suspend fun write(session: String, srcName: String, src: String): ShellResult = run {
            // pm install-write "$session" "$srcDir" "$src"
            val prepared = prepareSource(src)
            execute(
                "install-write",
                "$QUOTE$session$QUOTE",
                "$QUOTE$srcName$QUOTE",
                "$QUOTE$prepared$QUOTE",
            )
        }

        suspend fun commit(session: String): ShellResult = run {
            // pm install-commit "$session"
            execute(
                "install-commit",
                "$QUOTE$session$QUOTE",
            )
        }
    }
}
