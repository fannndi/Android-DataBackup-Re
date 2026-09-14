package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji pembungkus lingkungan shell.
 *
 * Kesalahan di sini pernah membuat `zstd` tidak ditemukan dan `tar` memakai
 * versi sistem, sehingga seluruh arsip backup rusak.
 */
class ShellEnvTest {
    @Test
    fun `wrap menaruh direktori singgahan di depan PATH`() {
        val wrapped = ShellEnv.wrap("echo hi")
        assertTrue(wrapped.contains("export PATH=$QUOTE${ShellBinStaging.STAGING_DIR}:${USD}PATH"))
    }

    @Test
    fun `wrap mengakhiri dengan perintah yang diminta`() {
        assertTrue(ShellEnv.wrap("echo hi").endsWith("echo hi"))
    }

    @Test
    fun `wrap menyetel HOME dan pipefail`() {
        val wrapped = ShellEnv.wrap("true")
        assertTrue(wrapped.contains("export HOME=/data/local/tmp"))
        assertTrue(wrapped.contains("set -o pipefail"))
    }
}
