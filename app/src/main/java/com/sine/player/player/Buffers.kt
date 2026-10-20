package com.sine.player.player

import android.app.ActivityManager
import android.content.Context

enum class BufferSize(val label: String, val description: String) {
    NORMAL("Normal", "Enough for most files; uses the least memory"),
    LARGE("Large", "Rides out Wi-Fi hiccups on big remuxes"),
    HUGE("Huge", "Minutes of a 4K remux ahead; for TVs with plenty of memory"),
}

/** How much each engine may read ahead. Media3 buffers in the app's Java heap; mpv in native memory. */
data class BufferPlan(val media3Bytes: Int, val maxBufferMs: Int, val mpvBytes: Long, val mpvSeconds: Int) {
    companion object {
        val DEFAULT = BufferPlan(media3Bytes = 0, maxBufferMs = 60_000, mpvBytes = 160L * MB, mpvSeconds = 0)
        const val MB = 1024 * 1024
    }
}

object Buffers {
    /**
     * Sizes [size] to this device: Media3 gets at most a share of the heap (it must also hold the
     * app), mpv at most a quarter of the TV's RAM.
     */
    fun plan(size: BufferSize, heapMb: Int, totalRamMb: Long): BufferPlan {
        if (size == BufferSize.NORMAL) return BufferPlan.DEFAULT
        val (share, cap, mpvWant) = when (size) {
            BufferSize.LARGE -> Triple(0.35, 256, 400L)
            else -> Triple(0.6, 512, 800L)
        }
        val media3Mb = minOf((heapMb * share).toInt(), cap).coerceAtLeast(64)
        val mpvMb = minOf(mpvWant, totalRamMb / 4).coerceAtLeast(160)
        return BufferPlan(media3Mb * BufferPlan.MB, maxBufferMs = 600_000, mpvBytes = mpvMb * BufferPlan.MB, mpvSeconds = 600)
    }

    fun forDevice(context: Context, size: BufferSize): BufferPlan {
        val am = context.getSystemService(ActivityManager::class.java) ?: return BufferPlan.DEFAULT
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // largeHeap is set in the manifest, so the large memory class is what Media3 can use.
        return plan(size, am.largeMemoryClass, info.totalMem / BufferPlan.MB)
    }
}
