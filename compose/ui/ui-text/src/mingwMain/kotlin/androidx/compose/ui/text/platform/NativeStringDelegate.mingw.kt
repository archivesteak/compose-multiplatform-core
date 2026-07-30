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

package androidx.compose.ui.text.platform

import androidx.compose.ui.text.PlatformStringDelegate
import androidx.compose.ui.text.intl.Locale
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.windows.LCMAP_LOWERCASE
import platform.windows.LCMAP_UPPERCASE
import platform.windows.LCMapStringEx
import platform.windows.WCHARVar

/**
 * Locale-aware casing through the Win32 NLS layer, so Turkish dotless-i and the other
 * language-specific mappings behave the way the OS does. Falls back to Kotlin's locale-invariant
 * casing if NLS rejects the locale name.
 */
internal class Win32StringDelegate : PlatformStringDelegate {
    override fun toUpperCase(string: String, locale: Locale): String =
        mapString(string, locale, LCMAP_UPPERCASE) ?: string.uppercase()

    override fun toLowerCase(string: String, locale: Locale): String =
        mapString(string, locale, LCMAP_LOWERCASE) ?: string.lowercase()

    override fun capitalize(string: String, locale: Locale): String =
        string.replaceFirstChar {
            if (it.isLowerCase()) toUpperCase(it.toString(), locale) else it.toString()
        }

    override fun decapitalize(string: String, locale: Locale): String =
        string.replaceFirstChar { toLowerCase(it.toString(), locale) }
}

/** Returns null when the mapping fails, so callers can fall back. */
private fun mapString(source: String, locale: Locale, flags: Int): String? {
    if (source.isEmpty()) return source
    val localeName = locale.toLanguageTag().ifEmpty { null }
    return memScoped {
        // `LOCALE_NAME_USER_DEFAULT` is NULL, which is also the right fallback for an empty tag.
        val mapFlags = flags.convert<UInt>()
        val needed =
            LCMapStringEx(localeName, mapFlags, source, source.length, null, 0, null, null, 0)
        if (needed <= 0) return@memScoped null
        val dest = allocArray<WCHARVar>(needed)
        val written =
            LCMapStringEx(localeName, mapFlags, source, source.length, dest, needed, null, null, 0)
        if (written <= 0) return@memScoped null
        // An explicit cchSrc means the result is not NUL-terminated.
        buildString(written) { for (i in 0 until written) append(dest[i].toInt().toChar()) }
    }
}

internal actual fun ActualStringDelegate(): PlatformStringDelegate = Win32StringDelegate()
