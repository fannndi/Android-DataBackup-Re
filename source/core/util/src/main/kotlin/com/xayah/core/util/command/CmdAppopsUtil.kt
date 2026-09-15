package com.xayah.core.util.command

import android.app.AppOpsManager

/**
 * Membaca mode AppOps paket lain lewat shell: `cmd appops get <pkg>`.
 *
 * Mode root membacanya dengan `AppOpsManager.getOpsForPackage`, yang butuh
 * `GET_APP_OPS_STATS` — hak yang tidak dimiliki aplikasi. shell (uid 2000)
 * punya hak itu, dan `cmd appops get` adalah pintu yang stabil sejak
 * Android 6 sampai 14.
 *
 * Hanya op dengan mode **non-default** yang dicetak, jadi hasil peta ini
 * memang pas untuk dipulihkan: op yang tidak tercatat berarti mengikuti
 * bawaan, dan restore tidak perlu menyentuhnya.
 *
 * Format keluaran berbeda antar versi:
 * ```
 * Uid u0a268:
 *   CAMERA: allow          // Android 9-10
 *   RECORD_AUDIO: mode=allow   // Android 11+
 * ```
 * Keduanya diterima pengurai yang sama.
 */
object CmdAppops {
    private val lineRegex = Regex("""^\s*([A-Z][A-Z0-9_]*):\s*(?:mode=)?([a-z]+)\s*$""")

    private val modes: Map<String, Int> = mapOf(
        "allow" to AppOpsManager.MODE_ALLOWED,
        "ignore" to AppOpsManager.MODE_IGNORED,
        "deny" to AppOpsManager.MODE_ERRORED,
        "default" to AppOpsManager.MODE_DEFAULT,
        "foreground" to AppOpsManager.MODE_FOREGROUND,
    )

    suspend fun readOps(packageName: String): Map<String, Int> =
        parseOps(
            BaseUtil.execute("cmd", "appops", "get", "\"$packageName\"", log = false).out
        )

    /** Dipisah supaya bisa diuji tanpa perangkat. */
    internal fun parseOps(lines: List<String>): Map<String, Int> {
        val ops = mutableMapOf<String, Int>()
        lines.forEach { line ->
            val match = lineRegex.find(line) ?: return@forEach
            val mode = modes[match.groupValues[2].lowercase()] ?: return@forEach
            ops[match.groupValues[1]] = mode
        }
        return ops
    }
}
