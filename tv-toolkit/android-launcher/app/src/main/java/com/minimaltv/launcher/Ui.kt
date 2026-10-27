@file:OptIn(UnstableApi::class)

package com.minimaltv.launcher

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun card(c: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(c, 14).toFloat()
        setColor(if (focused) 0x55FFFFFF else 0x33FFFFFF)
        if (focused) setStroke(dp(c, 3), 0xFFFFFFFF.toInt())
    }

    /** Hace una vista navegable con D-pad: borde blanco + zoom suave al enfocar (estilo tvOS). */
    fun focusable(v: View, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true
        v.isClickable = true
        v.background = card(v.context, false)
        v.setOnFocusChangeListener { _, f ->
            v.background = card(v.context, f)
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(140).start()
            v.translationZ = if (f) 12f else 0f
            onFocus?.invoke(f)
        }
    }

    fun text(c: Context, s: String, sp: Float, bold: Boolean = false, color: Int = 0xFFFFFFFF.toInt()) =
        TextView(c).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
}

/** Mosaico/tile de una camara del DVR (RTSP via Media3). */
class CamTile(ctx: Context, val channel: Int, private val cfg: Config) : FrameLayout(ctx) {
    private val pv: PlayerView
    private val status: TextView
    private val live: View
    private var player: ExoPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private var mainStream = false

    init {
        LayoutInflater.from(ctx).inflate(R.layout.tile_camera, this, true)
        pv = findViewById(R.id.player)
        status = findViewById(R.id.status)
        live = findViewById(R.id.live)
        findViewById<TextView>(R.id.label).text = "Cámara $channel"
        val r = Ui.dp(ctx, 12).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
        }
        clipToOutline = true
    }

    fun start(main: Boolean) {
        stop()
        mainStream = main
        if (!cfg.configured) { status.text = "Configura el DVR"; return }
        status.text = "Conectando…"; status.visibility = VISIBLE
        val src = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(cfg.rtsp(channel, main)))
        player = ExoPlayer.Builder(context).build().also { p ->
            p.volume = if (main) 1f else 0f
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) {
                    if (s == Player.STATE_READY) {
                        status.visibility = GONE; live.setBackgroundColor(0xFF34C759.toInt())
                    }
                }
                override fun onPlayerError(e: PlaybackException) {
                    status.text = "Sin señal"; status.visibility = VISIBLE
                    live.setBackgroundColor(0xFFFF3B30.toInt())
                    h.postDelayed({ start(mainStream) }, 6000)   // reintento automatico
                }
            })
            p.setMediaSource(src); p.prepare(); p.playWhenReady = true
            pv.player = p
        }
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        pv.player = null
        player?.release(); player = null
    }
}
