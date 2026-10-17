package app.hyprconnect

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object PresenterState { @Volatile var active = false }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Root()
            }
        }
    }

    // Presenter tab: volume keys = previous / next slide
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PresenterState.active) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { if (event.repeatCount == 0) Hub.key("left"); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { if (event.repeatCount == 0) Hub.key("right"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (PresenterState.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}

fun applyPairUri(raw: String): Boolean {
    val u = Uri.parse(raw.trim())
    if (u.scheme != "hyprconnect") return false
    val host = u.host ?: return false
    val fp = u.getQueryParameter("fp") ?: return false
    val token = u.getQueryParameter("token") ?: return false
    if (!Regex("[0-9a-f]{64}").matches(fp)) return false
    Hub.store.savePairing(host, if (u.port > 0) u.port else 1716, fp, token, u.getQueryParameter("name") ?: "PC")
    return true
}

private fun startLink(ctx: Context) =
    ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionService::class.java))

@Composable
fun Root() {
    val ctx = LocalContext.current
    var paired by remember { mutableStateOf(Hub.store.hasPairing) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(paired) { if (paired) startLink(ctx) }
    Surface(Modifier.fillMaxSize()) {
        if (!paired) PairScreen { paired = true }
        else MainScreen(onUnpair = {
            ctx.stopService(Intent(ctx, ConnectionService::class.java))
            Hub.unpair()
            paired = false
        })
    }
}

@Composable
fun PairScreen(onPaired: () -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun apply(s: String) { if (applyPairUri(s)) onPaired() else err = "That is not a HyprConnect pairing link" }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text("HyprConnect", style = MaterialTheme.typography.headlineLarge)
        Text("On your PC run:\n    hyprconnect pair\nthen scan the QR code (valid for 2 minutes).")
        Button(onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { apply(it.rawValue ?: "") }
                .addOnFailureListener { err = it.message }
        }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
        OutlinedTextField(
            value = manual, onValueChange = { manual = it }, singleLine = true,
            label = { Text("…or paste the hyprconnect:// link") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { apply(manual) }, enabled = manual.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Pair with link")
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private val TABS = listOf("🖱" to "Pad", "📽" to "Present", "🎵" to "Media", "🖥" to "Desktop", "⋯" to "More")

@Composable
fun MainScreen(onUnpair: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val conn by Hub.conn.collectAsState()
    LaunchedEffect(Unit) { Hub.toasts.collect { snack.showSnackbar(it) } }
    DisposableEffect(tab) {
        PresenterState.active = tab == 1
        onDispose { PresenterState.active = false }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { StatusBar(conn) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(emoji, fontSize = 20.sp) }, label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TouchpadTab()
                1 -> PresenterTab()
                2 -> MediaTab()
                3 -> DesktopTab()
                else -> MoreTab(onUnpair)
            }
        }
    }
}

@Composable
fun StatusBar(conn: Hub.Conn) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (txt, col) = when (conn) {
            is Hub.Conn.On -> "● Connected to ${Hub.store.pcName}" to Color(0xFF2E7D32)
            Hub.Conn.Connecting -> "… Connecting" to Color(0xFFF9A825)
            Hub.Conn.Off -> "○ Disconnected" to MaterialTheme.colorScheme.error
        }
        Text(txt, color = col, modifier = Modifier.weight(1f))
        if (conn == Hub.Conn.Off) TextButton(onClick = { startLink(ctx) }) { Text("Retry") }
    }
}

// ───────────────────────── Touchpad + keyboard

private suspend fun PointerInputScope.touchpad() = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val t0 = down.uptimeMillis
    var endMs = t0
    var moved = 0f
    var maxPointers = 1
    var accX = 0f; var accY = 0f; var scrollAcc = 0f
    do {
        val ev = awaitPointerEvent()
        endMs = ev.changes.first().uptimeMillis
        val pressed = ev.changes.filter { it.pressed }
        maxPointers = max(maxPointers, pressed.size)
        if (pressed.size >= 2) {
            val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
            moved += abs(dy)
            scrollAcc += dy
            val steps = (scrollAcc / 14f).toInt()
            if (steps != 0) { Hub.scroll(steps); scrollAcc -= steps * 14f }
        } else if (pressed.size == 1 && maxPointers == 1) {
            val c = pressed[0]
            val dx = c.position.x - c.previousPosition.x
            val dy = c.position.y - c.previousPosition.y
            moved += abs(dx) + abs(dy)
            val accel = 1.5f + min(hypot(dx, dy) / 18f, 2.5f)
            accX += dx * accel; accY += dy * accel
            val ix = accX.toInt(); val iy = accY.toInt()
            if (ix != 0 || iy != 0) { Hub.move(ix, iy); accX -= ix; accY -= iy }
        }
        ev.changes.forEach { it.consume() }
    } while (ev.changes.any { it.pressed })
    if (moved < 24f && endMs - t0 < 260) Hub.click(if (maxPointers >= 2) "right" else "left")
}

@Composable
fun TouchpadTab() {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) { touchpad() },
            contentAlignment = Alignment.Center
        ) {
            Text("1 finger: move / tap = click\n2 fingers: scroll / tap = right click",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Hub.click("left") }, modifier = Modifier.weight(1f)) { Text("Left") }
            OutlinedButton(onClick = { Hub.click("middle") }, modifier = Modifier.weight(1f)) { Text("Mid") }
            OutlinedButton(onClick = { Hub.click("right") }, modifier = Modifier.weight(1f)) { Text("Right") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("Type on PC") }, modifier = Modifier.weight(1f)
            )
            Button(onClick = { Hub.text(text); text = "" }, enabled = text.isNotEmpty()) { Text("Send") }
        }
        val keys = listOf("Esc" to "esc", "Tab" to "tab", "⌫" to "backspace", "⏎" to "enter")
        val arrows = listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right")
        for (row in listOf(keys, arrows)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, k) ->
                    OutlinedButton(
                        onClick = { Hub.key(k) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(label) }
                }
            }
        }
    }
}

// ───────────────────────── Presenter (slides + gyro pointer + timer)

@Composable
fun PresenterTab() {
    val ctx = LocalContext.current
    var secs by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var sens by remember { mutableFloatStateOf(1.5f) }
    val laser = remember { mutableStateOf(false) }
    var hasGyro by remember { mutableStateOf(true) }

    LaunchedEffect(running) { while (running) { delay(1000); secs++ } }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasGyro = gyro != null
        var ax = 0f; var ay = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!laser.value) { ax = 0f; ay = 0f; return }
                // phone held like a remote: yaw (z) = left/right, pitch (x) = up/down
                ax += -e.values[2] * 28f * sens
                ay += -e.values[0] * 28f * sens
                val dx = ax.toInt(); val dy = ay.toInt()
                if (dx != 0 || dy != 0) { Hub.move(dx, dy); ax -= dx; ay -= dy }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (gyro != null) sm.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("%02d:%02d".format(secs / 60, secs % 60), fontSize = 34.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { running = !running }) { Text(if (running) "Pause" else "Timer") }
            OutlinedButton(onClick = { running = false; secs = 0 }) { Text("Reset") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { Hub.key("left") }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text("◀  Prev", fontSize = 22.sp)
            }
            Button(onClick = { Hub.key("right") }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text("Next  ▶", fontSize = 22.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Hub.key("f5") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Start F5") }
            OutlinedButton(onClick = { Hub.key("esc") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("End Esc") }
            OutlinedButton(onClick = { Hub.key("b") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Black B") }
        }
        if (hasGyro) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { laser.value = true; tryAwaitRelease(); laser.value = false })
                    },
                contentAlignment = Alignment.Center
            ) { Text("Hold and tilt to move the pointer", color = MaterialTheme.colorScheme.onTertiaryContainer) }
            Text("Pointer speed")
            Slider(value = sens, onValueChange = { sens = it }, valueRange = 0.4f..4f)
        } else Text("No gyroscope on this phone: pointer mode unavailable.")
        Text("Volume keys = previous / next slide", style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────────────────── Media

@Composable
fun MediaTab() {
    @Composable
    fun RowScope.B(label: String, onClick: () -> Unit) =
        Button(onClick = onClick, modifier = Modifier.weight(1f).height(72.dp)) { Text(label, fontSize = 18.sp) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            B("⏮") { Hub.send("media", "action" to "prev") }
            B("⏯") { Hub.send("media", "action" to "play_pause") }
            B("⏭") { Hub.send("media", "action" to "next") }
        }
        Text("Volume", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            B("−") { Hub.send("volume", "action" to "down") }
            B("Mute") { Hub.send("volume", "action" to "mute") }
            B("+") { Hub.send("volume", "action" to "up") }
        }
        Text("Brightness", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            B("−") { Hub.send("brightness", "action" to "down") }
            B("+") { Hub.send("brightness", "action" to "up") }
        }
    }
}

// ───────────────────────── Hyprland: workspaces, windows, commands

@Composable
fun DesktopTab() {
    val ws by Hub.workspaces.collectAsState()
    val active by Hub.activeWs.collectAsState()
    val wins by Hub.windows.collectAsState()
    val cmds by Hub.commands.collectAsState()
    LaunchedEffect(Unit) { Hub.send("ws.list"); Hub.send("cmd.list") }
    LaunchedEffect(ws, active) { Hub.send("win.list") }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Workspaces", style = MaterialTheme.typography.titleMedium) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ws) { w ->
                    FilterChip(
                        selected = w.id == active,
                        onClick = { Hub.send("ws.switch", "id" to w.id) },
                        label = { Text("${w.id}  (${w.windows})") }
                    )
                }
            }
        }
        item { Text("Windows", style = MaterialTheme.typography.titleMedium) }
        items(wins) { w ->
            Card(Modifier.fillMaxWidth().clickable {
                Hub.send("win.focus", "address" to w.address)
            }) {
                Column(Modifier.padding(12.dp)) {
                    Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${w.cls} · workspace ${w.workspace}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Commands (defined in config.json on the PC)", style = MaterialTheme.typography.titleMedium) }
        items(cmds) { c ->
            OutlinedButton(onClick = { Hub.send("cmd.run", "name" to c) }, modifier = Modifier.fillMaxWidth()) { Text(c) }
        }
    }
}

// ───────────────────────── Files, clipboard, notifications, SMS, settings

@Composable
fun MoreTab(onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    val ringing by Hub.ringing.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.sendFiles(it) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        Hub.toast(if (r.values.all { it }) "SMS forwarding enabled" else "SMS permission denied")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (ringing) Button(
            onClick = { Hub.stopRinging() }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Stop ringing") }

        Text("Files", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Send files to PC") }
        Text("From the PC: hyprconnect send <file>. Received files go to Downloads/HyprConnect.", style = MaterialTheme.typography.bodySmall)

        Text("Clipboard", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (t.isNullOrEmpty()) Hub.toast("Phone clipboard is empty") else { Hub.send("clip.set", "text" to t); Hub.toast("Sent to PC") }
            }, modifier = Modifier.weight(1f)) { Text("Phone → PC") }
            OutlinedButton(onClick = { Hub.send("clip.get") }, modifier = Modifier.weight(1f)) { Text("PC → phone") }
        }

        Text("Notifications & SMS", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, modifier = Modifier.fillMaxWidth()) { Text("Mirror phone notifications (grant access)") }
        OutlinedButton(onClick = {
            smsPerm.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
        }, modifier = Modifier.fillMaxWidth()) { Text("Enable SMS (send from PC / forward incoming)") }
        Text("Send an SMS from the PC: hyprconnect sms <number> <text>", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onUnpair, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("Unpair this PC") }
    }
}
