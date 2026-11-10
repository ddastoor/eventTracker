package io.github.ddastoor.eventtracker.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How long each point on the frequency chart covers. */
sealed interface Period {
    /** [n] calendar days per point. With n > 1 the last point ends on the range's last day. */
    data class Days(val n: Int) : Period

    /** Calendar weeks, Monday to Sunday. */
    data object Week : Period

    /** [n] calendar months per point, aligned to January (so 6 = half years, 12 = years). */
    data class Months(val n: Int) : Period

    companion object {
        val DAY = Days(1)
        val MONTH = Months(1)
        val HALF_YEAR = Months(6)
        val YEAR = Months(12)
    }
}

data class Bucket(val start: LocalDate, val endExclusive: LocalDate) {
    val endInclusive: LocalDate get() = endExclusive.minusDays(1)
}

data class Series(val key: String, val counts: List<Int>)

data class FrequencyData(
    val buckets: List<Bucket>,
    val series: List<Series>,
    /** Short axis labels, one per bucket. */
    val labels: List<String>,
) {
    val maxCount: Int get() = series.maxOfOrNull { s -> s.counts.maxOrNull() ?: 0 } ?: 0
}

data class Summary(
    val total: Int,
    val averagePerPeriod: Double,
    val busiestPeriodCount: Int,
    /** Average number of days between consecutive events in the range, or null if fewer than 2. */
    val averageGapDays: Double?,
    /** Epoch seconds of the most recent event (in any time), or null. */
    val lastEvent: Long?,
)

object Stats {
    const val MAX_BUCKETS = 5000

    /** Splits [from]..[to] (inclusive) into whole periods; the edges are widened to full periods. */
    fun buckets(from: LocalDate, to: LocalDate, period: Period): List<Bucket> {
        require(!to.isBefore(from)) { "Range end is before its start" }
        val result = ArrayList<Bucket>()
        when (period) {
            is Period.Days -> {
                val n = period.n.coerceAtLeast(1).toLong()
                var end = to.plusDays(1)
                while (end.isAfter(from)) {
                    result += Bucket(end.minusDays(n), end)
                    end = end.minusDays(n)
                    check(result.size <= MAX_BUCKETS) { TOO_MANY }
                }
                result.reverse()
            }
            Period.Week -> {
                var start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                while (!start.isAfter(to)) {
                    result += Bucket(start, start.plusWeeks(1))
                    start = start.plusWeeks(1)
                    check(result.size <= MAX_BUCKETS) { TOO_MANY }
                }
            }
            is Period.Months -> {
                val n = period.n.coerceAtLeast(1)
                val first = monthIndex(from) / n * n
                var start = monthStart(first)
                while (!start.isAfter(to)) {
                    result += Bucket(start, start.plusMonths(n.toLong()))
                    start = start.plusMonths(n.toLong())
                    check(result.size <= MAX_BUCKETS) { TOO_MANY }
                }
            }
        }
        return result
    }

    /**
     * Counts events per bucket. [timestampsByKey] maps a series key (an event type id) to the epoch
     * second timestamps of its events.
     */
    fun frequency(
        timestampsByKey: Map<String, List<Long>>,
        from: LocalDate,
        to: LocalDate,
        period: Period,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): FrequencyData {
        val buckets = buckets(from, to, period)
        val starts = buckets.map { it.start }
        val series = timestampsByKey.map { (key, stamps) ->
            val counts = IntArray(buckets.size)
            stamps.forEach { ts ->
                val i = indexOf(starts, buckets, toDate(ts, zone))
                if (i >= 0) counts[i]++
            }
            Series(key, counts.toList())
        }
        return FrequencyData(buckets, series, buckets.map { shortLabel(it, period, buckets, locale) })
    }

    fun summary(timestamps: List<Long>, data: FrequencyData, zone: ZoneId): Summary {
        val first = data.buckets.firstOrNull()?.start
        val end = data.buckets.lastOrNull()?.endExclusive
        val inRange = if (first == null || end == null) emptyList() else timestamps.filter {
            val d = toDate(it, zone)
            !d.isBefore(first) && d.isBefore(end)
        }.sorted()
        val totals = data.buckets.indices.map { i -> data.series.sumOf { it.counts[i] } }
        val gap = if (inRange.size < 2) null else (inRange.last() - inRange.first()) / 86400.0 / (inRange.size - 1)
        return Summary(
            total = inRange.size,
            averagePerPeriod = if (totals.isEmpty()) 0.0 else totals.sum().toDouble() / totals.size,
            busiestPeriodCount = totals.maxOrNull() ?: 0,
            averageGapDays = gap,
            lastEvent = timestamps.maxOrNull(),
        )
    }

    fun toDate(epochSeconds: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

    fun longLabel(bucket: Bucket, locale: Locale = Locale.getDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
        return if (bucket.start == bucket.endInclusive) fmt.format(bucket.start)
        else "${fmt.format(bucket.start)} – ${fmt.format(bucket.endInclusive)}"
    }

    fun periodName(period: Period): String = when (period) {
        Period.DAY -> "day"
        is Period.Days -> "${period.n} days"
        Period.Week -> "week"
        Period.MONTH -> "month"
        Period.HALF_YEAR -> "6 months"
        Period.YEAR -> "year"
        is Period.Months -> "${period.n} months"
    }

    private fun shortLabel(b: Bucket, period: Period, all: List<Bucket>, locale: Locale): String {
        val multiYear = all.isNotEmpty() && all.first().start.year != all.last().endInclusive.year
        return when (period) {
            is Period.Days, Period.Week ->
                DateTimeFormatter.ofPattern(if (multiYear) "MMM d ''yy" else "MMM d", locale).format(b.start)
            is Period.Months -> when (period.n) {
                12 -> b.start.year.toString()
                6 -> "H${(b.start.monthValue - 1) / 6 + 1} '${twoDigitYear(b.start)}"
                3 -> "Q${(b.start.monthValue - 1) / 3 + 1} '${twoDigitYear(b.start)}"
                else -> DateTimeFormatter.ofPattern("MMM ''yy", locale).format(b.start)
            }
        }
    }

    private fun twoDigitYear(d: LocalDate) = "%02d".format(d.year % 100)

    private fun indexOf(starts: List<LocalDate>, buckets: List<Bucket>, d: LocalDate): Int {
        var i = starts.binarySearch(d)
        if (i < 0) i = -i - 2
        if (i < 0 || i >= buckets.size) return -1
        return if (d.isBefore(buckets[i].endExclusive)) i else -1
    }

    private fun monthIndex(d: LocalDate) = d.year * 12 + (d.monthValue - 1)

    private fun monthStart(index: Int): LocalDate = YearMonth.of(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1).atDay(1)

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

    private const val TOO_MANY = "Too many points for this range. Choose a longer period."
}
