package com.flyphoto.usb_camera_sdk

/** Handle-only Canon catalog used to keep automatic reconciliation independent of metadata size. */
internal class CanonHandleCatalog {
    private val handles = linkedSetOf<Int>()
    private var storageIds = emptySet<Int>()

    var initialized: Boolean = false
        private set

    fun hasSameStorage(candidate: Set<Int>): Boolean =
        initialized && storageIds == candidate

    fun replaceBaseline(candidateStorageIds: Set<Int>, candidateHandles: Set<Int>) {
        storageIds = candidateStorageIds.toSet()
        handles.clear()
        handles += candidateHandles
        initialized = true
    }

    fun reconcile(candidateHandles: Set<Int>): List<Int> {
        check(initialized) { "Canon handle baseline is not initialized" }
        val added = candidateHandles.filterNot(handles::contains)
        handles.clear()
        handles += candidateHandles
        return added
    }

    fun observe(handle: Int) {
        if (initialized) handles += handle
    }

    fun clear() {
        handles.clear()
        storageIds = emptySet()
        initialized = false
    }
}
