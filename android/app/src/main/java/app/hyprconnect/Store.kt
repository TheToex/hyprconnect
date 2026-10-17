package app.hyprconnect

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PairedDevice(
    val fp: String,
    val name: String,
    val host: String,
    val port: Int,
    val secret: String? = null,
    val token: String? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

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

    fun getPairedDevices(): List<PairedDevice> {
        val raw = p.getString("paired_devices_json", null)
        if (raw.isNullOrBlank()) {
            val currentFp = fp
            if (currentFp != null) {
                val dev = PairedDevice(
                    fp = currentFp,
                    name = pcName,
                    host = host ?: "",
                    port = port,
                    secret = secret,
                    token = token
                )
                val list = listOf(dev)
                savePairedDevices(list)
                return list
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PairedDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PairedDevice(
                        fp = o.getString("fp"),
                        name = o.optString("name", "PC"),
                        host = o.optString("host", ""),
                        port = o.optInt("port", 5716),
                        secret = o.optString("secret").takeIf { it.isNotEmpty() },
                        token = o.optString("token").takeIf { it.isNotEmpty() },
                        lastSeen = o.optLong("lastSeen", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePairedDevices(list: List<PairedDevice>) {
        val arr = JSONArray()
        for (d in list) {
            val o = JSONObject()
                .put("fp", d.fp)
                .put("name", d.name)
                .put("host", d.host)
                .put("port", d.port)
                .put("lastSeen", d.lastSeen)
            d.secret?.let { o.put("secret", it) }
            d.token?.let { o.put("token", it) }
            arr.put(o)
        }
        p.edit().putString("paired_devices_json", arr.toString()).apply()
    }

    fun savePairing(host: String, port: Int, fp: String, token: String, name: String) {
        p.edit().putString("host", host).putInt("port", port).putString("fp", fp)
            .putString("token", token).remove("secret").putString("pcname", name).apply()

        val devices = getPairedDevices().toMutableList()
        val existingIdx = devices.indexOfFirst { it.fp == fp }
        val newDev = PairedDevice(
            fp = fp,
            name = name,
            host = host,
            port = port,
            secret = null,
            token = token,
            lastSeen = System.currentTimeMillis()
        )
        if (existingIdx >= 0) {
            devices[existingIdx] = newDev
        } else {
            devices.add(0, newDev)
        }
        savePairedDevices(devices)
    }

    fun updateCurrentDeviceSecret(newSecret: String, newName: String) {
        secret = newSecret
        pcName = newName
        val currentFp = fp ?: return
        val devices = getPairedDevices().toMutableList()
        val idx = devices.indexOfFirst { it.fp == currentFp }
        if (idx >= 0) {
            devices[idx] = devices[idx].copy(
                secret = newSecret,
                token = null,
                name = newName,
                lastSeen = System.currentTimeMillis()
            )
            savePairedDevices(devices)
        }
    }

    fun switchToDevice(d: PairedDevice) {
        val ed = p.edit()
            .putString("host", d.host)
            .putInt("port", d.port)
            .putString("fp", d.fp)
            .putString("pcname", d.name)
        if (d.secret != null) {
            ed.putString("secret", d.secret).remove("token")
        } else if (d.token != null) {
            ed.putString("token", d.token).remove("secret")
        } else {
            ed.remove("secret").remove("token")
        }
        ed.apply()
    }

    fun removePairedDevice(targetFp: String) {
        val devices = getPairedDevices().filterNot { it.fp == targetFp }
        savePairedDevices(devices)
        if (fp == targetFp) {
            if (devices.isNotEmpty()) {
                switchToDevice(devices.first())
            } else {
                clear()
            }
        }
    }

    fun clear() {
        p.edit().remove("host").remove("port").remove("fp").remove("token").remove("secret").apply()
    }
}
