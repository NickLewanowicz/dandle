package net.babybuddy.next.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ISO-8601 wire format helpers (research doc §1: datetimes are ISO 8601 with offset; input
 * accepts `Z`, explicit offsets, or naive local). We send UTC second-precision — same choice
 * as the original client ("always UTC ISO without millis", research doc §2.3).
 */
object Iso8601 {

    fun format(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Parses any server shape ("…-04:00", "…Z", microsecond precision) to epoch millis. */
    fun parse(value: String): Long =
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
}
