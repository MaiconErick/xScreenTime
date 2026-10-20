package com.maiconerick.xscreentime.ui

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// AMOLED neon palette (shared with the xClaudeUsage widget look).
val Black = Color(0xFF000000)
val Surface = Color(0xFF0C0A10)
val SurfaceHi = Color(0xFF18151E)
val Track = Color(0xFF141219)
val TextMain = Color(0xFFF3EEE9)
val TextDim = Color(0xFF8C8794)
val TextFaint = Color(0xFF55515C)
val Cyan = Color(0xFF67E8F9)
val Violet = Color(0xFFA78BFA)
val Magenta = Color(0xFFF472B6)
val Orange = Color(0xFFE07A52)
val Amber = Color(0xFFFFB020)
val Green = Color(0xFF4ADE80)
val Red = Color(0xFFFF3D6E)

object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val dayLong = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val dayShort = DateTimeFormatter.ofPattern("EEEEE", Locale.ENGLISH)

    /** "4h 12m", "38m", "45s". */
    fun dur(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    fun time(ms: Long): String = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun day(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dayLong.format(d)
        }
    }

    fun dayLetter(d: LocalDate): String = dayShort.format(d)

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** "14:18" today, "Yesterday 14:18", "3 Oct" this year, "3 Oct 2025" before. */
    fun ago(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val d = z.toLocalDate()
        val today = LocalDate.now()
        return when {
            d == today -> hm.format(z)
            d == today.minusDays(1) -> "Yesterday ${hm.format(z)}"
            d.year == today.year -> dayMonth.format(z)
            else -> dayMonthYear.format(z)
        }
    }
}
