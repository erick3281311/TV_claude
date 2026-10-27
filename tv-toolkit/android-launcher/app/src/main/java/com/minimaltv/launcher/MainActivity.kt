package com.minimaltv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

/**
 * Launcher minimalista estilo tvOS:
 *  - Saludo + reloj, titulo "Inicio"
 *  - Dock de favoritos (mantener OK sobre una app = anadir/quitar favorito)
 *  - Fila "Vigilancia de Camaras" con video en vivo del DVR Hikvision (sub-stream RTSP)
 *  - Fila "Mis Aplicaciones"
 *  - Fondos de naturaleza rotativos (getExternalFilesDir("wallpapers")), sin anuncios ni recomendaciones
 */
class MainActivity : Activity() {
    private class App(val pkg: String, val cls: String, val label: String, val icon: Drawable, val banner: Boolean)

    private lateinit var cfg: Config
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private val cams = mutableListOf<CamTile>()
    private val h = Handler(Looper.getMainLooper())
    private var started = false
    private var frontA = true
    private var walls: List<File> = emptyList()
    private var wIdx = 0
    private val rotate = object : Runnable {
        override fun run() { nextWall(); h.postDelayed(this, 60_000) }
    }

    private val preferred = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow", "com.spotify.tv.android",
        "com.apple.atve.androidtv.appletv", "com.crunchyroll.crunchyroid"
    )

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bgA = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt(), 0xFF2C5364.toInt())))
        }
        bgB = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val dim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0x33000000, 0xCC000000.toInt()))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(40))
            clipChildren = false; clipToPadding = false
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        }
        listOf(bgA, bgB, dim, scroll).forEach { root.addView(it, MATCH_PARENT, MATCH_PARENT) }
        setContentView(root)
        rebuild()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); rebuild() }   // Home pulsado: vuelve al inicio
    @Deprecated("launcher: Atras no hace nada")
    override fun onBackPressed() {}

    override fun onStart() {
        super.onStart(); started = true
        cams.forEach { it.start(false) }
        walls = (getExternalFilesDir("wallpapers")?.listFiles() ?: emptyArray())
            .filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }.shuffled()
        h.removeCallbacks(rotate); h.post(rotate)
    }

    override fun onStop() {
        super.onStop(); started = false
        cams.forEach { it.stop() }; h.removeCallbacks(rotate)
    }

    // ------------------------------------------------------------------ UI
    private fun rebuild() {
        cams.forEach { it.stop() }; cams.clear(); content.removeAllViews()
        val apps = loadApps()

        // Cabecera
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val greet = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        greet.addView(Ui.text(this, if (cfg.userName.isBlank()) "¡Hola!" else "¡Hola, ${cfg.userName}!", 16f, color = 0xFFFFFFFF.toInt()))
        greet.addView(TextClock(this).apply {
            format24Hour = "HH:mm  |  EEE d MMM"; format12Hour = "HH:mm  |  EEE d MMM"
            textSize = 14f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(16), 0, 0, 0)
        })
        top.addView(greet, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(pill("Cámaras DVR") { dvrDialog() })
        top.addView(pill("Ajustes") {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        content.addView(top)
        content.addView(Ui.text(this, "Inicio", 34f, bold = true).apply { setPadding(0, dp(12), 0, 0) })

        // Favoritos
        if (!cfg.hasFavs) cfg.favorites = preferred.filter { p -> apps.any { it.pkg == p } }.toMutableList()
        val favs = cfg.favorites.mapNotNull { f -> apps.firstOrNull { it.pkg == f } }
        if (favs.isNotEmpty()) {
            val r = row("Favoritos")
            favs.forEach { r.addView(appTile(it)) }
        }

        // Camaras
        if (cfg.configured) {
            val n = cfg.channels
            val r = row("Vigilancia de Cámaras ($n)")
            for (ch in 1..minOf(n, 4)) r.addView(camTile(ch))
            r.addView(infoCard("Ver todas ▸") { openViewer(0) })
        } else {
            val r = row("Vigilancia de Cámaras")
            r.addView(infoCard("＋ Añadir DVR Hikvision") { dvrDialog() })
        }

        // Todas las apps
        val r = row("Mis Aplicaciones")
        apps.forEach { r.addView(appTile(it)) }

        scroll.scrollTo(0, 0)
        content.post { content.requestFocus() }
        if (started) cams.forEach { it.start(false) }
    }

    private fun row(title: String): LinearLayout {
        content.addView(Ui.text(this, title, 16f, bold = true).apply { setPadding(0, dp(22), 0, 0) })
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(16), dp(8), dp(12))
        }
        val ll = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        hs.addView(ll); content.addView(hs)
        return ll
    }

    private fun pill(label: String, click: () -> Unit) = Ui.text(this, label, 13f).apply {
        setPadding(dp(18), dp(8), dp(18), dp(8)); gravity = Gravity.CENTER
        Ui.focusable(this); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) }
    }

    private fun round(v: View, r: Int) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, dp(r).toFloat())
        }
        v.clipToOutline = true
    }

    private fun appTile(app: App): View {
        val card = FrameLayout(this).also { Ui.focusable(it) }
        round(card, 14)
        card.addView(ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = if (app.banner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (!app.banner) setPadding(dp(30), dp(14), dp(30), dp(14))
        }, MATCH_PARENT, MATCH_PARENT)
        card.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app.pkg, app.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        card.setOnLongClickListener {   // mantener OK: favorito on/off
            val f = cfg.favorites
            if (f.contains(app.pkg)) f.remove(app.pkg) else f.add(app.pkg)
            cfg.favorites = f
            Toast.makeText(this, if (f.contains(app.pkg)) "★ ${app.label} añadida a favoritos" else "${app.label} quitada de favoritos", Toast.LENGTH_SHORT).show()
            rebuild(); true
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, dp(176), dp(99))
            addView(Ui.text(this@MainActivity, app.label, 12f, color = 0xCCFFFFFF.toInt()).apply {
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }, dp(176), WRAP_CONTENT)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(18) }
        }
    }

    private fun camTile(ch: Int): View {
        val card = FrameLayout(this).also { Ui.focusable(it) }
        card.setPadding(dp(3), dp(3), dp(3), dp(3))
        if (cfg.livePreview) {
            val t = CamTile(this, ch, cfg); cams.add(t); card.addView(t, MATCH_PARENT, MATCH_PARENT)
        } else {
            card.addView(Ui.text(this, "📷  Cámara $ch", 16f).apply { gravity = Gravity.CENTER }, MATCH_PARENT, MATCH_PARENT)
        }
        card.setOnClickListener { openViewer(ch) }
        card.layoutParams = LinearLayout.LayoutParams(dp(280), dp(158)).apply { marginEnd = dp(18) }
        return card
    }

    private fun infoCard(label: String, click: () -> Unit) = FrameLayout(this).apply {
        Ui.focusable(this); setOnClickListener { click() }
        addView(Ui.text(context, label, 15f, bold = true).apply { gravity = Gravity.CENTER }, MATCH_PARENT, MATCH_PARENT)
        layoutParams = LinearLayout.LayoutParams(dp(200), dp(158)).apply { marginEnd = dp(18) }
    }

    private fun openViewer(ch: Int) {
        if (!cfg.configured) { dvrDialog(); return }
        startActivity(Intent(this, CameraViewerActivity::class.java).putExtra("ch", ch))
    }

    // ------------------------------------------------------------------ Apps
    private fun loadApps(): List<App> {
        val out = mutableListOf<App>(); val seen = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in packageManager.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == packageName || !seen.add(ai.packageName)) continue
                val banner = ai.loadBanner(packageManager)
                out += App(ai.packageName, ai.name, ri.loadLabel(packageManager).toString(), banner ?: ai.loadIcon(packageManager), banner != null)
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // ------------------------------------------------------------------ Dialogo DVR
    private fun dvrDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        box.addView(Ui.text(this, "Hikvision DVR (cámaras analógicas): IP local o DDNS del DVR, puerto RTSP (554), " +
            "usuario con permiso de vista en vivo.", 12f, color = 0xAAFFFFFF.toInt()))
        fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            box.addView(Ui.text(this, label, 12f).apply { setPadding(0, dp(10), 0, 0) })
            return EditText(this).apply { setText(value); inputType = type; setSingleLine(); box.addView(this) }
        }
        val name = field("Tu nombre", cfg.userName)
        val host = field("IP / host del DVR", cfg.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Puerto RTSP", cfg.port.toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field("Usuario", cfg.user)
        val pass = field("Contraseña", cfg.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val chs = field("Nº de canales/cámaras", cfg.channels.toString(), InputType.TYPE_CLASS_NUMBER)
        val live = CheckBox(this).apply { text = "Video en vivo en el inicio"; isChecked = cfg.livePreview; setTextColor(Color.WHITE) }
        box.addView(live)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Configurar DVR Hikvision")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                cfg.userName = name.text.toString().trim(); cfg.host = host.text.toString()
                cfg.port = port.text.toString().toIntOrNull() ?: 554; cfg.user = user.text.toString()
                cfg.pass = pass.text.toString(); cfg.channels = chs.text.toString().toIntOrNull() ?: 4
                cfg.livePreview = live.isChecked
                rebuild()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------------ Fondos
    private fun nextWall() {
        if (walls.isEmpty()) return
        val f = walls[wIdx++ % walls.size]
        thread {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / s > 2200) s *= 2
            val bmp: Bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return@thread
            runOnUiThread {
                val show = if (frontA) bgB else bgA; val hide = if (frontA) bgA else bgB
                show.setImageBitmap(bmp)
                show.animate().alpha(1f).setDuration(1600).start()
                hide.animate().alpha(0f).setDuration(1600).start()
                frontA = !frontA
            }
        }
    }
}
