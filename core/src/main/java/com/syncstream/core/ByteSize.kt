package com.syncstream.core

import java.util.Locale

/**
 * Human-readable size for transfer UIs: "512 B", "3.4 KB", "12.0 MB", "1.2 GB". Negative or
 * unknown sizes render as "–".
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 0L) return "–"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
