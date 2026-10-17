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
        get() = p.getInt("port", 5716)
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

    var deviceName: String
        get() = p.getString("devicename", android.os.Build.MODEL ?: "Android Phone") ?: "Android Phone"
        set(v) = p.edit().putString("devicename", v).apply()

    var pointerSens: Float
        get() = p.getFloat("pointer_sens", 1.5f)
        set(v) = p.edit().putFloat("pointer_sens", v).apply()

    var gyroSens: Float
        get() = p.getFloat("gyro_sens", 1.6f)
        set(v) = p.edit().putFloat("gyro_sens", v).apply()

    var hapticsEnabled: Boolean
        get() = p.getBoolean("haptics_enabled", true)
        set(v) = p.edit().putBoolean("haptics_enabled", v).apply()

    val hasPairing: Boolean get() = fp != null && (secret != null || token != null)

    fun savePairing(host: String, port: Int, fp: String, token: String, name: String) {
        p.edit().putString("host", host).putInt("port", port).putString("fp", fp)
            .putString("token", token).remove("secret").putString("pcname", name).apply()
    }

    fun clear() {
        p.edit().remove("host").remove("port").remove("fp").remove("token").remove("secret").apply()
    }
}
