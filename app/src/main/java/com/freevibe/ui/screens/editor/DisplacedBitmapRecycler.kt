package com.freevibe.ui.screens.editor

import android.graphics.Bitmap

/**
 * Frees the bitmaps the wallpaper editor replaces, one generation late.
 *
 * Every filter render allocates a fresh full-size bitmap and puts it in state.
 * The one it displaced used to be dropped on the floor, so a user dragging a
 * slider handed the collector up to 64 MiB per render — precisely the pressure
 * Android 17's per-app memory limiter kills a process for.
 *
 * Recycling on the spot is not safe. Compose can still be painting the bitmap it
 * has only just been told to stop using, and drawing a recycled bitmap crashes.
 * So a displaced bitmap waits [generations] further replacements before it is
 * freed: once a later render has arrived, the composition has drawn the frame in
 * between and provably let go of this one.
 *
 * Two invariants make a double-recycle unrepresentable rather than merely
 * unlikely: a bitmap is queued at most once, and nothing still reachable from
 * editor state is ever freed. The retained set is re-checked at release time as
 * well as at queue time, because state can take a bitmap back — `resetAll()`
 * restores the original, and a cancelled render leaves the previous frame in
 * place.
 */
internal class DisplacedBitmapRecycler(private val generations: Int = 1) {

    private val pending = ArrayDeque<Bitmap>()

    /** Bitmaps held back, waiting for enough generations to pass. */
    val pendingCount: Int get() = pending.size

    /**
     * Takes ownership of [displaced] and frees whatever has now waited long enough.
     *
     * @param retained everything editor state still points at; never freed.
     * @return how many bitmaps this call actually recycled.
     */
    fun displace(displaced: Bitmap?, retained: Collection<Bitmap?>): Int {
        enqueue(displaced, retained)
        var freed = 0
        while (pending.size > generations) {
            if (release(pending.removeFirst(), retained)) freed++
        }
        return freed
    }

    /**
     * Frees everything held, without waiting.
     *
     * Safe only when nothing can paint any more, which is why the editor calls it
     * from `onCleared()` and nowhere else.
     */
    fun drain(retained: Collection<Bitmap?> = emptyList()): Int {
        var freed = 0
        while (pending.isNotEmpty()) {
            if (release(pending.removeFirst(), retained)) freed++
        }
        return freed
    }

    private fun enqueue(bitmap: Bitmap?, retained: Collection<Bitmap?>) {
        if (bitmap == null || bitmap.isRecycled) return
        if (retained.anyIdenticalTo(bitmap)) return
        if (pending.anyIdenticalTo(bitmap)) return
        pending.addLast(bitmap)
    }

    private fun release(bitmap: Bitmap, retained: Collection<Bitmap?>): Boolean {
        if (retained.anyIdenticalTo(bitmap)) return false
        if (bitmap.isRecycled) return false
        bitmap.recycle()
        return true
    }

    /**
     * Identity, not equality. Two distinct bitmaps holding the same pixels are
     * still two allocations, and `Bitmap.equals` is reference equality today but
     * is not a contract worth resting a recycle decision on.
     */
    private fun Collection<Bitmap?>.anyIdenticalTo(bitmap: Bitmap): Boolean =
        any { it === bitmap }
}
