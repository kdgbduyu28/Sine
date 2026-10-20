package com.sine.player.library

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

/** One screensaver slide: a library title over its backdrop. */
data class LibrarySlide(val image: String, val title: String, val detail: String, val overview: String?)

object LibrarySlides {
    /** "2016 · ★ 7.9 · Drama, Science Fiction" */
    fun detail(year: Int?, rating: Double?, genres: List<String>): String =
        listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }, genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" · ")

    /** Every movie and show the active profile may see that has a backdrop (or poster), shuffled. */
    fun collect(): List<LibrarySlide> {
        val store = Library.store
        val profile = SineApp.instance.profiles.active
        val movies = store.movies.filter { profile.allows(it.file.uri) }.distinctBy { it.key }.map { it.key to it.title }
        val shows = store.shows.filter { profile.allows(it.folder) }.map { it.key to it.title }
        return (movies + shows).mapNotNull { (key, fallback) ->
            val meta = store.meta[key] ?: return@mapNotNull null
            val image = meta.backdrop ?: meta.poster ?: return@mapNotNull null
            LibrarySlide(image, meta.title ?: fallback, detail(meta.year, meta.rating, meta.genres), meta.overview)
        }.shuffled()
    }
}

/**
 * Screensaver with backdrops from the Sine library. Listed as "Sine library" next to "Sine photos"
 * under Settings → Device Preferences → Screen saver.
 */
@UnstableApi
class LibraryDreamService : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val front = imageView()
        val back = imageView()
        // Dark bottom gradient so the text reads over any picture.
        val shade = android.view.View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
        }
        val title = text(34f)
        val detail = text(18f).apply { alpha = 0.8f }
        val overview = text(16f).apply {
            alpha = 0.75f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val clock = text(28f)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(detail)
            addView(overview)
        }
        root.addView(back)
        root.addView(front)
        root.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420, Gravity.BOTTOM))
        root.addView(info, FrameLayout.LayoutParams(1100, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(72, 0, 0, 56) })
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 64, 56) })
        setContentView(root)

        scope.launch {
            val slides = LibrarySlides.collect()
            if (slides.isEmpty()) {
                title.text = "Add folders to the Sine library (and a TMDB key or local fanart) to see them here"
                return@launch
            }
            val loader = SineImages.loader(this@LibraryDreamService)
            var showFront = true
            var i = 0
            while (isActive) {
                val slide = slides[i % slides.size]
                val target = if (showFront) front else back
                val other = if (showFront) back else front
                loader.enqueue(
                    ImageRequest.Builder(this@LibraryDreamService)
                        .data(slide.image)
                        .target(onSuccess = { image ->
                            target.setImageDrawable(image.asDrawable(resources))
                            // Slow drift, like a documentary pan.
                            target.scaleX = 1.0f
                            target.scaleY = 1.0f
                            target.animate().alpha(1f).scaleX(1.06f).scaleY(1.06f).setDuration(SLIDE_MS).start()
                            other.animate().alpha(0f).setDuration(1500).start()
                            title.text = slide.title
                            detail.text = slide.detail
                            overview.text = slide.overview.orEmpty()
                        })
                        .build(),
                )
                clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                delay(SLIDE_MS)
                showFront = !showFront
                i++
            }
        }
    }

    private fun imageView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun text(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SLIDE_MS = 12_000L
    }
}
