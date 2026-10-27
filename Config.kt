package com.minimaltv.launcher

import android.content.Context
import android.net.Uri

/** Preferencias: datos del DVR Hikvision, nombre, favoritos. */
class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var userName: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()
    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v.trim()).apply()
    var port: Int
        get() = p.getInt("port", 554)
        set(v) = p.edit().putInt("port", v).apply()
    var user: String
        get() = p.getString("user", "admin") ?: "admin"
        set(v) = p.edit().putString("user", v).apply()
    var pass: String
        get() = p.getString("pass", "") ?: ""
        set(v) = p.edit().putString("pass", v).apply()
    var channels: Int
        get() = p.getInt("channels", 4)
        set(v) = p.edit().putInt("channels", v.coerceIn(1, 32)).apply()
    var livePreview: Boolean
        get() = p.getBoolean("live", true)
        set(v) = p.edit().putBoolean("live", v).apply()
    var favorites: MutableList<String>
        get() = (p.getString("favs", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
        set(v) = p.edit().putString("favs", v.joinToString(",")).apply()
    val hasFavs get() = p.contains("favs")

    val configured get() = host.isNotBlank()

    /**
     * Hikvision DVR (tambien analogicas HD-TVI/AHD conectadas al DVR):
     * canal N principal = N01, sub-stream = N02  (ej. canal 1 -> 101/102, canal 10 -> 1001/1002).
     */
    /** Rutas RTSP candidatas (Hikvision moderno, con parametro, y firmware antiguo). */
    fun rtspCandidates(ch: Int, main: Boolean): List<String> {
        val s = if (main) 1 else 2
        val base = "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$host:$port"
        return listOf(
            "$base/Streaming/Channels/${ch}0$s",
            "$base/Streaming/Channels/${ch}0$s?transportmode=unicast",
            "$base/h264/ch$ch/${if (main) "main" else "sub"}/av_stream"
        )
    }

    fun rtsp(ch: Int, main: Boolean) =
        "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@$host:$port/Streaming/Channels/${ch}0${if (main) 1 else 2}"
}
