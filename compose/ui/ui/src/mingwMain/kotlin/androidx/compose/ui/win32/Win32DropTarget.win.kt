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

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.posix.fflush
import platform.windows.DVASPECT_CONTENT
import platform.windows.DragQueryFileW
import platform.windows.FORMATETC
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HDROP
import platform.windows.HGLOBAL
import platform.windows.HWND
import platform.windows.IDataObject
import platform.windows.IDropTarget
import platform.windows.IDropTargetVtbl
import platform.windows.POINTL
import platform.windows.RegisterDragDrop
import platform.windows.ReleaseStgMedium
import platform.windows.RevokeDragDrop
import platform.windows.STGMEDIUM
import platform.windows.ScreenToClient
import platform.windows.TYMED_HGLOBAL
import platform.windows.WCHARVar

/** `DROPEFFECT_*`; only copy is offered, which is what a viewer-style drop wants. */
private const val DROPEFFECT_NONE = 0u
private const val DROPEFFECT_COPY = 1u

/** Clipboard format id for a shell file list. */
private const val CF_HDROP = 15u

/** Result of a transactional [RegisterDragDrop] attempt. */
internal data class Win32DropTargetRegistration(
    val target: Win32DropTarget?,
    val hresult: Int,
)

/**
 * The `IDropTarget` registered for one Compose window.
 *
 * The object starts with one owner reference. `RegisterDragDrop` takes another through `AddRef`,
 * `RevokeDragDrop` returns that registration reference through `Release`, and [dispose] finally
 * returns the owner's reference. Native memory is released only when the real COM count reaches
 * zero, so an interface pointer acquired by the shell can never outlive its vtable.
 */
internal class Win32DropTarget private constructor(
    private val hwnd: HWND,
    private val onDragEnter: (DragAndDropEvent) -> Boolean,
    private val onDragOver: (DragAndDropEvent) -> Unit,
    private val onDragLeave: () -> Unit,
    private val onDrop: (DragAndDropEvent) -> Boolean,
) {
    private val vtable = nativeHeap.alloc<IDropTargetVtbl>()
    internal val comObject = nativeHeap.alloc<IDropTarget>()
    private val references = StaComReferenceCount(::destroyNative)

    private var isRegistered = false
    private var ownerReferenceReleased = false
    private var acceptsCallbacks = true

    /** Payload of the drag in progress, read once on enter and reused while it moves. */
    private var draggedText: String? = null
    private var draggedFiles: List<String> = emptyList()
    private var isAccepted = false

    internal val referenceCount: UInt get() = references.value

    init {
        vtable.QueryInterface = staticCFunction { self, iid, output ->
            if (output == null) {
                E_POINTER
            } else {
                output.pointed.value = null
                if (iid == null) {
                    E_POINTER
                } else {
                    val target = targets[self.rawAddress()]
                    when {
                        target == null -> E_UNEXPECTED
                        !iid.matchesOleInterfaceId(IID_DATA1_IUNKNOWN) &&
                            !iid.matchesOleInterfaceId(IID_DATA1_IDROPTARGET) -> E_NOINTERFACE
                        else -> {
                            output.pointed.value = self
                            target.references.addRef()
                            S_OK
                        }
                    }
                }
            }
        }
        vtable.AddRef = staticCFunction { self ->
            targets[self.rawAddress()]?.references?.addRef() ?: 0u
        }
        vtable.Release = staticCFunction { self ->
            targets[self.rawAddress()]?.references?.release() ?: 0u
        }

        vtable.DragEnter = staticCFunction { self, dataObject, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            if (effect == null) {
                E_POINTER
            } else if (dataObject == null) {
                effect.pointed.value = DROPEFFECT_NONE
                E_INVALIDARG
            } else if (target == null || !target.acceptsCallbacks) {
                effect.pointed.value = DROPEFFECT_NONE
                E_UNEXPECTED
            } else {
                val allowedEffects = effect.pointed.value
                try {
                    target.clearPayload()
                    if (allowedEffects and DROPEFFECT_COPY != 0u) {
                        target.readPayload(dataObject)
                        target.isAccepted =
                            target.onDragEnter(target.eventAt(point, keyState.toInt()))
                    }
                    effect.pointed.value = target.currentEffect(allowedEffects)
                    S_OK
                } catch (throwable: Throwable) {
                    effect.pointed.value = DROPEFFECT_NONE
                    target.clearPayload()
                    reportComCallbackFailure("IDropTarget::DragEnter", throwable)
                    E_UNEXPECTED
                }
            }
        }

        vtable.DragOver = staticCFunction { self, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            if (effect == null) {
                E_POINTER
            } else if (target == null || !target.acceptsCallbacks) {
                effect.pointed.value = DROPEFFECT_NONE
                E_UNEXPECTED
            } else {
                val allowedEffects = effect.pointed.value
                try {
                    if (target.isAccepted) {
                        target.onDragOver(target.eventAt(point, keyState.toInt()))
                    }
                    effect.pointed.value = target.currentEffect(allowedEffects)
                    S_OK
                } catch (throwable: Throwable) {
                    effect.pointed.value = DROPEFFECT_NONE
                    // The shell still owns this drag session. Preserve acceptance so its eventual
                    // DragLeave or Drop can balance Compose's onStarted with onEnded.
                    reportComCallbackFailure("IDropTarget::DragOver", throwable)
                    E_UNEXPECTED
                }
            }
        }

        vtable.DragLeave = staticCFunction { self ->
            val target = targets[self.rawAddress()]
            if (target == null || !target.acceptsCallbacks) {
                E_UNEXPECTED
            } else {
                try {
                    if (target.isAccepted) target.onDragLeave()
                    S_OK
                } catch (throwable: Throwable) {
                    reportComCallbackFailure("IDropTarget::DragLeave", throwable)
                    E_UNEXPECTED
                } finally {
                    target.clearPayload()
                }
            }
        }

        vtable.Drop = staticCFunction { self, dataObject, keyState, point, effect ->
            val target = targets[self.rawAddress()]
            if (effect == null) {
                E_POINTER
            } else if (dataObject == null) {
                effect.pointed.value = DROPEFFECT_NONE
                E_INVALIDARG
            } else if (target == null || !target.acceptsCallbacks) {
                effect.pointed.value = DROPEFFECT_NONE
                E_UNEXPECTED
            } else {
                val allowedEffects = effect.pointed.value
                var terminalCallbackAttempted = false
                try {
                    if (!target.isAccepted) {
                        effect.pointed.value = DROPEFFECT_NONE
                    } else if (allowedEffects and DROPEFFECT_COPY == 0u) {
                        // Compose only offers COPY. A source that forbids it must not receive an
                        // onDrop callback, but the accepted drag lifecycle still has to end.
                        terminalCallbackAttempted = true
                        target.onDragLeave()
                        effect.pointed.value = DROPEFFECT_NONE
                    } else {
                        // Re-read: a shell drop may carry data the enter notification did not.
                        target.readPayload(dataObject)
                        terminalCallbackAttempted = true
                        val accepted = target.onDrop(target.eventAt(point, keyState.toInt()))
                        effect.pointed.value = if (accepted) DROPEFFECT_COPY else DROPEFFECT_NONE
                    }
                    S_OK
                } catch (throwable: Throwable) {
                    effect.pointed.value = DROPEFFECT_NONE
                    if (target.isAccepted && !terminalCallbackAttempted) {
                        terminalCallbackAttempted = true
                        try {
                            target.onDragLeave()
                        } catch (finishFailure: Throwable) {
                            throwable.addSuppressed(finishFailure)
                        }
                    }
                    reportComCallbackFailure("IDropTarget::Drop", throwable)
                    E_UNEXPECTED
                } finally {
                    target.clearPayload()
                }
            }
        }

        comObject.lpVtbl = vtable.ptr
        targets[comObject.ptr.rawAddress()] = this
    }

    /** Revokes the HWND registration and returns this object's owner reference exactly once. */
    fun dispose(): Int {
        if (ownerReferenceReleased) return S_FALSE
        acceptsCallbacks = false
        if (!isRegistered) return S_FALSE

        val revokeResult = RevokeDragDrop(hwnd)
        // RevokeDragDrop calls IDropTarget::Release only on success. Keep both our registration
        // state and owner reference intact on failure: freeing either allocation while OLE may
        // retain its interface pointer would turn a cleanup error into use-after-free.
        if (revokeResult != S_OK) return revokeResult

        isRegistered = false
        ownerReferenceReleased = true
        references.release()
        return revokeResult
    }

    private fun currentEffect(allowedEffects: UInt): UInt =
        if (isAccepted && allowedEffects and DROPEFFECT_COPY != 0u) {
            DROPEFFECT_COPY
        } else {
            DROPEFFECT_NONE
        }

    private fun clearPayload() {
        draggedText = null
        draggedFiles = emptyList()
        isAccepted = false
    }

    /** Builds the Compose event, converting the shell's screen point to client coordinates. */
    private fun eventAt(point: CValue<POINTL>, keyState: Int): DragAndDropEvent {
        val position = point.useContents {
            memScoped {
                val client = alloc<platform.windows.POINT>()
                client.x = x
                client.y = y
                ScreenToClient(hwnd, client.ptr)
                Offset(client.x.toFloat(), client.y.toFloat())
            }
        }
        return DragAndDropEvent(
            position = position,
            text = draggedText,
            files = draggedFiles,
            keyState = keyState,
        )
    }

    /** Copies the payload out while the source's `IDataObject` is valid. */
    private fun readPayload(dataObject: CPointer<IDataObject>?) {
        draggedText = null
        draggedFiles = emptyList()
        if (dataObject == null) return
        draggedText = readText(dataObject)
        draggedFiles = readFiles(dataObject)
    }

    private fun readText(dataObject: CPointer<IDataObject>): String? = memScoped {
        val medium = alloc<STGMEDIUM>()
        if (!getData(dataObject, CF_UNICODETEXT, medium)) return@memScoped null
        try {
            val handle = medium.hGlobal ?: return@memScoped null
            readUtf16Global(handle)
        } finally {
            ReleaseStgMedium(medium.ptr)
        }
    }

    private fun readFiles(dataObject: CPointer<IDataObject>): List<String> = memScoped {
        val medium = alloc<STGMEDIUM>()
        if (!getData(dataObject, CF_HDROP, medium)) return@memScoped emptyList()
        try {
            val handle = medium.hGlobal ?: return@memScoped emptyList()
            val drop: HDROP = handle.reinterpret()
            // 0xFFFFFFFF asks for the number of paths rather than one of them.
            val count = DragQueryFileW(drop, 0xFFFFFFFFu, null, 0u).toInt()
            if (count <= 0) return@memScoped emptyList()
            (0 until count).mapNotNull { index ->
                val length = DragQueryFileW(drop, index.toUInt(), null, 0u).toInt()
                if (length <= 0) return@mapNotNull null
                val buffer = allocArray<WCHARVar>(length + 1)
                val copied = DragQueryFileW(drop, index.toUInt(), buffer, (length + 1).toUInt())
                if (copied.toInt() != length) return@mapNotNull null
                buildString(length) { for (i in 0 until length) append(buffer[i].toInt().toChar()) }
            }
        } finally {
            ReleaseStgMedium(medium.ptr)
        }
    }

    private fun destroyNative() {
        targets.remove(comObject.ptr.rawAddress())
        nativeHeap.free(comObject.rawPtr)
        nativeHeap.free(vtable.rawPtr)
    }

    companion object {
        /**
         * Registers a fully initialized object, or frees every allocation before reporting failure.
         */
        fun register(
            hwnd: HWND,
            onDragEnter: (DragAndDropEvent) -> Boolean,
            onDragOver: (DragAndDropEvent) -> Unit,
            onDragLeave: () -> Unit,
            onDrop: (DragAndDropEvent) -> Boolean,
        ): Win32DropTargetRegistration {
            val target = Win32DropTarget(
                hwnd = hwnd,
                onDragEnter = onDragEnter,
                onDragOver = onDragOver,
                onDragLeave = onDragLeave,
                onDrop = onDrop,
            )
            val result = RegisterDragDrop(hwnd, target.comObject.ptr)
            return if (result == S_OK) {
                target.isRegistered = true
                Win32DropTargetRegistration(target, result)
            } else {
                target.ownerReferenceReleased = true
                target.acceptsCallbacks = false
                target.references.release()
                Win32DropTargetRegistration(null, result)
            }
        }
    }
}

/** Requests one clipboard format from the data object, filling [medium] on success. */
private fun getData(
    dataObject: CPointer<IDataObject>,
    format: UInt,
    medium: STGMEDIUM,
): Boolean {
    medium.tymed = 0u
    medium.hGlobal = null
    medium.pUnkForRelease = null
    val getDataMethod = dataObject.pointed.lpVtbl?.pointed?.GetData ?: return false
    return memScoped {
        val request = alloc<FORMATETC>()
        request.cfFormat = format.toUShort()
        request.ptd = null
        request.dwAspect = DVASPECT_CONTENT
        request.lindex = -1
        request.tymed = TYMED_HGLOBAL
        val result = getDataMethod(dataObject, request.ptr, medium.ptr)
        if (result != S_OK) return@memScoped false
        if (medium.tymed == TYMED_HGLOBAL && medium.hGlobal != null) {
            true
        } else {
            // A successful GetData transfers ownership even when a buggy provider returns an
            // unusable medium. Release it before rejecting the payload.
            ReleaseStgMedium(medium.ptr)
            medium.tymed = 0u
            medium.hGlobal = null
            medium.pUnkForRelease = null
            false
        }
    }
}

/** Reads a NUL-terminated UTF-16 block without stepping beyond the HGLOBAL allocation. */
private fun readUtf16Global(handle: HGLOBAL): String? {
    val byteSize = GlobalSize(handle).toLong()
    val characterSize = sizeOf<WCHARVar>()
    if (byteSize < characterSize || byteSize % characterSize != 0L) return null
    val pointer = GlobalLock(handle) ?: return null
    try {
        val characters = pointer.reinterpret<WCHARVar>()
        val maximum = minOf(
            byteSize / characterSize,
            Int.MAX_VALUE.toLong(),
        ).toInt()
        return characters.readBoundedNullTerminatedUtf16(maximum)
    } finally {
        GlobalUnlock(handle)
    }
}

/** Identity of a COM pointer, used to find the Kotlin object behind a `This` pointer. */
private fun CPointer<IDropTarget>?.rawAddress(): Long = this?.rawValue?.toLong() ?: 0L

/** Every live target; OLE invokes these objects only on their registering STA thread. */
private val targets = mutableMapOf<Long, Win32DropTarget>()

internal val liveWin32DropTargetCount: Int get() = targets.size

private fun reportComCallbackFailure(method: String, throwable: Throwable) {
    println("Exception in $method:")
    throwable.printStackTrace()
    fflush(null)
}
