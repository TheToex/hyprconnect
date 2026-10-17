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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// ───────────────────────── Hyprland / Catppuccin Mocha Theme
val MochaBackground = Color(0xFF11111B)
val MochaMantle = Color(0xFF181825)
val MochaBase = Color(0xFF1E1E2E)
val MochaSurface0 = Color(0xFF313244)
val MochaSurface1 = Color(0xFF45475A)
val MochaText = Color(0xFFCDD6F4)
val MochaSubtext = Color(0xFFA6ADC8)
val MochaMauve = Color(0xFFCBA6F7)
val MochaBlue = Color(0xFF89B4FA)
val MochaSapphire = Color(0xFF74C7EC)
val MochaGreen = Color(0xFFA6E3A1)
val MochaPeach = Color(0xFFFAB387)
val MochaYellow = Color(0xFFF9E2AF)
val MochaRed = Color(0xFFF38BA8)

val HyprColorScheme = darkColorScheme(
    primary = MochaMauve,
    onPrimary = MochaBackground,
    primaryContainer = MochaSurface0,
    onPrimaryContainer = MochaMauve,
    secondary = MochaBlue,
    onSecondary = MochaBackground,
    secondaryContainer = MochaSurface0,
    onSecondaryContainer = MochaBlue,
    tertiary = MochaGreen,
    onTertiary = MochaBackground,
    tertiaryContainer = MochaSurface0,
    onTertiaryContainer = MochaGreen,
    background = MochaBackground,
    onBackground = MochaText,
    surface = MochaBase,
    onSurface = MochaText,
    surfaceVariant = MochaSurface0,
    onSurfaceVariant = MochaSubtext,
    error = MochaRed,
    onError = MochaBackground
)

// ───────────────────────── Haptic Feedback for Presentations & Clicks
object Haptics {
    fun click(ctx: Context) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= 29) {
                vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(25)
            }
        }
    }

    fun warning(ctx: Context) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(longArrayOf(0, 100, 100, 100), -1)
            }
        }
    }

    fun overtime(ctx: Context) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150, 80, 250), -1))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(longArrayOf(0, 150, 80, 150, 80, 250), -1)
            }
        }
    }
}

object PresenterState { @Volatile var active = false }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        setContent {
            MaterialTheme(colorScheme = HyprColorScheme) {
                Root()
            }
        }
    }

    // Hardware volume buttons flip slides during university presentations
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PresenterState.active) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) {
                    Hub.key("left")
                    Haptics.click(this)
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    Hub.key("right")
                    Haptics.click(this)
                }
                return true
            }
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
    Hub.store.savePairing(host, if (u.port > 0) u.port else 5716, fp, token, u.getQueryParameter("name") ?: "PC")
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    fun apply(s: String) { if (applyPairUri(s)) onPaired() else err = "لینک جفت‌سازی نامعتبر است" }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MochaMauve))
                    Spacer(Modifier.width(8.dp))
                    Text("HyprConnect", style = MaterialTheme.typography.headlineMedium, color = MochaMauve, fontWeight = FontWeight.Bold)
                }
                Text("روی کامپیوتر دستور زیر را در ترمینال اجرا کنید:", color = MochaSubtext, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MochaMantle,
                    border = BorderStroke(1.dp, MochaSurface0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("hyprconnect pair", color = MochaGreen, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                }
                Text("سپس QR کد را اسکن کرده یا لینک را پیست کنید (اعتبار: ۲ دقیقه).", color = MochaSubtext, style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = {
                val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, opts).startScan()
                    .addOnSuccessListener { apply(it.rawValue ?: "") }
                    .addOnFailureListener { err = it.message }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MochaMauve, contentColor = MochaBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📷  اسکن QR Code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        OutlinedTextField(
            value = manual, onValueChange = { manual = it }, singleLine = true,
            label = { Text("یا لینک hyprconnect:// را وارد کنید") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MochaMauve,
                unfocusedBorderColor = MochaSurface0
            )
        )

        OutlinedButton(
            onClick = { apply(manual) },
            enabled = manual.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (manual.isNotBlank()) MochaBlue else MochaSurface0)
        ) {
            Text("اتصال با لینک", color = if (manual.isNotBlank()) MochaBlue else MochaSubtext)
        }

        err?.let { Text(it, color = MochaRed, style = MaterialTheme.typography.bodySmall) }
    }
}

private val TABS = listOf(
    "🖱" to "تاچ‌پد",
    "📽" to "ارائه",
    "🎵" to "مدیا",
    "🖥" to "Hyprland",
    "⋯" to "بیشتر"
)

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
        containerColor = MochaBackground,
        snackbarHost = { SnackbarHost(snack) },
        topBar = { StatusBar(conn) },
        bottomBar = {
            NavigationBar(
                containerColor = MochaMantle,
                tonalElevation = 4.dp
            ) {
                TABS.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(emoji, fontSize = 20.sp) },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MochaMauve,
                            selectedTextColor = MochaMauve,
                            indicatorColor = MochaSurface0,
                            unselectedTextColor = MochaSubtext
                        )
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
    Surface(
        color = MochaMantle,
        border = BorderStroke(0.dp, Color.Transparent),
        modifier = Modifier.fillMaxWidth().statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (txt, dotCol) = when (conn) {
                is Hub.Conn.On -> "متصل به ${Hub.store.pcName}" to MochaGreen
                Hub.Conn.Connecting -> "در حال اتصال…" to MochaYellow
                Hub.Conn.Off -> "قطع ارتباط" to MochaRed
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotCol))
            Spacer(Modifier.width(8.dp))
            Text(txt, color = MochaText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (conn == Hub.Conn.Off) {
                TextButton(onClick = { startLink(ctx) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("تلاش مجدد", color = MochaBlue, fontSize = 12.sp)
                }
            }
        }
    }
}

// ───────────────────────── 1. Touchpad & Remote Keyboard
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
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MochaBase)
                .border(BorderStroke(1.dp, MochaSurface0), RoundedCornerShape(20.dp))
                .pointerInput(Unit) { touchpad() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🖱", fontSize = 32.sp)
                Spacer(Modifier.height(6.dp))
                Text("۱ انگشت: حرکت نشانگر / تپ = کلیک چپ", color = MochaSubtext, fontSize = 12.sp)
                Text("۲ انگشت: اسکرول / تپ = کلیک راست", color = MochaSubtext, fontSize = 12.sp)
            }
        }

        // Mouse buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { Hub.click("left"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("چپ (L)", color = MochaText) }
            OutlinedButton(
                onClick = { Hub.click("middle"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("وسط (M)", color = MochaSubtext) }
            OutlinedButton(
                onClick = { Hub.click("right"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("راست (R)", color = MochaText) }
        }

        // Quick Hyprland shortcuts (Super, Ctrl+C, Ctrl+V, etc.)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { Hub.key("super"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaMauve)
            ) { Text("Super ❖", color = MochaMauve, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { Hub.key("c", "ctrl"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Ctrl+C", color = MochaText, fontSize = 11.sp) }
            OutlinedButton(
                onClick = { Hub.key("v", "ctrl"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Ctrl+V", color = MochaText, fontSize = 11.sp) }
            OutlinedButton(
                onClick = { Hub.key("esc"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Esc", color = MochaText, fontSize = 11.sp) }
        }

        // Arrow keys
        val arrows = listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            arrows.forEach { (label, k) ->
                OutlinedButton(
                    onClick = { Hub.key(k); Haptics.click(ctx) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text(label, color = MochaText, fontSize = 14.sp) }
            }
        }

        // Type text directly to PC
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("تایپ روی کامپیوتر (فارسی/انگلیسی)") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MochaMauve,
                    unfocusedBorderColor = MochaSurface0
                )
            )
            Button(
                onClick = { Hub.text(text); text = ""; Haptics.click(ctx) },
                enabled = text.isNotEmpty(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MochaMauve, contentColor = MochaBackground)
            ) { Text("ارسال") }
        }
    }
}

// ───────────────────────── 2. Presenter Mode (مخصوص ارائه دانشگاه و کنفرانس)
@Composable
fun PresenterTab() {
    val ctx = LocalContext.current
    var elapsedSecs by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var targetSecs by remember { mutableIntStateOf(900) } // Default 15 minutes for presentations
    var sens by remember { mutableFloatStateOf(1.6f) }
    val laser = remember { mutableStateOf(false) }
    var hasGyro by remember { mutableStateOf(true) }

    // Warning alert flags
    var warned2Min by remember { mutableStateOf(false) }
    var warnedOvertime by remember { mutableStateOf(false) }

    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            elapsedSecs++
            if (targetSecs > 0) {
                val rem = targetSecs - elapsedSecs
                if (rem == 120 && !warned2Min) {
                    warned2Min = true
                    Haptics.warning(ctx)
                }
                if (rem <= 0 && !warnedOvertime) {
                    warnedOvertime = true
                    Haptics.overtime(ctx)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasGyro = gyro != null
        var ax = 0f; var ay = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!laser.value) { ax = 0f; ay = 0f; return }
                // Remote tilt: yaw (z) = left/right, pitch (x) = up/down
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

    val remaining = if (targetSecs > 0) targetSecs - elapsedSecs else elapsedSecs
    val timerColor = when {
        targetSecs > 0 && remaining < 0 -> MochaRed
        targetSecs > 0 && remaining <= 120 -> MochaYellow
        else -> MochaGreen
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // University Presentation Timer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val formatted = if (targetSecs > 0 && remaining < 0) {
                        "- %02d:%02d".format(abs(remaining) / 60, abs(remaining) % 60)
                    } else {
                        "%02d:%02d".format(abs(remaining) / 60, abs(remaining) % 60)
                    }
                    Text(
                        formatted,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = timerColor,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { running = !running; Haptics.click(ctx) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (running) MochaYellow else MochaGreen)
                    ) {
                        Text(if (running) "توقف" else "شروع تایمر", color = if (running) MochaYellow else MochaGreen, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { running = false; elapsedSecs = 0; warned2Min = false; warnedOvertime = false; Haptics.click(ctx) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) {
                        Text("ریست", color = MochaSubtext, fontSize = 12.sp)
                    }
                }

                // Preset selector chips for university defense / seminars
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "آزاد", 600 to "۱۰ دقیقه", 900 to "۱۵ دقیقه", 1200 to "۲۰ دقیقه").forEach { (sec, label) ->
                        FilterChip(
                            selected = targetSecs == sec,
                            onClick = { targetSecs = sec; elapsedSecs = 0; warned2Min = false; warnedOvertime = false; Haptics.click(ctx) },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MochaSurface1,
                                selectedLabelColor = MochaMauve
                            )
                        )
                    }
                }
            }
        }

        // Giant Presentation Slide Buttons (Easy to press blindly while facing audience)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Hub.key("left"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MochaSurface0, contentColor = MochaText)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◀", fontSize = 32.sp, color = MochaBlue)
                    Spacer(Modifier.height(4.dp))
                    Text("اسلاید قبلی", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = { Hub.key("right"); Haptics.click(ctx) },
                modifier = Modifier.weight(1.3f).fillMaxHeight(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MochaMauve, contentColor = MochaBackground)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", fontSize = 36.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("اسلاید بعدی", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Presentation control buttons (F5, Blank, Esc)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { Hub.key("f5"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("شروع (F5)", color = MochaGreen, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { Hub.key("b"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("صفحه مشکی (B)", color = MochaPeach, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { Hub.key("esc"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("خروج (Esc)", color = MochaRed, fontSize = 12.sp) }
        }

        // Gyroscope Air Mouse / Laser Pointer
        if (hasGyro) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (laser.value) MochaMauve else MochaBase)
                    .border(BorderStroke(1.5.dp, if (laser.value) MochaMauve else MochaSurface0), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                laser.value = true
                                Haptics.click(ctx)
                                tryAwaitRelease()
                                laser.value = false
                                Haptics.click(ctx)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (laser.value) "نشانگر فعال است (گوشی را بچرخانید)" else "پوینتر هوایی (نگه‌دارید و حرکت دهید)",
                        color = if (laser.value) MochaBackground else MochaText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("سرعت پوینتر: ", color = MochaSubtext, fontSize = 11.sp)
                Slider(value = sens, onValueChange = { sens = it }, valueRange = 0.5f..3.5f, modifier = Modifier.weight(1f))
            }
        } else {
            Text("این گوشی فاقد سنسور ژیروسکوپ است؛ از دکمه‌های اسلاید استفاده کنید.", color = MochaSubtext, fontSize = 11.sp)
        }

        Text("💡 کلیدهای ولوم گوشی در حالت ارائه اسلاید را جابه‌جا می‌کنند.", color = MochaSubtext, style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────────────────── 3. Media Controls
@Composable
fun MediaTab() {
    val ctx = LocalContext.current
    @Composable
    fun B(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
        Button(
            onClick = { onClick(); Haptics.click(ctx) },
            modifier = modifier.height(60.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MochaBase, contentColor = MochaText),
            border = BorderStroke(1.dp, MochaSurface0)
        ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("کنترل پخش موسیقی (MPRIS / Playerctl)", color = MochaMauve, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    B("⏮ قبلی", { Hub.send("media", "action" to "prev") }, Modifier.weight(1f))
                    B("⏯ پخش / مکث", { Hub.send("media", "action" to "play_pause") }, Modifier.weight(1.2f))
                    B("⏭ بعدی", { Hub.send("media", "action" to "next") }, Modifier.weight(1f))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تنظیم ولوم سیستم (WirePlumber / wpctl)", color = MochaBlue, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    B("− کم کردن", { Hub.send("volume", "action" to "down") }, Modifier.weight(1f))
                    B("🔇 بی‌صدا", { Hub.send("volume", "action" to "mute") }, Modifier.weight(1f))
                    B("+ زیاد کردن", { Hub.send("volume", "action" to "up") }, Modifier.weight(1f))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("روشنایی مانیتور (Brightnessctl)", color = MochaYellow, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    B("🔅 کم کردن نور", { Hub.send("brightness", "action" to "down") }, Modifier.weight(1f))
                    B("🔆 افزایش نور", { Hub.send("brightness", "action" to "up") }, Modifier.weight(1f))
                }
            }
        }
    }
}

// ───────────────────────── 4. Hyprland: Workspaces, Windows & Quick Actions
@Composable
fun DesktopTab() {
    val ctx = LocalContext.current
    val ws by Hub.workspaces.collectAsState()
    val active by Hub.activeWs.collectAsState()
    val wins by Hub.windows.collectAsState()
    val cmds by Hub.commands.collectAsState()
    LaunchedEffect(Unit) { Hub.send("ws.list"); Hub.send("cmd.list") }
    LaunchedEffect(ws, active) { Hub.send("win.list") }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("ورک‌اسپیس‌های Hyprland", color = MochaMauve, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ws) { w ->
                    FilterChip(
                        selected = w.id == active,
                        onClick = { Hub.send("ws.switch", "id" to w.id); Haptics.click(ctx) },
                        label = {
                            Text(
                                "WS ${w.id} (${w.windows})",
                                fontWeight = if (w.id == active) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MochaMauve,
                            selectedLabelColor = MochaBackground
                        )
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("پنجره‌های باز (برای فوکوس لمس کنید)", color = MochaBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        if (wins.isEmpty()) {
            item {
                Text("پنجره‌ای یافت نشد", color = MochaSubtext, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(wins) { w ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    Hub.send("win.focus", "address" to w.address)
                    Haptics.click(ctx)
                },
                colors = CardDefaults.cardColors(containerColor = MochaBase),
                border = BorderStroke(1.dp, MochaSurface0),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MochaText, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text("${w.cls} · ورک‌اسپیس ${w.workspace}", style = MaterialTheme.typography.bodySmall, color = MochaSubtext)
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("دستورات سیستم و کلیدهای سریع", color = MochaGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        items(cmds) { c ->
            OutlinedButton(
                onClick = { Hub.send("cmd.run", "name" to c); Haptics.click(ctx) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) {
                Text("⚡  $c", color = MochaText)
            }
        }
    }
}

// ───────────────────────── 5. More (Files, Clipboard, Notifications, SMS)
@Composable
fun MoreTab(onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    val ringing by Hub.ringing.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.sendFiles(it) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        Hub.toast(if (r.values.all { it }) "دسترسی پیامک فعال شد" else "دسترسی پیامک رد شد")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (ringing) {
            Button(
                onClick = { Hub.stopRinging() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MochaRed, contentColor = MochaBackground),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🔔 قطع صدای زنگ گوشی", fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📁 ارسال فایل به کامپیوتر", color = MochaMauve, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { pick.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MochaSurface0, contentColor = MochaText)
                ) {
                    Text("انتخاب و ارسال فایل به Hyprland")
                }
                Text("فایل‌های دریافتی از کامپیوتر در پوشه Download/HyprConnect ذخیره می‌شوند.", color = MochaSubtext, fontSize = 11.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📋 کلیپ‌بورد دوطرفه (Wayland Clipboard)", color = MochaBlue, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (t.isNullOrEmpty()) Hub.toast("کلیپ‌بورد گوشی خالی است") else { Hub.send("clip.set", "text" to t); Hub.toast("به لینوکس ارسال شد") }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) { Text("گوشی → کامپیوتر", color = MochaText, fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = { Hub.send("clip.get") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) { Text("کامپیوتر → گوشی", color = MochaText, fontSize = 12.sp) }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔔 نوتیفیکیشن و پیامک", color = MochaGreen, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text("فعال‌سازی آینه نوتیفیکیشن‌ها روی دسکتاپ", color = MochaText, fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { smsPerm.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text("فعال‌سازی سرویس پیامک (ارسال از PC / فوروارد)", color = MochaText, fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onUnpair,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MochaRed),
            border = BorderStroke(1.dp, MochaRed),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("قطع اتصال و لغو جفت‌سازی (Unpair)")
        }
    }
}
