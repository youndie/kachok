package ru.workinprogress.kachok.ui.session

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * How a number becomes the string the design draws.
 *
 * Two rules cover every figure in the window, and they were read off the mockup rather than
 * invented: **a byte size is three significant figures** — `3.70 GiB`, `412 MiB`, `48.2 MiB`,
 * `14.6 GiB` — and **a rate is a whole number with a space every three digits** — `15 736`,
 * `4 312`, `812`. A ratio follows the first rule: `0.11`, `2.07`, `11.4`, `18.9`.
 *
 * Three significant figures rather than a fixed two decimals because the columns are 40 and 60 px
 * wide and `11.40` does not fit where `11.4` does — the design's own numbers show it choosing this,
 * not a rounding accident.
 */
internal object Figures {
    /** What the design writes where a value does not exist. */
    const val DASH: String = "—"

    /** And where it exists but has no end. */
    const val FOREVER: String = "∞"

    private const val KIB = 1024L
    private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")

    /** `3.70 GiB`, `412 MiB`, `63.0 MiB`. */
    fun bytes(value: Long): String {
        var scaled = value.toDouble()
        var unit = 0
        while (scaled >= KIB && unit < UNITS.lastIndex) {
            scaled /= KIB
            unit++
        }
        // Bytes have no fractional part to give three figures of, so they are simply the number.
        return if (unit == 0) "$value ${UNITS[0]}" else "${threeFigures(scaled)} ${UNITS[unit]}"
    }

    /** `15 736`, `812`, `0` — the unit is the column head, not the cell. */
    fun rate(bytesPerSecond: Long): String = grouped(bytesPerSecond / KIB)

    /** `0.11`, `11.4`, `18.9`. Zero downloaded is not a ratio, it is a dash. */
    fun ratio(
        uploaded: Long,
        downloaded: Long,
    ): String = if (downloaded <= 0) DASH else threeFigures(uploaded.toDouble() / downloaded)

    /** `78%`. */
    fun percent(
        part: Long,
        whole: Long,
    ): String = if (whole <= 0) DASH else "${part * PERCENT / whole}%"

    fun fraction(
        part: Long,
        whole: Long,
    ): Float = if (whole <= 0) 0f else (part.toDouble() / whole).toFloat().coerceIn(0f, 1f)

    /**
     * `3m 20s`, `8m 04s`, `47s`, `14s`.
     *
     * The seconds are padded only when there are minutes in front of them, which is what stops a
     * countdown from changing width every second in a 52 px column.
     */
    fun eta(remaining: Duration): String {
        val total = remaining.inWholeSeconds
        if (total < 0) return DASH
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return when {
            hours > 0 -> "${hours}h ${pad(minutes)}m"
            minutes > 0 -> "${minutes}m ${pad(seconds)}s"
            else -> "${seconds}s"
        }
    }

    /**
     * `3 m`, `27 m`, `41 s` — a duration at the coarsest unit that still says something.
     *
     * The design writes tracker times this way and not as `0h 03m`: a tracker announcing every half
     * hour needs one number, and two would be two things to read at a glance instead of one.
     */
    fun ago(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return when {
            safe < SECONDS_PER_MINUTE -> "$safe s"
            safe < SECONDS_PER_HOUR -> "${safe / SECONDS_PER_MINUTE} m"
            else -> "${safe / SECONDS_PER_HOUR} h"
        }
    }

    /** `heap 41 / 128 MiB`, which is the one number that says the 128 MiB budget still holds. */
    fun heap(
        usedBytes: Long,
        maxBytes: Long,
    ): String = "heap ${usedBytes / KIB / KIB} / ${maxBytes / KIB / KIB} MiB"

    /** `24/4 · 61`. One string, because the design draws it in one colour. */
    fun peers(
        connected: Int,
        unchoked: Int,
        outstanding: Int,
    ): String = "$connected/$unchoked · $outstanding"

    private fun pad(value: Long): String = if (value < TEN) "0$value" else "$value"

    /**
     * Three significant figures, and never a trailing `.0` where the integer part already has
     * three: `412 MiB`, not `412. MiB` and not `412.0 MiB`.
     */
    private fun threeFigures(value: Double): String {
        val magnitude = abs(value)
        val decimals =
            when {
                magnitude >= HUNDRED -> 0
                magnitude >= TEN -> 1
                else -> 2
            }
        val factor = generateSequence(1L) { it * TEN_L }.elementAt(decimals)
        val rounded = (value * factor).roundToLong()
        val whole = rounded / factor
        val part = abs(rounded % factor)
        return if (decimals == 0) "$whole" else "$whole.${part.toString().padStart(decimals, '0')}"
    }

    /** `15 736`. A plain space, which in JetBrains Mono is exactly one figure wide. */
    private fun grouped(value: Long): String {
        val digits = value.toString()
        return digits
            .reversed()
            .chunked(GROUP)
            .joinToString(" ")
            .reversed()
    }

    private const val PERCENT = 100
    private const val TEN = 10
    private const val TEN_L = 10L
    private const val HUNDRED = 100
    private const val GROUP = 3
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
}
