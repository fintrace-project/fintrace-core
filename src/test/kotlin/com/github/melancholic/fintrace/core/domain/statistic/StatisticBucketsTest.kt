package com.github.melancholic.fintrace.core.domain.statistic

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The calendar arithmetic behind every series (§11.4): no Spring, no database. */
class StatisticBucketsTest {

    @Test
    fun `a week is widened to Monday through Sunday`() {
        val buckets = StatisticBuckets.splitByBuckets(Granularity.WEEK, date("2026-11-11"), date("2026-11-15"))

        assertEquals(date("2026-11-09"), buckets.firstStart)
        assertEquals(date("2026-11-09"), buckets.lastStart)
        assertEquals(date("2026-11-15"), buckets.end)
        assertEquals(1, buckets.count)
    }

    @Test
    fun `Sunday and the Monday after are two weeks`() {
        val buckets = StatisticBuckets.splitByBuckets(Granularity.WEEK, date("2026-11-15"), date("2026-11-16"))

        assertEquals(date("2026-11-09"), buckets.firstStart)
        assertEquals(date("2026-11-16"), buckets.lastStart)
        assertEquals(2, buckets.count)
    }

    @Test
    fun `months across New Year`() {
        val buckets = StatisticBuckets.splitByBuckets(Granularity.MONTH, date("2025-12-31"), date("2026-01-01"))

        assertEquals(date("2025-12-01"), buckets.firstStart)
        assertEquals(date("2026-01-01"), buckets.lastStart)
        assertEquals(date("2026-01-31"), buckets.end)
        assertEquals(2, buckets.count)
    }

    @Test
    fun `a leap February ends on the 29th`() {
        val buckets = StatisticBuckets.splitByBuckets(Granularity.MONTH, date("2028-02-10"), date("2028-02-10"))

        assertEquals(date("2028-02-29"), buckets.end)
    }

    @Test
    fun `years are widened to whole calendar years`() {
        val buckets = StatisticBuckets.splitByBuckets(Granularity.YEAR, date("2021-03-05"), date("2026-11-15"))

        assertEquals(date("2021-01-01"), buckets.firstStart)
        assertEquals(date("2026-01-01"), buckets.lastStart)
        assertEquals(date("2026-12-31"), buckets.end)
        assertEquals(6, buckets.count)
    }

    @Test
    fun `one day is one bucket`() {
        val day = date("2026-11-15")
        val buckets = StatisticBuckets.splitByBuckets(Granularity.DAY, day, day)

        assertEquals(day, buckets.firstStart)
        assertEquals(day, buckets.end)
        assertEquals(1, buckets.count)
    }

    @Test
    fun `the count is inclusive of both ends, at the cap's edge`() {
        val from = date("2020-01-01")

        assertEquals(2000, StatisticBuckets.splitByBuckets(Granularity.DAY, from, from.plusDays(1999)).count)
        assertEquals(2001, StatisticBuckets.splitByBuckets(Granularity.DAY, from, from.plusDays(2000)).count)
    }

    @Test
    fun `the buckets carry the granularity that cut them`() {
        assertEquals(
            Granularity.MONTH,
            StatisticBuckets.splitByBuckets(Granularity.MONTH, date("2026-01-01"), date("2026-02-01")).granularity,
        )
    }

    @Test
    fun `refuses bounds the wrong way round, a caller's bug rather than a request's`() {
        assertFailsWith<IllegalArgumentException> {
            StatisticBuckets.splitByBuckets(Granularity.DAY, date("2026-11-16"), date("2026-11-15"))
        }
    }

    private fun date(iso: String) = LocalDate.parse(iso)
}
