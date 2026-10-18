// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.util.Log
import java.io.IOException

class SettingsBackgroundView(
    context: android.content.Context,
    private val source: SettingsAppearanceSource,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private var imageDrawable: Drawable? = null
    private var imageView: ImageView? = null
    private var textureView: TextureView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var hostResumed = true

    init {
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO)
        isClickable = false
        isFocusable = false
        alpha = source.opacity / 100f
        if (Build.VERSION.SDK_INT >= 31 && source.blur > 0) {
            val radius = source.blur
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        if (source.isVideo) createVideoView() else createImageView()
    }

    fun sourceKey() = source.cacheKey()

    fun onHostResume() {
        hostResumed = true
        (imageDrawable as? AnimatedImageDrawable)?.start()
        runCatching { mediaPlayer?.start() }
    }

    fun onHostStop() {
        hostResumed = false
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        runCatching { mediaPlayer?.takeIf { it.isPlaying }?.pause() }
    }

    fun dispose() {
        (imageDrawable as? AnimatedImageDrawable)?.stop()
        releasePlayer()
        textureView?.surfaceTextureListener = null
        removeAllViews()
    }

    private fun createImageView() {
        runCatching {
            imageView = ImageView(context).also {
                it.scaleType = ImageView.ScaleType.CENTER_CROP
                imageDrawable = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.contentResolver, source.uri),
                )
                it.setImageDrawable(imageDrawable)
            }
            addView(imageView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            (imageDrawable as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        }.onFailure { Log.e(TAG, "Cannot decode Settings background", it) }
    }

    private fun createVideoView() {
        textureView = TextureView(context).also {
            it.isOpaque = false
            it.surfaceTextureListener = this
        }
        addView(textureView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) = startPlayer(surfaceTexture)
    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = updateVideoTransform()
    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean { releasePlayer(); return true }
    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun startPlayer(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        runCatching {
            descriptor = context.contentResolver.openFileDescriptor(source.uri, "r") ?: error("Cannot open video")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(descriptor!!.fileDescriptor)
                val surface = Surface(surfaceTexture)
                setSurface(surface)
                surface.release()
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, width, height ->
                    this@SettingsBackgroundView.videoWidth = width
                    this@SettingsBackgroundView.videoHeight = height
                    this@SettingsBackgroundView.updateVideoTransform()
                }
                setOnPreparedListener {
                    closeDescriptor()
                    this@SettingsBackgroundView.videoWidth = it.videoWidth
                    this@SettingsBackgroundView.videoHeight = it.videoHeight
                    this@SettingsBackgroundView.updateVideoTransform()
                    if (hostResumed) it.start()
                }
                setOnErrorListener { _, what, extra -> Log.e(TAG, "Video background failed: $what/$extra"); closeDescriptor(); true }
                prepareAsync()
            }
        }.onFailure {
            Log.e(TAG, "Cannot start Settings video background", it)
            releasePlayer()
        }
    }

    private fun updateVideoTransform() {
        val view = textureView ?: return
        if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
        val scale = maxOf(view.width.toFloat() / videoWidth, view.height.toFloat() / videoHeight)
        val matrix = Matrix().apply {
            setScale(videoWidth * scale / view.width, videoHeight * scale / view.height, view.width / 2f, view.height / 2f)
        }
        view.setTransform(matrix)
    }

    private fun releasePlayer() {
        closeDescriptor()
        mediaPlayer?.let { player ->
            runCatching { player.setSurface(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    private fun closeDescriptor() { runCatching { descriptor?.close() }; descriptor = null }

    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }

    companion object { private const val TAG = "HyperChangerSettingsAppearance" }
}
