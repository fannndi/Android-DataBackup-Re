package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD

/**
 * Lingkungan awal yang sama untuk semua backend shell (Shizuku dan ADB).
 *
 * Menaruh direktori singgahan binary di depan `PATH` sangat penting: tanpa itu
 * `zstd` tidak ditemukan sama sekali dan `tar` jatuh ke versi bawaan sistem
 * yang tidak mengenal `--totals`, sehingga arsip backup rusak.
 */
internal object ShellEnv {
    fun wrap(command: String): String = buildString {
        append("export PATH=")
        append(QUOTE)
        append(ShellBinStaging.STAGING_DIR)
        append(":")
        append(USD)
        append("PATH")
        append(QUOTE)
        append("; ")
        append("export HOME=/data/local/tmp")
        append("; ")
        append("set -o pipefail")
        append("; ")
        append("alias awk=")
        append(QUOTE)
        append("busybox awk")
        append(QUOTE)
        append("; ")
        append("alias ps=")
        append(QUOTE)
        append("busybox ps")
        append(QUOTE)
        append("; ")
        append(command)
    }
}
