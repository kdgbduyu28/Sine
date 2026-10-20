package com.sine.player.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.C

/**
 * HDR video on a display that can't show it looks washed out with Media3 (no tone mapping in
 * playback). mpv can map HDR10/HLG to SDR itself, so Sine switches to it for those files.
 */
object ToneMap {
    /** mpv: render through libplacebo and map to SDR BT.709 (no effect on SDR files). */
    val mpvOptions = mapOf(
        "tone-mapping" to "bt.2446a",
        "hdr-compute-peak" to "yes",
        "target-prim" to "bt.709",
        "target-trc" to "bt.1886",
        "target-colorspace-hint" to "no",
    )

    /** Whether a video with [colorTransfer] needs mapping on a display with these HDR types. */
    fun needed(colorTransfer: Int?, displayHdrTypes: Set<Int>): Boolean = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> Display.HdrCapabilities.HDR_TYPE_HDR10 !in displayHdrTypes &&
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION !in displayHdrTypes
        C.COLOR_TRANSFER_HLG -> Display.HdrCapabilities.HDR_TYPE_HLG !in displayHdrTypes
        else -> false
    }

    fun apply(options: EngineOptions): EngineOptions =
        // Direct MediaCodec output would bypass mpv's renderer, and with it the tone mapping.
        options.copy(mpvDirectOutput = false, mpvExtra = options.mpvExtra + mpvOptions)

    @Suppress("DEPRECATION")
    fun displayHdrTypes(context: Context): Set<Int> = runCatching {
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?.hdrCapabilities?.supportedHdrTypes?.toSet()
    }.getOrNull().orEmpty()
}
