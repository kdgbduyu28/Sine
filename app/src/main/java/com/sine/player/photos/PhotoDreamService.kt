package com.sine.player.photos

import android.graphics.Color
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import coil3.asDrawable
import coil3.request.ImageRequest
import com.sine.player.SineApp
import com.sine.player.images.SineImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Screensaver that shows a slideshow of the folder chosen in Sine (Browse → hold OK on a folder →
 * "Use as screensaver"). Android TV lists it under Settings → Device Preferences → Screen saver;
 * Google TV hides third-party screensavers, see the README for the adb command.
 */
@UnstableApi
class PhotoDreamService : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val front = imageView()
        val back = imageView()
        val clock = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        root.addView(back)
        root.addView(front)
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, 64, 48)
        })
        setContentView(root)

        val settings = SineApp.instance.settings
        val folder = settings.screensaverFolder
        scope.launch {
            if (folder.isBlank()) {
                clock.text = "Choose a photo folder in Sine: hold OK on a folder → Use as screensaver"
                return@launch
            }
            val photos = Photos.collect(folder).shuffled()
            if (photos.isEmpty()) {
                clock.text = "No photos found in the screensaver folder"
                return@launch
            }
            val loader = SineImages.loader(this@PhotoDreamService)
            var showFront = true
            var i = 0
            while (isActive) {
                val target = if (showFront) front else back
                val other = if (showFront) back else front
                val request = ImageRequest.Builder(this@PhotoDreamService)
                    .data(photos[i % photos.size].uri)
                    .target(onSuccess = { image ->
                        target.setImageDrawable(image.asDrawable(resources))
                        target.animate().alpha(1f).setDuration(1200).start()
                        other.animate().alpha(0f).setDuration(1200).start()
                    })
                    .build()
                loader.enqueue(request)
                clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                delay(settings.photoIntervalSeconds * 1000L)
                showFront = !showFront
                i++
            }
        }
    }

    private fun imageView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
