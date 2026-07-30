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

package androidx.compose.ui.viewinterop

import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.HWND
import platform.windows.HWND_TOP
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOSIZE
import platform.windows.SetWindowPos

/**
 * Hosts native child windows inside a Compose window.
 *
 * Compose draws into one `HWND` through a software surface, and a child `HWND` is composited by
 * the window manager *above* whatever we paint. So an interop child always sits in front of the
 * Compose content, and z-order among several children is maintained through `SetWindowPos` in the
 * order Compose placed them. That is the same trade-off the JVM desktop backend makes with heavy
 * AWT components.
 *
 * Updates are applied straight away rather than queued: Compose layout and the Win32 message loop
 * run on the same thread here, so there is no frame to synchronise against.
 */
internal class Win32InteropContainer(
    /** The Compose window that owns the children. */
    val window: HWND,
) : InteropContainer {
    override val root: InteropViewGroup get() = window

    override var rootModifier: TrackInteropPlacementModifierNode? = null
    override val snapshotObserver = SnapshotStateObserver { command -> command() }

    private val holders = mutableListOf<InteropViewHolder>()

    fun startObserving() = snapshotObserver.start()

    fun stopObserving() {
        snapshotObserver.stop()
        snapshotObserver.clear()
    }

    override fun contains(holder: InteropViewHolder): Boolean = holder in holders

    override fun holderOfView(view: InteropView): InteropViewHolder? =
        holders.firstOrNull { it.interopView == view }

    override fun place(holder: InteropViewHolder) {
        val index = countInteropComponentsBelow(holder)
        if (holder in holders) {
            holder.changeInteropViewIndex(root, index)
        } else {
            holders.add(index.coerceAtMost(holders.size), holder)
            holder.insertInteropView(root, index)
        }
        applyZOrder()
    }

    override fun unplace(holder: InteropViewHolder) {
        holders.remove(holder)
        holder.removeInteropView(root)
    }

    override fun scheduleUpdate(action: () -> Unit) {
        // Layout and the message loop share a thread, so the update can happen now.
        action()
    }

    /**
     * Restacks the children so their z-order matches Compose's draw order: each is placed directly
     * above the one before it.
     */
    fun applyZOrder() {
        var below: HWND = HWND_TOP ?: return
        for (holder in holders) {
            val child = holder.interopView as HWND
            SetWindowPos(
                child,
                below,
                0, 0, 0, 0,
                (SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE).toUInt(),
            )
            below = child
        }
    }
}
