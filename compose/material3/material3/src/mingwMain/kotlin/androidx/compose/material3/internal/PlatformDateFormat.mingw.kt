/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.windows.DATE_LONGDATE
import platform.windows.DWORDVar
import platform.windows.GetDateFormatEx
import platform.windows.GetLocaleInfoEx
import platform.windows.LOCALE_IFIRSTDAYOFWEEK
import platform.windows.LOCALE_NOUSEROVERRIDE
import platform.windows.LOCALE_RETURN_NUMBER
import platform.windows.LOCALE_SDAYNAME1
import platform.windows.LOCALE_SDAYNAME2
import platform.windows.LOCALE_SDAYNAME3
import platform.windows.LOCALE_SDAYNAME4
import platform.windows.LOCALE_SDAYNAME5
import platform.windows.LOCALE_SDAYNAME6
import platform.windows.LOCALE_SDAYNAME7
import platform.windows.LOCALE_SLONGDATE
import platform.windows.LOCALE_SSHORTDATE
import platform.windows.LOCALE_SSHORTESTDAYNAME1
import platform.windows.LOCALE_SSHORTESTDAYNAME2
import platform.windows.LOCALE_SSHORTESTDAYNAME3
import platform.windows.LOCALE_SSHORTESTDAYNAME4
import platform.windows.LOCALE_SSHORTESTDAYNAME5
import platform.windows.LOCALE_SSHORTESTDAYNAME6
import platform.windows.LOCALE_SSHORTESTDAYNAME7
import platform.windows.LOCALE_STIMEFORMAT
import platform.windows.LOCALE_SYEARMONTH
import platform.windows.SYSTEMTIME
import platform.windows.WCHARVar

/**
 * Date formatting through the Win32 NLS layer.
 *
 * Windows exposes a first-class API for every part of this — the first day of the week, the
 * localized day names, the short/long date and year-month pictures, and the time format — so
 * unlike the web target none of it has to be approximated from region tables. Only parsing has no
 * Win32 counterpart, and uses kotlinx-datetime the way the Apple and web targets do.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal actual class PlatformDateFormat actual constructor(private val locale: CalendarLocale) {

    /** A BCP47 tag is exactly what the NLS functions take as `lpLocaleName`. */
    private val localeName: String get() = locale.toLanguageTag()

    /**
     * `LOCALE_IFIRSTDAYOFWEEK` counts 0 = Monday through 6 = Sunday; the calendar model wants
     * 1 = Monday through 7 = Sunday.
     */
    actual val firstDayOfWeek: Int
        get() = (localeNumber(LOCALE_IFIRSTDAYOFWEEK)?.plus(1)) ?: 1

    /** Full and shortest day names, Monday first — the order NLS already uses. */
    actual val weekdayNames: List<Pair<String, String>>
        get() = List(DaysInWeek) { index ->
            val full = localeText(DayNameTypes[index]).orEmpty()
            val shortest = localeText(ShortestDayNameTypes[index]).orEmpty()
            full to shortest
        }

    actual fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        cache: MutableMap<String, Any>
    ): String {
        val picture = cache.getOrPut("P:$pattern") { pattern.asDatePicture() } as String
        return formatDate(utcTimeMillis, picture)
    }

    actual fun formatWithSkeleton(
        utcTimeMillis: Long,
        skeleton: String,
        cache: MutableMap<String, Any>
    ): String {
        return when {
            // Windows' "long date" is the full form, weekday included.
            skeleton.hasSameDateFieldsAs(DatePickerDefaults.YearMonthWeekdayDaySkeleton) ->
                formatDate(
                    utcTimeMillis,
                    flags = (DATE_LONGDATE or LOCALE_NOUSEROVERRIDE.toInt()).convert(),
                )

            // The same form without the weekday and with the month abbreviated.
            skeleton.hasSameDateFieldsAs(DatePickerDefaults.YearAbbrMonthDaySkeleton) -> {
                val picture = cache.getOrPut("S:$skeleton:$localeName") {
                    localeText(LOCALE_SLONGDATE)
                        ?.withoutWeekday()
                        ?.abbreviatingMonthName()
                        ?: return formatWithPattern(utcTimeMillis, skeleton, cache)
                } as String
                formatDate(utcTimeMillis, picture)
            }

            // Windows keeps a dedicated picture for a year-and-month heading.
            skeleton.hasSameDateFieldsAs(DatePickerDefaults.YearMonthSkeleton) -> {
                val picture = localeText(LOCALE_SYEARMONTH)
                    ?: return formatWithPattern(utcTimeMillis, skeleton, cache)
                formatDate(utcTimeMillis, picture)
            }

            // Any other skeleton is close enough to a pattern to be treated as one, which is
            // what the JVM desktop target does too.
            else -> formatWithPattern(utcTimeMillis, skeleton, cache)
        }
    }

    @OptIn(FormatStringsInDatetimeFormats::class)
    actual fun parse(
        date: String,
        pattern: String,
        locale: CalendarLocale,
        cache: MutableMap<String, Any>
    ): CalendarDate? {
        // Win32 has no date parsing counterpart to GetDateFormatEx.
        // TODO https://youtrack.jetbrains.com/issue/CMP-7146/Properly-use-locale-in-CalendarModel.parse-implementations
        return try {
            LocalDate
                .parse(input = date, format = LocalDate.Format { byUnicodePattern(pattern) })
                .atTime(Midnight)
                .toInstant(TimeZone.UTC)
                .toCalendarDate(TimeZone.UTC)
        } catch (e: Throwable) {
            null
        }
    }

    actual fun getDateInputFormat(): DateInputFormat {
        // Short-date pictures use the same d/M/y letters the shared parser expects.
        val pattern = localeText(LOCALE_SSHORTDATE) ?: "MM/dd/yyyy"
        return datePatternAsInputFormat(pattern)
    }

    /**
     * `LOCALE_STIMEFORMAT` spells the hour as `H` in 24-hour locales and `h` in 12-hour ones.
     */
    actual fun is24HourFormat(): Boolean {
        val timeFormat = localeText(LOCALE_STIMEFORMAT) ?: return false
        return timeFormat.outsideLiterals().any { it == 'H' }
    }

    private fun formatDate(utcTimeMillis: Long, picture: String): String =
        formatDate(utcTimeMillis, flags = 0u, picture = picture)

    private fun formatDate(utcTimeMillis: Long, flags: UInt): String =
        formatDate(utcTimeMillis, flags = flags, picture = null)

    private fun formatDate(utcTimeMillis: Long, flags: UInt, picture: String?): String = memScoped {
        val dateTime = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)
        val systemTime = alloc<SYSTEMTIME>()
        systemTime.wYear = dateTime.year.convert()
        systemTime.wMonth = dateTime.month.number.convert()
        systemTime.wDay = dateTime.day.convert()
        // SYSTEMTIME counts 0 = Sunday, ISO counts 1 = Monday through 7 = Sunday.
        systemTime.wDayOfWeek = (dateTime.dayOfWeek.isoDayNumber % DaysInWeek).convert()

        val needed = GetDateFormatEx(localeName, flags, systemTime.ptr, picture, null, 0, null)
        if (needed <= 0) return@memScoped ""
        val buffer = allocArray<WCHARVar>(needed)
        val written =
            GetDateFormatEx(localeName, flags, systemTime.ptr, picture, buffer, needed, null)
        if (written <= 0) "" else buffer.readUtf16(written - 1)
    }

    /** Reads a string locale property, sizing the buffer from the first call. */
    private fun localeText(lcType: Int): String? = memScoped {
        // A CalendarLocale describes the requested locale, not the user's Control Panel
        // overrides. Without LOCALE_NOUSEROVERRIDE, asking for en-US on a machine whose user
        // locale is en-US can unexpectedly return that user's custom date pictures instead of
        // the en-US ones.
        val type = lcType or LOCALE_NOUSEROVERRIDE.toInt()
        val needed = GetLocaleInfoEx(localeName, type.convert(), null, 0)
        if (needed <= 0) return@memScoped null
        val buffer = allocArray<WCHARVar>(needed)
        val written = GetLocaleInfoEx(localeName, type.convert(), buffer, needed)
        // The returned length counts the terminating NUL.
        if (written <= 1) null else buffer.readUtf16(written - 1)
    }

    private fun localeNumber(lcType: Int): Int? = memScoped {
        val value = alloc<DWORDVar>()
        val written =
            GetLocaleInfoEx(
                localeName,
                (lcType or LOCALE_RETURN_NUMBER or LOCALE_NOUSEROVERRIDE.toInt()).convert(),
                value.ptr.reinterpret(),
                // With LOCALE_RETURN_NUMBER the buffer receives a DWORD; cchData counts WCHARs.
                2,
            )
        if (written == 0) null else value.value.toInt()
    }
}

private fun CPointer<WCHARVar>.readUtf16(length: Int): String =
    buildString(length) {
        for (i in 0 until length) append(this@readUtf16[i].toInt().toChar())
    }

private val DayNameTypes = intArrayOf(
    LOCALE_SDAYNAME1, // Monday
    LOCALE_SDAYNAME2,
    LOCALE_SDAYNAME3,
    LOCALE_SDAYNAME4,
    LOCALE_SDAYNAME5,
    LOCALE_SDAYNAME6,
    LOCALE_SDAYNAME7, // Sunday
)

private val ShortestDayNameTypes = intArrayOf(
    LOCALE_SSHORTESTDAYNAME1,
    LOCALE_SSHORTESTDAYNAME2,
    LOCALE_SSHORTESTDAYNAME3,
    LOCALE_SSHORTESTDAYNAME4,
    LOCALE_SSHORTESTDAYNAME5,
    LOCALE_SSHORTESTDAYNAME6,
    LOCALE_SSHORTESTDAYNAME7,
)

/**
 * Rewrites a Unicode (ICU/CLDR) date pattern as the equivalent Win32 picture string.
 *
 * The two notations already agree on `d`, `M`, `y` and on `'` for literals, so only the fields
 * that Win32 spells differently need translating: the day of week is `ddd`/`dddd` rather than
 * `E`, there is no standalone month so `L` folds into `M`, and the era is a lowercase `g`.
 * Letters with no Win32 meaning are quoted so they survive as text rather than being read as
 * fields.
 */
private fun String.asDatePicture(): String {
    val picture = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char == '\'' -> {
                // A literal run; '' is an escaped quote in both notations.
                val end = indexOf('\'', startIndex = index + 1)
                if (end < 0) {
                    picture.append(substring(index))
                    index = length
                } else {
                    picture.append(substring(index, end + 1))
                    index = end + 1
                }
            }
            char.isLetter() -> {
                var end = index
                while (end < length && this[end] == char) end++
                picture.append(datePictureField(char, count = end - index))
                index = end
            }
            else -> {
                picture.append(char)
                index++
            }
        }
    }
    return picture.toString()
}

private fun datePictureField(field: Char, count: Int): String = when (field) {
    // Unicode 'y' is the unpadded year, 'yy' the two-digit one; Win32 spells those yyyy and yy.
    'y', 'u' -> if (count == 2) "yy" else "yyyy"
    'M' -> "M".repeat(count.coerceAtMost(4))
    // Win32 has no standalone month form.
    'L' -> "M".repeat(count.coerceAtMost(4))
    'd' -> "d".repeat(count.coerceAtMost(2))
    // Day of week: Win32 uses the d-run lengths above 2 for it.
    'E', 'e', 'c' -> if (count >= 4) "dddd" else "ddd"
    'G' -> "g".repeat(count.coerceAtMost(2))
    // Not a Win32 date field: keep it as text.
    else -> "'" + field.toString().repeat(count) + "'"
}

/**
 * Drops the `ddd`/`dddd` element and any separator left stranded next to it — a long-date picture
 * such as `dddd, MMMM d, yyyy` or `dddd d MMMM yyyy` becomes the same date without the weekday.
 */
private fun String.withoutWeekday(): String =
    replace(Regex("d{3,4}"), "")
        .replace(Regex("^[\\s,،、.\\-/]+"), "")
        .replace(Regex("[\\s,،、.\\-/]+$"), "")
        .trim()

/** Turns a full month name element into the abbreviated one. */
private fun String.abbreviatingMonthName(): String = replace("MMMM", "MMM")

/**
 * ICU skeleton field order is insignificant: `yMMMd` and `dMMMy` request the same localized
 * fields. NLS exposes locale date pictures rather than an ICU best-pattern generator, so matching
 * the supported date-picker skeletons by their field multiset preserves that contract before the
 * corresponding Windows picture is selected.
 */
private fun String.hasSameDateFieldsAs(reference: String): Boolean =
    skeletonFieldCounts()?.let { it == reference.skeletonFieldCounts() } == true

private fun String.skeletonFieldCounts(): Map<Char, Int>? {
    val counts = mutableMapOf<Char, Int>()
    for (field in this) {
        if (!field.isLetter()) return null
        val canonicalField = when (field) {
            'u', 'Y' -> 'y'
            'L' -> 'M'
            'e', 'c' -> 'E'
            else -> field
        }
        counts[canonicalField] = counts.getOrElse(canonicalField) { 0 } + 1
    }
    return counts
}

/** The characters outside `'`-quoted literals, so field letters can be told from text. */
private fun String.outsideLiterals(): Sequence<Char> = sequence {
    var inLiteral = false
    for (char in this@outsideLiterals) {
        if (char == '\'') {
            inLiteral = !inLiteral
        } else if (!inLiteral) {
            yield(char)
        }
    }
}
