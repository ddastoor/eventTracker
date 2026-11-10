package io.github.ddastoor.eventtracker.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class StatsTest {
    private val utc = ZoneOffset.UTC
    private fun d(s: String) = LocalDate.parse(s)
    private fun ts(s: String) = d(s).atTime(12, 0).toEpochSecond(utc)

    @Test
    fun `days buckets cover each day`() {
        val b = Stats.buckets(d("2025-03-01"), d("2025-03-03"), Period.DAY)
        assertEquals(listOf(d("2025-03-01"), d("2025-03-02"), d("2025-03-03")), b.map { it.start })
        assertEquals(d("2025-03-04"), b.last().endExclusive)
    }

    @Test
    fun `weeks start on Monday`() {
        // 2025-03-05 is a Wednesday.
        val b = Stats.buckets(d("2025-03-05"), d("2025-03-17"), Period.Week)
        assertEquals(listOf(d("2025-03-03"), d("2025-03-10"), d("2025-03-17")), b.map { it.start })
    }

    @Test
    fun `month, half-year and year buckets are calendar aligned`() {
        assertEquals(
            listOf(d("2025-01-01"), d("2025-02-01"), d("2025-03-01")),
            Stats.buckets(d("2025-01-15"), d("2025-03-02"), Period.MONTH).map { it.start },
        )
        assertEquals(
            listOf(d("2025-01-01"), d("2025-07-01"), d("2026-01-01")),
            Stats.buckets(d("2025-03-01"), d("2026-02-01"), Period.HALF_YEAR).map { it.start },
        )
        assertEquals(
            listOf(d("2024-01-01"), d("2025-01-01")),
            Stats.buckets(d("2024-05-01"), d("2025-02-01"), Period.YEAR).map { it.start },
        )
    }

    @Test
    fun `custom day periods end on the last day of the range`() {
        val b = Stats.buckets(d("2025-01-15"), d("2025-01-31"), Period.Days(10))
        assertEquals(listOf(d("2025-01-12"), d("2025-01-22")), b.map { it.start })
        assertEquals(d("2025-02-01"), b.last().endExclusive)
    }

    @Test
    fun `custom month periods`() {
        val b = Stats.buckets(d("2025-02-10"), d("2025-12-31"), Period.Months(4))
        assertEquals(listOf(d("2025-01-01"), d("2025-05-01"), d("2025-09-01")), b.map { it.start })
    }

    @Test
    fun `frequency counts events per period and series`() {
        val data = Stats.frequency(
            mapOf(
                "haircut" to listOf(ts("2025-01-03"), ts("2025-01-28"), ts("2025-03-09"), ts("2024-12-31")),
                "shave" to listOf(ts("2025-02-14")),
            ),
            d("2025-01-01"), d("2025-03-31"), Period.MONTH, utc, Locale.US,
        )
        assertEquals(listOf(2, 0, 1), data.series[0].counts)
        assertEquals(listOf(0, 1, 0), data.series[1].counts)
        assertEquals(listOf("Jan '25", "Feb '25", "Mar '25"), data.labels)
        assertEquals(2, data.maxCount)
    }

    @Test
    fun `labels for half years and years`() {
        val half = Stats.frequency(mapOf("a" to emptyList()), d("2025-01-01"), d("2025-12-31"), Period.HALF_YEAR, utc, Locale.US)
        assertEquals(listOf("H1 '25", "H2 '25"), half.labels)
        val year = Stats.frequency(mapOf("a" to emptyList()), d("2023-01-01"), d("2024-12-31"), Period.YEAR, utc, Locale.US)
        assertEquals(listOf("2023", "2024"), year.labels)
    }

    @Test
    fun `summary averages and gaps`() {
        val stamps = listOf(ts("2025-01-01"), ts("2025-01-11"), ts("2025-01-31"), ts("2024-06-01"))
        val data = Stats.frequency(mapOf("a" to stamps), d("2025-01-01"), d("2025-02-28"), Period.MONTH, utc)
        val s = Stats.summary(stamps, data, utc)
        assertEquals(3, s.total)
        assertEquals(1.5, s.averagePerPeriod, 1e-9)
        assertEquals(3, s.busiestPeriodCount)
        assertEquals(15.0, s.averageGapDays!!, 1e-9)
        assertEquals(ts("2025-01-31"), s.lastEvent)
        assertNull(Stats.summary(emptyList(), data, utc).averageGapDays)
    }

    @Test(expected = IllegalStateException::class)
    fun `refuses absurd numbers of points`() {
        Stats.buckets(d("1900-01-01"), d("2025-01-01"), Period.DAY)
    }
}
