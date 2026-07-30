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

package androidx.compose.foundation.gestures

import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastFold
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.SPI_GETWHEELSCROLLLINES
import platform.windows.SystemParametersInfoW
import platform.windows.UINTVar
import platform.windows.WHEEL_PAGESCROLL

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    WindowsScrollConfig

/**
 * Mirrors the WinUI feel that the JVM desktop target implements, with one difference: the number
 * of lines a notch scrolls is not baked into the event here, so it is read from the system setting
 * the same way `MouseWheelEvent.scrollAmount` provides it on the JVM.
 */
private object WindowsScrollConfig : ScrollConfig {
    // Free-scrolling wheels still animate on Windows, matching the JVM desktop behaviour.
    override fun isPreciseWheelScroll(event: PointerEvent): Boolean = false

    // The formula was determined experimentally based on Windows Start behaviour.
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        val delta = event.totalScrollDelta
        val linesPerNotch = wheelScrollLines()
        return if (linesPerNotch == WHEEL_PAGESCROLL) {
            // The user asked for a page per notch.
            Offset(x = delta.x * bounds.width, y = delta.y * bounds.height) * -1f
        } else {
            Offset(x = delta.x * (bounds.width / 20f), y = delta.y * (bounds.height / 20f)) *
                -linesPerNotch.toFloat()
        }
    }
}

/**
 * `SPI_GETWHEELSCROLLLINES`: how many lines one wheel notch scrolls, or [WHEEL_PAGESCROLL] when
 * the user chose to scroll a page at a time. Read per event so a settings change takes effect
 * without restarting; the value is served from the system's cache.
 */
private fun wheelScrollLines(): UInt = memScoped {
    val lines = alloc<UINTVar>()
    val read = SystemParametersInfoW(SPI_GETWHEELSCROLLLINES.convert(), 0u, lines.ptr, 0u)
    if (read == 0) DEFAULT_WHEEL_SCROLL_LINES else lines.value
}

/** The Windows default, used when the setting cannot be read. */
private const val DEFAULT_WHEEL_SCROLL_LINES = 3u

private val PointerEvent.totalScrollDelta
    get() = changes.fastFold(Offset.Zero) { acc, change -> acc + change.scrollDelta }

internal actual fun platformScrollableDefaultFlingBehavior(): ScrollableDefaultFlingBehavior =
    DefaultFlingBehavior(
        SplineBasedFloatDecayAnimationSpec(UnityDensity).generateDecayAnimationSpec()
    )

@Composable
internal actual fun rememberPlatformDefaultFlingBehavior(): FlingBehavior {
    val flingSpec = rememberSplineBasedDecay<Float>()
    return remember(flingSpec) {
        DefaultFlingBehavior(flingSpec)
    }
}
