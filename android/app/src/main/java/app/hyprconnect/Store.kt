package app.hyprconnect

import android.content.Context
import java.util.UUID

/** Pairing data. Lives in the app-private sandbox (not backed up). */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("hc", Context.MODE_PRIVATE)

    val deviceId: String
        get() = p.getString("id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("id", it).apply()
        }

    var host: String?
        get() = p.getString("host", null)
        set(v) = p.edit().putString("host", v).apply()
    var port: Int
        get() = p.getInt("port", 1716)
        set(v) = p.edit().putInt("port", v).apply()
    /** SHA-256 of the PC's certificate; every connection is pinned to it. */
    var fp: String?
        get() = p.getString("fp", null)
        set(v) = p.edit().putString("fp", v).apply()
    /** One-time pairing token from the QR (cleared after pairing). */
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var secret: String?
        get() = p.getString("secret", null)
        set(v) = p.edit().putString("secret", v).apply()
    var pcName: String
        get() = p.getString("pcname", "PC") ?: "PC"
        set(v) = p.edit().putString("pcname", v).apply()

    val hasPairing: Boolean get() = fp != null && (secret != null || token != null)

    fun savePairing(host: String, port: Int, fp: String, token: String, name: String) {
        p.edit().putString("host", host).putInt("port", port).putString("fp", fp)
            .putString("token", token).remove("secret").putString("pcname", name).apply()
    }

    fun clear() {
        p.edit().remove("host").remove("port").remove("fp").remove("token").remove("secret").apply()
    }
}
