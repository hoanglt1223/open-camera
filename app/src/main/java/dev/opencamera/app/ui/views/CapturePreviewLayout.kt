/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.opencamera.app.R
import dev.opencamera.app.ext.decodeSampled
import dev.opencamera.app.ext.smoothRotate
import dev.opencamera.app.models.MediaType
import dev.opencamera.app.models.Rotation
import dev.opencamera.app.utils.ExifUtils
import java.io.InputStream

/**
 * Image/video preview fragment
 */
class CapturePreviewLayout(context: Context, attrs: AttributeSet?) : ConstraintLayout(
    context, attrs
) {
    private var uri: Uri? = null
    private var photoInputStream: InputStream? = null
    private lateinit var mediaType: MediaType

    private var exoPlayer: ExoPlayer? = null

    private val cancelButton by lazy { findViewById<ImageButton>(R.id.cancelButton) }
    private val confirmButton by lazy { findViewById<ImageButton>(R.id.confirmButton) }
    private val imageView by lazy { findViewById<ImageView>(R.id.imageView) }
    private val videoView by lazy { findViewById<PlayerView>(R.id.videoView) }

    /**
     * input is null == canceled
     * input is not null == confirmed
     */
    internal var onChoiceCallback: (input: Any?) -> Unit = {}

    private var screenRotation = Rotation.ROTATION_0

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        setOnClickListener {
            // Prevent clicks behind the view
        }

        cancelButton.setOnClickListener {
            stopPreview()
            onChoiceCallback(null)
        }
        confirmButton.setOnClickListener {
            stopPreview()
            onChoiceCallback(uri ?: photoInputStream)
        }
    }

    internal fun updateSource(uri: Uri, mediaType: MediaType) {
        this.uri = uri
        this.photoInputStream = null
        this.mediaType = mediaType

        imageView.isVisible = mediaType == MediaType.PHOTO
        videoView.isVisible = mediaType == MediaType.VIDEO

        startPreview()
    }

    internal fun updateSource(photoInputStream: InputStream) {
        this.uri = null
        this.photoInputStream = photoInputStream
        this.mediaType = MediaType.PHOTO

        imageView.isVisible = true
        videoView.isVisible = false

        startPreview()
    }

    fun setScreenRotation(screenRotation: Rotation) {
        this.screenRotation = screenRotation

        val compensationValue = screenRotation.compensationValue.toFloat()

        cancelButton.smoothRotate(compensationValue)
        confirmButton.smoothRotate(compensationValue)
    }

    private var decodes = 0

    private fun previewLongSide() = maxOf(width, height, resources.displayMetrics.heightPixels)

    /** Decodes on a background thread; a newer preview wins over a late result. */
    private fun showDecoded(decode: () -> Bitmap?) {
        imageView.setImageDrawable(null)
        val generation = ++decodes
        decodeExecutor.execute {
            val bitmap = runCatching { decode() }
                .onFailure { Log.w(LOG_TAG, "Preview decode failed", it) }.getOrNull()
            post { if (generation == decodes) imageView.setImageBitmap(bitmap) }
        }
    }

    private fun startPreview() {
        assert((uri == null) != (photoInputStream == null)) {
            "Expected uri or photoInputStream, not both."
        }
        when (mediaType) {
            MediaType.PHOTO -> {
                if (uri != null) {
                    imageView.rotation = 0f
                    imageView.scaleX = 1f
                    val source = uri!!
                    showDecoded { decodeSampled({ context.contentResolver.openInputStream(source) }, previewLongSide()) }
                } else {
                    val inputStream = photoInputStream!!
                    val transform = ExifUtils.getTransform(inputStream)
                    inputStream.mark(Int.MAX_VALUE)
                    val bytes = inputStream.readBytes()
                    inputStream.reset()
                    showDecoded { decodeSampled({ bytes.inputStream() }, previewLongSide()) }
                    Log.d(LOG_TAG, "Preview transform=$transform screenRotation=$screenRotation")
                    imageView.rotation =
                        transform.rotation.offset.toFloat() - screenRotation.offset
                    imageView.scaleX = if (transform.mirror) {
                        -1f
                    } else {
                        1f
                    }
                }
            }

            MediaType.VIDEO -> {
                exoPlayer = ExoPlayer.Builder(context)
                    .build()
                    .also {
                        videoView.player = it

                        it.setMediaItem(MediaItem.fromUri(uri!!))

                        it.playWhenReady = true
                        it.seekTo(0)
                        it.prepare()
                    }
            }
        }
    }

    private fun stopPreview() {
        when (mediaType) {
            MediaType.PHOTO -> {}
            MediaType.VIDEO -> {
                exoPlayer?.release()
                exoPlayer = null
            }
        }
    }

    companion object {
        private const val LOG_TAG = "Aperture"
        private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
