package app.hyprconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class Ws(val id: Int, val windows: Int)
data class Win(val address: String, val title: String, val cls: String, val workspace: Int)

/** Owns the TLS link to the PC, the protocol, and everything the phone does on the PC's behalf. */
object Hub {
    private const val TAG = "HyprConnect"
    private const val CHUNK = 48 * 1024

    sealed interface Conn {
        data object Off : Conn
        data object Connecting : Conn
        data class On(val host: String) : Conn
    }

    val conn = MutableStateFlow<Conn>(Conn.Off)
    val workspaces = MutableStateFlow<List<Ws>>(emptyList())
    val activeWs = MutableStateFlow(0)
    val windows = MutableStateFlow<List<Win>>(emptyList())
    val commands = MutableStateFlow<List<String>>(emptyList())
    val ringing = MutableStateFlow(false)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val screenFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val brightness = mutableFloatStateOf(100f)

    lateinit var store: Store
        private set
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val out = Channel<JSONObject>(64)
    private var job: Job? = null
    private var ringtone: Ringtone? = null
    private var lastBattery: Pair<Int, Boolean>? = null

    private val motionLock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingScroll = 0
    private var pendingLaserDx = 0
    private var pendingLaserDy = 0

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        store = Store(app)
    }

    fun start() {
        if (!store.hasPairing || job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        conn.value = Conn.Off
    }

    fun unpair() {
        stop()
        store.clear()
        workspaces.value = emptyList(); windows.value = emptyList(); commands.value = emptyList()
    }

    fun toast(s: String) { toasts.tryEmit(s) }

    // ---- sending helpers
    fun send(o: JSONObject) { out.trySend(o) }
    private suspend fun sendAwait(o: JSONObject) = out.send(o)
    fun send(t: String, vararg kv: Pair<String, Any>) {
        val o = JSONObject().put("t", t)
        kv.forEach { o.put(it.first, it.second) }
        send(o)
    }

    /** Smooth coalesced pointer movement (80Hz delivery without packet drops) */
    fun move(dx: Int, dy: Int) {
        synchronized(motionLock) {
            pendingDx += dx
            pendingDy += dy
        }
    }

    /** Smooth coalesced wheel scrolling */
    fun scroll(dy: Int) {
        synchronized(motionLock) {
            pendingScroll += dy
        }
    }

    fun laserStart() = send("laser.start")
    fun laserMove(dx: Int, dy: Int) {
        synchronized(motionLock) {
            pendingLaserDx += dx
            pendingLaserDy += dy
        }
    }
    fun laserStop() = send("laser.stop")

    fun setBrightness(pct: Int) = send("brightness.set", "percent" to pct)
    fun getBrightness() = send("brightness.get")
    fun requestScreenCapture() = send("screen.capture")

    fun click(button: String = "left", double: Boolean = false) =
        send("mouse.click", "button" to button, "double" to double)
    fun key(k: String, vararg mods: String) =
        send(JSONObject().put("t", "key").put("key", k).put("mods", org.json.JSONArray(mods.toList())))
    fun text(s: String) = send("text", "text" to s)
    fun char(c: Char) = text(c.toString())

    // ---- connection loop
    private suspend fun loop() {
        var backoff = 1000L
        while (currentCoroutineContext().isActive) {
            conn.value = Conn.Connecting
            try {
                session()
                backoff = 1000
            } catch (e: CancellationException) {
                throw e
            } catch (e: Rejected) {
                Log.w(TAG, "rejected: ${e.message}")
                toast("PC rejected the connection: ${e.message}")
                conn.value = Conn.Off
                return
            } catch (e: Exception) {
                Log.w(TAG, "link error: $e")
                store.fp?.let { fp ->
                    Discovery.find(app, fp)?.let { (h, p) -> store.host = h; store.port = p }
                }
            }
            conn.value = Conn.Off
            delay(backoff)
            backoff = minOf(backoff * 2, 15000)
        }
    }

    private class Rejected(msg: String) : Exception(msg)

    private fun tlsSocket(host: String, port: Int, fp: String): SSLSocket {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, a: String) {
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (got != fp) throw CertificateException("certificate fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        val raw = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), 5000) }
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 0
        s.tcpNoDelay = true
        s.startHandshake()
        return s
    }

    private suspend fun session() = coroutineScope {
        val host = store.host ?: throw IllegalStateException("no host")
        val fp = store.fp ?: throw IllegalStateException("no fingerprint")
        val sock = tlsSocket(host, store.port, fp)
        launch { try { awaitCancellation() } finally { runCatching { sock.close() } } }

        val rd = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
        val os = sock.outputStream
        fun w(o: JSONObject) { synchronized(os) { os.write((o.toString() + "\n").toByteArray()); os.flush() } }

        val hello = JSONObject().put("t", "hello").put("id", store.deviceId)
            .put("name", store.deviceName)
        store.secret?.let { hello.put("secret", it) } ?: hello.put("token", store.token ?: "")
        w(hello)

        val first = JSONObject(rd.readLine() ?: throw java.io.IOException("closed during handshake"))
        if (first.getString("t") == "error") throw Rejected(first.optString("msg"))
        if (first.has("secret")) {
            store.secret = first.getString("secret")
            store.token = null
            first.optString("host").takeIf { it.isNotEmpty() }?.let { store.pcName = it }
        }

        while (out.tryReceive().isSuccess) { /* drop stale events */ }
        conn.value = Conn.On(host)
        lastBattery = null

        val writer = launch { for (m in out) w(m) }

        // Dedicated coalescing motion loop at ~200Hz (5ms) for ultra-low latency
        val motionWriter = launch {
            while (isActive) {
                var dx = 0
                var dy = 0
                var sc = 0
                var ldx = 0
                var ldy = 0
                synchronized(motionLock) {
                    dx = pendingDx
                    dy = pendingDy
                    sc = pendingScroll
                    ldx = pendingLaserDx
                    ldy = pendingLaserDy
                    pendingDx = 0
                    pendingDy = 0
                    pendingScroll = 0
                    pendingLaserDx = 0
                    pendingLaserDy = 0
                }
                if (dx != 0 || dy != 0) {
                    w(JSONObject().put("t", "mouse.move").put("dx", dx).put("dy", dy))
                }
                if (sc != 0) {
                    w(JSONObject().put("t", "mouse.scroll").put("dy", sc))
                }
                if (ldx != 0 || ldy != 0) {
                    w(JSONObject().put("t", "laser.move").put("dx", ldx).put("dy", ldy))
                }
                delay(5)
            }
        }

        send("cmd.list")
        send("brightness.get")
        try {
            while (true) {
                val line = rd.readLine() ?: break
                runCatching { handle(JSONObject(line)) }.onFailure { Log.w(TAG, "bad msg: $it") }
            }
        } finally {
            writer.cancel()
            motionWriter.cancel()
            synchronized(motionLock) {
                pendingDx = 0
                pendingDy = 0
                pendingScroll = 0
                pendingLaserDx = 0
                pendingLaserDy = 0
            }
            cancelAllReceives()
            runCatching { sock.close() }
            coroutineContext.cancelChildren()
        }
    }

    // ---- incoming messages
    private fun handle(m: JSONObject) {
        when (m.getString("t")) {
            "ws.state" -> {
                activeWs.value = m.optInt("active")
                val a = m.optJSONArray("workspaces")
                workspaces.value = (0 until (a?.length() ?: 0)).map {
                    val w = a!!.getJSONObject(it); Ws(w.optInt("id"), w.optInt("windows"))
                }
            }
            "win.list" -> {
                val a = m.optJSONArray("windows")
                windows.value = (0 until (a?.length() ?: 0)).map {
                    val w = a!!.getJSONObject(it)
                    Win(w.optString("address"), w.optString("title"), w.optString("class"), w.optInt("workspace"))
                }.filter { it.title.isNotBlank() }
            }
            "cmd.list" -> {
                val a = m.optJSONArray("names")
                commands.value = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            }
            "clip" -> main.post {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PC", m.optString("text")))
                toast("Clipboard from PC copied")
            }
            "ring" -> ring()
            "sms.send" -> sendSms(m.optString("to"), m.optString("body"))
            "file.offer" -> rxOffer(m)
            "file.chunk" -> rxChunk(m)
            "file.end" -> rxEnd(m)
            "file.reject" -> toast("PC refused the file")
            "screen.frame" -> {
                val data = m.optString("data")
                if (data.isNotEmpty()) {
                    runCatching {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            screenFrame.value = bmp
                        }
                    }
                }
            }
            "brightness.status" -> {
                brightness.floatValue = m.optInt("percent", 100).toFloat()
            }
            "mirror.stopped" -> {
                MirrorManager.stop()
            }
        }
    }

    // ---- find my phone
    private fun ring() {
        main.post {
            runCatching {
                ringtone?.stop()
                val r = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                r.isLooping = true
                r.play()
                ringtone = r
                ringing.value = true
                main.postDelayed({ stopRinging() }, 30_000)
            }
        }
    }

    fun stopRinging() {
        main.post { runCatching { ringtone?.stop() }; ringtone = null; ringing.value = false }
    }

    // ---- SMS (phone is the modem)
    private fun sendSms(to: String, body: String) {
        if (to.isBlank() || body.isBlank()) return
        if (app.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("SMS permission not granted on the phone"); return
        }
        runCatching {
            val sm = app.getSystemService(SmsManager::class.java)
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(body), null, null)
            toast("SMS sent to $to")
        }.onFailure { toast("SMS failed: ${it.message}") }
    }

    // ---- battery
    fun sendBattery(intent: Intent) {
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        if (level < 0) return
        val pct = level * 100 / scale
        val chg = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        if (lastBattery == pct to chg || conn.value !is Conn.On) return
        lastBattery = pct to chg
        send("battery", "pct" to pct, "charging" to chg)
    }

    // ---- files: phone -> PC
    fun sendFiles(uris: List<Uri>) {
        scope.launch { uris.forEach { runCatching { sendUri(it) }.onFailure { e -> toast("Send failed: ${e.message}") } } }
    }

    private suspend fun sendUri(uri: Uri) {
        var name = "file"; var size = -1L
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        if (size < 0) throw IllegalStateException("unknown file size")
        if (conn.value !is Conn.On) throw IllegalStateException("not connected")
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sendAwait(JSONObject().put("t", "file.offer").put("id", id).put("name", name).put("size", size))
        app.contentResolver.openInputStream(uri)!!.use { ins ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                sendAwait(JSONObject().put("t", "file.chunk").put("id", id)
                    .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
            }
        }
        sendAwait(JSONObject().put("t", "file.end").put("id", id))
        toast("Sent $name")
    }

    // ---- files: PC -> phone (saved to Downloads/HyprConnect)
    private class Rx(val uri: Uri, val os: OutputStream, val size: Long, val name: String, var got: Long = 0)
    private val rx = HashMap<String, Rx>()

    private fun rxOffer(m: JSONObject) {
        val id = m.getString("id")
        val name = m.optString("name", "file").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trimStart('.').ifEmpty { "file" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/HyprConnect")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
        val os = app.contentResolver.openOutputStream(uri) ?: return
        synchronized(rx) { rx[id] = Rx(uri, os, m.optLong("size"), name) }
    }

    private fun rxChunk(m: JSONObject) {
        val r = synchronized(rx) { rx[m.getString("id")] } ?: return
        val b = Base64.decode(m.getString("data"), Base64.DEFAULT)
        r.got += b.size
        r.os.write(b)
    }

    private fun rxEnd(m: JSONObject) {
        val r = synchronized(rx) { rx.remove(m.getString("id")) } ?: return
        runCatching { r.os.close() }
        if (r.got == r.size) {
            val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            app.contentResolver.update(r.uri, cv, null, null)
            toast("Received ${r.name} (Downloads/HyprConnect)")
        } else {
            app.contentResolver.delete(r.uri, null, null)
            toast("Transfer of ${r.name} was incomplete")
        }
    }

    private fun cancelAllReceives() {
        val all = synchronized(rx) { rx.values.toList().also { rx.clear() } }
        all.forEach { runCatching { it.os.close(); app.contentResolver.delete(it.uri, null, null) } }
    }
}
