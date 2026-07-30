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

@file:OptIn(ExperimentalForeignApi::class)

package androidx.compose.ui.text.intl

import androidx.compose.runtime.Immutable
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
import platform.windows.DWORDVar
import platform.windows.GetLocaleInfoEx
import platform.windows.GetUserDefaultLocaleName
import platform.windows.GetUserPreferredUILanguages
import platform.windows.LOCALE_IREADINGLAYOUT
import platform.windows.LOCALE_NAME_MAX_LENGTH
import platform.windows.LOCALE_RETURN_NUMBER
import platform.windows.MUI_LANGUAGE_NAME
import platform.windows.ULONGVar
import platform.windows.WCHARVar

private const val DEFAULT_LANGUAGE_TAG = "en-US"

/**
 * Win32 locale names use BCP47 syntax ("en-US", "sr-Cyrl-RS"), so the language tag doubles as the
 * platform locale identifier and no ICU/NLS handle needs to be kept alive.
 */
@Immutable
actual class Locale actual constructor(languageTag: String) {
    private val subtags: List<String> = canonicalSubtags(languageTag)

    /** BCP47 tag in canonical casing; also a valid `lpLocaleName` for the NLS APIs. */
    internal val localeName: String = subtags.joinToString("-")

    actual val language: String
        get() = subtags.firstOrNull().orEmpty()

    actual val script: String
        get() = subtags.getOrNull(1)?.takeIf { it.isScriptSubtag() }.orEmpty()

    actual val region: String
        get() = subtags.drop(1).firstOrNull { it.isRegionSubtag() }.orEmpty()

    actual fun toLanguageTag(): String = localeName

    actual override operator fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is Locale) return false
        if (this === other) return true
        return localeName == other.localeName
    }

    actual override fun hashCode(): Int = localeName.hashCode()

    actual override fun toString(): String = localeName

    actual companion object {
        actual val current: Locale
            get() = platformLocaleDelegate.current[0]
    }
}

internal actual fun createPlatformLocaleDelegate(): PlatformLocaleDelegate =
    object : PlatformLocaleDelegate {
        override val current: LocaleList
            get() = LocaleList(preferredLanguageTags().map { Locale(it) })
    }

internal actual fun Locale.isRtl(): Boolean = readingLayout(localeName) == READING_LAYOUT_RTL

/** `LOCALE_IREADINGLAYOUT`: 0 = LTR, 1 = RTL, 2 = TTB+RTL columns, 3 = TTB+LTR columns. */
private const val READING_LAYOUT_RTL = 1

private fun readingLayout(localeName: String): Int = memScoped {
    if (localeName.isEmpty()) return@memScoped 0
    val value = alloc<DWORDVar>()
    // With LOCALE_RETURN_NUMBER the LPWSTR buffer receives a DWORD; cchData counts WCHARs.
    val read =
        GetLocaleInfoEx(
            localeName,
            (LOCALE_IREADINGLAYOUT or LOCALE_RETURN_NUMBER).convert(),
            value.ptr.reinterpret(),
            2,
        )
    if (read == 0) 0 else value.value.toInt()
}

/**
 * The user's UI language preference list, most-preferred first — the Win32 analog of
 * `NSLocale.preferredLanguages`. Falls back to the default user locale, then to [en-US], so the
 * list is never empty (`Locale.current` indexes into it).
 */
private fun preferredLanguageTags(): List<String> {
    val preferred = userPreferredUILanguages()
    if (preferred.isNotEmpty()) return preferred
    val default = userDefaultLocaleName()
    return if (default != null) listOf(default) else listOf(DEFAULT_LANGUAGE_TAG)
}

private fun userPreferredUILanguages(): List<String> = memScoped {
    val count = alloc<ULONGVar>()
    val chars = alloc<ULONGVar>()
    val flags = MUI_LANGUAGE_NAME.convert<UInt>()
    if (GetUserPreferredUILanguages(flags, count.ptr, null, chars.ptr) == 0) {
        return@memScoped emptyList()
    }
    val size = chars.value.toInt()
    if (size <= 0) return@memScoped emptyList()
    val buffer = allocArray<WCHARVar>(size)
    if (GetUserPreferredUILanguages(flags, count.ptr, buffer, chars.ptr) == 0) {
        return@memScoped emptyList()
    }
    // Double-NUL-terminated sequence of NUL-terminated names.
    val names = ArrayList<String>(count.value.toInt())
    var start = 0
    while (start < size && buffer[start] != 0.toUShort()) {
        var end = start
        while (end < size && buffer[end] != 0.toUShort()) end++
        names.add(buffer.readUtf16(start, end))
        start = end + 1
    }
    names
}

private fun userDefaultLocaleName(): String? = memScoped {
    val buffer = allocArray<WCHARVar>(LOCALE_NAME_MAX_LENGTH)
    val length = GetUserDefaultLocaleName(buffer, LOCALE_NAME_MAX_LENGTH)
    // The returned length counts the terminating NUL.
    if (length <= 1) null else buffer.readUtf16(0, length - 1)
}

private fun CPointer<WCHARVar>.readUtf16(start: Int, end: Int): String =
    buildString(end - start) {
        for (i in start until end) append(this@readUtf16[i].toInt().toChar())
    }

/**
 * Splits a language tag into subtags and applies BCP47 canonical casing: lowercase language,
 * titlecase script, uppercase region. Accepts `_` as a separator so JVM-style tags ("en_US") parse.
 */
private fun canonicalSubtags(languageTag: String): List<String> {
    val parts = languageTag.split('-', '_').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return listOf("")
    val canonical = ArrayList<String>(parts.size)
    canonical.add(parts[0].lowercase())
    var scriptSeen = false
    var regionSeen = false
    for (i in 1 until parts.size) {
        val part = parts[i]
        when {
            !scriptSeen && !regionSeen && part.isScriptSubtag() -> {
                canonical.add(part.lowercase().replaceFirstChar { it.uppercaseChar() })
                scriptSeen = true
            }
            !regionSeen && part.isRegionSubtag() -> {
                canonical.add(part.uppercase())
                regionSeen = true
            }
            else -> canonical.add(part.lowercase())
        }
    }
    return canonical
}

private fun String.isScriptSubtag(): Boolean = length == 4 && all { it.isLetter() }

private fun String.isRegionSubtag(): Boolean =
    (length == 2 && all { it.isLetter() }) || (length == 3 && all { it.isDigit() })
