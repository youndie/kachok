package io.github.youndie.kachok.control

import java.util.Locale
import kotlin.math.roundToLong

/**
 * A rate as a person writes it, and as one reads it back
 * ([B-151](../../../../../../../../../docs/backlog/B-151-one-limit-for-the-whole-client.md)).
 *
 * People give a link's speed in bits — "10 Mbit/s" is what a provider sells — and a client shows
 * transfer rates in bytes. Both are taken, and every rate is said back in both, so a limit set in
 * one unit can be checked against the speeds shown in the other.
 *
 * **Lowercase `b` is a bit and uppercase `B` a byte**, the way the units are written: `10 Mb/s` is
 * ten megabits and `10 MB/s` ten megabytes. `bit`/`бит` and `byte`/`байт` spelled out are the same
 * two. Decimal prefixes (`k`, `M`, `G`) are powers of 1000 and binary ones (`Ki`, `Mi`, `Gi`) of
 * 1024. A bare number is refused rather than guessed at: zero is the only number without a unit
 * that means one thing.
 */
public object Rates {
    /** Bytes a second for [text]; zero for "no limit". Throws with a sentence on anything else. */
    public fun parse(text: String): Long {
        val trimmed = text.trim()
        if (trimmed.lowercase(Locale.ROOT) in NO_LIMIT) return 0
        val match =
            NUMBER_AND_UNIT.matchEntire(trimmed)
                ?: throw IllegalArgumentException("\"$text\" is not a rate; write it like 10 Mbit/s, 1.5 MB/s or off")
        val number = match.groupValues[1].replace(',', '.').toDouble()
        val unit =
            match.groupValues[2]
                .removeSuffix("/s")
                .removeSuffix("/с")
                .removeSuffix("ps")
                .trim()
        if (unit.isEmpty()) {
            if (number == 0.0) return 0
            throw IllegalArgumentException("\"$text\" has no unit; say Mbit/s, MB/s, KiB/s or another one")
        }
        return (number * bytesPerUnit(unit, text)).roundToLong()
    }

    /** "10 Mbit/s (1.19 MiB/s)", or "no limit" for zero. */
    public fun describe(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "no limit"
        return "${bits(bytesPerSecond)} (${bytes(bytesPerSecond)})"
    }

    private fun bits(bytesPerSecond: Long): String {
        val bits = bytesPerSecond * BITS_PER_BYTE.toDouble()
        return when {
            bits >= GIGA -> "${figure(bits / GIGA)} Gbit/s"
            bits >= MEGA -> "${figure(bits / MEGA)} Mbit/s"
            else -> "${figure(bits / KILO)} kbit/s"
        }
    }

    private fun bytes(bytesPerSecond: Long): String {
        val value = bytesPerSecond.toDouble()
        return when {
            value >= GIBI -> "${figure(value / GIBI)} GiB/s"
            value >= MEBI -> "${figure(value / MEBI)} MiB/s"
            value >= KIBI -> "${figure(value / KIBI)} KiB/s"
            else -> "$bytesPerSecond B/s"
        }
    }

    /** Three significant figures and no trailing zeros, as the rest of the client shows numbers. */
    private fun figure(value: Double): String {
        val decimals =
            when {
                value >= HUNDRED -> 0
                value >= TEN -> 1
                else -> 2
            }
        return String
            .format(Locale.ROOT, "%.${decimals}f", value)
            .let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
    }

    private fun bytesPerUnit(
        unit: String,
        text: String,
    ): Double {
        val lower = unit.lowercase(Locale.ROOT)
        val (prefix, isBits) =
            when {
                lower.endsWith("bits") -> lower.dropLast(4) to true

                lower.endsWith("bit") || lower.endsWith("бит") -> lower.dropLast(3) to true

                lower.endsWith("bytes") -> lower.dropLast(5) to false

                lower.endsWith("byte") || lower.endsWith("байт") -> lower.dropLast(4) to false

                unit.endsWith("b") -> lower.dropLast(1) to true

                unit.endsWith("B") -> lower.dropLast(1) to false

                // «мб», «кб»: in Russian the short form is the byte, the bit is spelled «бит».
                lower.endsWith("б") -> lower.dropLast(1) to false

                else -> throw IllegalArgumentException("\"$text\": say whether that is bits (Mbit/s) or bytes (MB/s)")
            }
        val multiplier =
            when (prefix) {
                "" -> 1.0
                "k", "к" -> KILO
                "m", "м" -> MEGA
                "g", "г" -> GIGA
                "ki", "ки" -> KIBI
                "mi", "ми" -> MEBI
                "gi", "ги" -> GIBI
                else -> throw IllegalArgumentException("\"$text\": the unit \"$unit\" is not one this client knows")
            }
        return if (isBits) multiplier / BITS_PER_BYTE else multiplier
    }

    private val NO_LIMIT = setOf("", "0", "off", "none", "no", "no limit", "unlimited", "нет", "без ограничений")
    private val NUMBER_AND_UNIT = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(\S*)""")

    private const val BITS_PER_BYTE = 8
    private const val KILO = 1_000.0
    private const val MEGA = 1_000_000.0
    private const val GIGA = 1_000_000_000.0
    private const val KIBI = 1_024.0
    private const val MEBI = 1_048_576.0
    private const val GIBI = 1_073_741_824.0
    private const val TEN = 10.0
    private const val HUNDRED = 100.0
}
