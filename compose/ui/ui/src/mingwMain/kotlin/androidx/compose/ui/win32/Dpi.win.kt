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

package androidx.compose.ui.win32

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import platform.windows.GetDC
import platform.windows.GetDeviceCaps
import platform.windows.GetProcAddress
import platform.windows.HWND
import platform.windows.LOGPIXELSY
import platform.windows.LoadLibraryW
import platform.windows.ReleaseDC
import platform.windows.SetProcessDPIAware

/**
 * The DPI entry points added in Windows 10, resolved at run time.
 *
 * `GetDpiForWindow` and `SetProcessDpiAwarenessContext` are not in the Kotlin/Native Windows klib,
 * whose headers predate them, so they are looked up in `user32.dll` the way a Windows application
 * normally adopts a newer API while still running on older systems. Everything degrades to the
 * system-wide DPI when the lookup fails.
 */
private object Win32Dpi {
    private const val USER32 = "user32.dll"

    /** `DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2`, a sentinel handle rather than a real pointer. */
    private const val PER_MONITOR_AWARE_V2 = -4L

    private val user32 by lazy { LoadLibraryW(USER32) }

    private val getDpiForWindow: CPointer<CFunction<(HWND?) -> UInt>>? by lazy {
        user32?.let { GetProcAddress(it, "GetDpiForWindow") }?.reinterpret()
    }

    private val setProcessDpiAwarenessContext:
        CPointer<CFunction<(Long) -> Int>>? by lazy {
        user32?.let { GetProcAddress(it, "SetProcessDpiAwarenessContext") }?.reinterpret()
    }

    /**
     * Opts the process into per-monitor-v2 awareness, so Windows reports real pixels on every
     * monitor and sends `WM_DPICHANGED` when a window moves between them. Falls back to
     * system-wide awareness on Windows versions without it.
     *
     * Must run before the first window is created.
     */
    fun markProcessDpiAware() {
        val setContext = setProcessDpiAwarenessContext
        if (setContext != null && setContext(PER_MONITOR_AWARE_V2) != 0) return
        SetProcessDPIAware()
    }

    /** The DPI of the monitor [hwnd] is on, or the system DPI if per-window DPI is unavailable. */
    fun dpiFor(hwnd: HWND?): Int {
        if (hwnd != null) {
            val perWindow = getDpiForWindow?.invoke(hwnd)?.toInt() ?: 0
            if (perWindow > 0) return perWindow
        }
        return systemDpi()
    }

    /** System-wide DPI, read from the desktop device context. */
    fun systemDpi(): Int {
        val screen = GetDC(null) ?: return DEFAULT_DPI
        val dpi = GetDeviceCaps(screen, LOGPIXELSY)
        ReleaseDC(null, screen)
        return if (dpi > 0) dpi else DEFAULT_DPI
    }
}

/** Windows' reference DPI: a scale factor of 1.0. */
internal const val DEFAULT_DPI = 96

/** @see Win32Dpi.markProcessDpiAware */
internal fun markProcessDpiAware() = Win32Dpi.markProcessDpiAware()

/** Scale factor for the monitor [hwnd] is on: 96 DPI is 1.0. */
internal fun windowScale(hwnd: HWND?): Float = Win32Dpi.dpiFor(hwnd).toFloat() / DEFAULT_DPI

/** Scale factor of the primary monitor, for sizing a window before it exists. */
internal fun systemScale(): Float = Win32Dpi.systemDpi().toFloat() / DEFAULT_DPI
