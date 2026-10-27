package com.minimaltv.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Visor DVR Hikvision.
 *  - Mosaico 2x2 (sub-stream, ligero) con paginas de 4 camaras.
 *  - OK sobre una camara = pantalla completa (stream principal).
 *  - Pantalla completa: IZQ/DER cambia de camara, teclas 1-9 saltan a canal, ATRAS vuelve.
 *  - Mosaico: CH+/CH- (o boton "Pagina") cambia de pagina.
 */
class CameraViewerActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var root: FrameLayout
    private val tiles = mutableListOf<CamTile>()
    private var started = false
    private var page = 0
    private var full = 0          // 0 = mosaico, >0 = canal a pantalla completa
    private var direct = false    // abierto directamente en una camara

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = Config(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val ch = intent.getIntExtra("ch", 0)
        if (ch > 0) { direct = true; showFull(ch) } else showMosaic()
    }

    override fun onStart() { super.onStart(); started = true; tiles.forEach { it.start(full > 0) } }
    override fun onStop() { super.onStop(); started = false; tiles.forEach { it.stop() } }

    private fun clear() { tiles.forEach { it.stop() }; tiles.clear(); root.removeAllViews() }

    private fun showMosaic() {
        clear(); full = 0
        val n = cfg.channels; val pages = (n + 3) / 4
        page = page.coerceIn(0, pages - 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(16)) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0..1) {
            val rowLl = LinearLayout(this)
            for (c in 0..1) {
                val ch = page * 4 + r * 2 + c + 1
                val lp = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
                if (ch <= n) {
                    val t = CamTile(this, ch, cfg); tiles.add(t)
                    val w = FrameLayout(this).apply {
                        setPadding(dp(4), dp(4), dp(4), dp(4)); Ui.focusable(this)
                        setOnClickListener { showFull(ch) }
                        addView(t, MATCH_PARENT, MATCH_PARENT)
                    }
                    rowLl.addView(w, lp)
                } else rowLl.addView(View(this), lp)
            }
            grid.addView(rowLl, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        bar.addView(Ui.text(this, "OK: pantalla completa   •   Atrás: salir" + if (pages > 1) "   •   CH+/CH-: página" else "", 12f, color = 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (pages > 1) bar.addView(Ui.text(this, "Página ${page + 1}/$pages  ▸", 13f).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8)); Ui.focusable(this)
            setOnClickListener { page = (page + 1) % pages; showMosaic() }
        })
        col.addView(bar)
        root.addView(col, MATCH_PARENT, MATCH_PARENT)
        if (started) tiles.forEach { it.start(false) }
        grid.post { grid.requestFocus() }
    }

    private fun showFull(ch: Int) {
        clear(); full = ch
        val t = CamTile(this, ch, cfg); tiles.add(t)
        root.addView(t, MATCH_PARENT, MATCH_PARENT)
        val hint = TextView(this).apply {
            text = "◀ ▶ cambiar cámara   •   1-9 ir a canal   •   Atrás: volver"
            setTextColor(Color.WHITE); textSize = 12f; setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        hint.animate().alpha(0f).setStartDelay(4000).setDuration(800).start()
        if (started) t.start(true)
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val n = cfg.channels
        if (full > 0) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                    { showFull(if (full == 1) n else full - 1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP ->
                    { showFull(if (full == n) 1 else full + 1); return true }
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                    val c = k - KeyEvent.KEYCODE_0; if (c <= n) showFull(c); return true
                }
                KeyEvent.KEYCODE_BACK -> { page = (full - 1) / 4; if (direct) finish() else showMosaic(); return true }
            }
        } else {
            val pages = (n + 3) / 4
            when (k) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> { page = (page + 1) % pages; showMosaic(); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> { page = (page + pages - 1) % pages; showMosaic(); return true }
            }
        }
        return super.onKeyDown(k, e)
    }
}
