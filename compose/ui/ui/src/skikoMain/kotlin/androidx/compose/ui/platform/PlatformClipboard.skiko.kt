/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.compose.ui.platform

/**
 * Describes what a [ClipEntry] holds without reading it.
 *
 * The point of the type is to let a paste target decide whether it can accept the clipboard
 * before touching the contents, which on some platforms is what raises a permission prompt or a
 * "pasted from" notification. It therefore carries only MIME types, never data.
 *
 * The constructor is internal because metadata always describes an entry the platform layer
 * produced; application code reads it from [ClipEntry.clipMetadata].
 *
 * See https://youtrack.jetbrains.com/issue/CMP-1260.
 */
actual class ClipMetadata internal constructor(
    /** MIME types the entry can be read as, most specific first. */
    val mimeTypes: List<String>,
) {
    /**
     * Whether the entry can be read as [mimeType]. Either half of the type may be the wildcard
     * `*`, so a pattern matching every subtype of text, or every type at all, works the same way
     * as in Android's `ClipDescription.hasMimeType`.
     */
    fun hasMimeType(mimeType: String): Boolean =
        mimeTypes.any { available -> matches(pattern = mimeType, available = available) }

    override fun toString(): String = "ClipMetadata(mimeTypes=$mimeTypes)"

    private fun matches(pattern: String, available: String): Boolean {
        if (pattern == available) return true
        val patternSlash = pattern.indexOf('/')
        if (patternSlash < 0) return false
        val patternType = pattern.substring(0, patternSlash)
        val patternSubtype = pattern.substring(patternSlash + 1)
        if (patternType == "*" && patternSubtype == "*") return true

        val availableSlash = available.indexOf('/')
        if (availableSlash < 0) return false
        val availableType = available.substring(0, availableSlash)
        val availableSubtype = available.substring(availableSlash + 1)

        val typeMatches = patternType == "*" || patternType == availableType
        val subtypeMatches = patternSubtype == "*" || patternSubtype == availableSubtype
        return typeMatches && subtypeMatches
    }
}

/** Metadata for an entry that holds nothing. */
internal fun emptyClipMetadata(): ClipMetadata = ClipMetadata(emptyList())

/** Metadata for an entry that holds Unicode text. */
internal fun plainTextClipMetadata(): ClipMetadata = ClipMetadata(listOf(MimeTypeText))

/** Metadata for an entry that holds file paths. */
internal fun fileListClipMetadata(): ClipMetadata = ClipMetadata(listOf(MimeTypeFileList))

internal const val MimeTypeText = "text/plain"
internal const val MimeTypeFileList = "application/x-file-list"

@Suppress("DEPRECATION")
internal expect fun createPlatformClipboardManager(): ClipboardManager

internal expect fun createPlatformClipboard(): Clipboard
