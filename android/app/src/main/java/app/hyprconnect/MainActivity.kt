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
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

// ───────────────────────── Hyprland Ice Blue (#89DCEB) & Catppuccin Mocha Palette
val HyprSky = Color(0xFF89DCEB)
val HyprSkyDim = Color(0xFF58B5C5)
val HyprSkyGlow = Color(0x3389DCEB)
val MochaBackground = Color(0xFF11111B)
val MochaMantle = Color(0xFF181825)
val MochaBase = Color(0xFF1E1E2E)
val MochaSurface0 = Color(0xFF313244)
val MochaSurface1 = Color(0xFF45475A)
val MochaSurface2 = Color(0xFF585B70)
val MochaText = Color(0xFFCDD6F4)
val MochaSubtext = Color(0xFFA6ADC8)
val MochaGreen = Color(0xFFA6E3A1)
val MochaYellow = Color(0xFFF9E2AF)
val MochaRed = Color(0xFFF38BA8)
val MochaPeach = Color(0xFFFAB387)
val MochaSapphire = Color(0xFF74C7EC)

val HyprColorScheme = darkColorScheme(
    primary = HyprSky,
    onPrimary = MochaBackground,
    primaryContainer = MochaSurface0,
    onPrimaryContainer = HyprSky,
    secondary = MochaSapphire,
    onSecondary = MochaBackground,
    secondaryContainer = MochaSurface0,
    onSecondaryContainer = MochaSapphire,
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

// ───────────────────────── Vector Hyprland Origami Logo (#89DCEB)
@Composable
fun HyprlandLogo(
    modifier: Modifier = Modifier,
    color: Color = HyprSky
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Left main wing
        val leftWing = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.16f, h * 0.82f)
            lineTo(w * 0.34f, h * 0.82f)
            lineTo(w * 0.50f, h * 0.46f)
            close()
        }
        // Right wing (subtle gradient depth)
        val rightWing = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.50f, h * 0.46f)
            lineTo(w * 0.66f, h * 0.82f)
            lineTo(w * 0.84f, h * 0.82f)
            close()
        }
        // Center inner origami facet
        val centerFacet = Path().apply {
            moveTo(w * 0.50f, h * 0.46f)
            lineTo(w * 0.38f, h * 0.72f)
            lineTo(w * 0.62f, h * 0.72f)
            close()
        }
        drawPath(leftWing, color = color)
        drawPath(rightWing, color = color.copy(alpha = 0.75f))
        drawPath(centerFacet, color = color.copy(alpha = 0.90f))
    }
}

// ───────────────────────── Haptic Feedback Engine
object Haptics {
    fun click(ctx: Context) {
        runCatching {
            if (!Hub.store.hapticsEnabled) return
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
                vib?.vibrate(25L)
            }
        }
    }

    fun warning(ctx: Context) {
        runCatching {
            if (!Hub.store.hapticsEnabled) return
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
            if (!Hub.store.hapticsEnabled) return
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

// ───────────────────────── Main Activity
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

    // Hardware volume keys advance slides during university presentations
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
    Hub.refreshPairedDevices()
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

// ───────────────────────── Pair Screen (First-time or Manual Pairing)
@Composable
fun PairScreen(onPaired: () -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun apply(s: String) {
        if (applyPairUri(s)) onPaired() else err = "Invalid hyprconnect pairing URL"
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HyprlandLogo(Modifier.size(64.dp), color = HyprSky)
        Spacer(Modifier.height(4.dp))
        Text(
            "HyprConnect",
            style = MaterialTheme.typography.headlineMedium,
            color = HyprSky,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Wayland Companion for Hyprland",
            color = MochaSubtext,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Run this command on your Linux PC:",
                    color = MochaText,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MochaMantle,
                    border = BorderStroke(1.dp, MochaSurface0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "hyprconnect pair",
                        color = HyprSky,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Then scan the terminal QR code or paste the link below (valid for 2 minutes).",
                    color = MochaSubtext,
                    style = MaterialTheme.typography.bodySmall
                )
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
            colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Scan QR Code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            singleLine = true,
            label = { Text("or paste hyprconnect:// link") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HyprSky,
                unfocusedBorderColor = MochaSurface0
            )
        )

        OutlinedButton(
            onClick = { apply(manual) },
            enabled = manual.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (manual.isNotBlank()) HyprSky else MochaSurface0)
        ) {
            Text("Connect with URL", color = if (manual.isNotBlank()) HyprSky else MochaSubtext)
        }

        err?.let { Text(it, color = MochaRed, style = MaterialTheme.typography.bodySmall) }
    }
}

// ───────────────────────── Navigation Tabs Definition & Unified Vector Icons
private val TABS = listOf(
    "Trackpad",
    "Presenter",
    "Media",
    "Hyprland",
    "Files"
)

@Composable
fun TabIcon(tab: Int, selected: Boolean) {
    val color = if (selected) HyprSky else MochaSubtext
    when (tab) {
        0 -> { // Trackpad
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val h = size.height
                // Rounded trackpad body
                drawRoundRect(
                    color = color,
                    size = androidx.compose.ui.geometry.Size(w, h * 0.85f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.08f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 1.8.dp.toPx())
                )
                // Bottom click separator
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.93f),
                    strokeWidth = 1.8.dp.toPx()
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(0f, h * 0.65f),
                    end = androidx.compose.ui.geometry.Offset(w, h * 0.65f),
                    strokeWidth = 1.8.dp.toPx()
                )
            }
        }
        1 -> { // Presenter (Slide Board / Projection screen)
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val h = size.height
                // Screen outline
                drawRoundRect(
                    color = color,
                    size = androidx.compose.ui.geometry.Size(w, h * 0.65f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(width = 1.8.dp.toPx())
                )
                // Play triangle inside screen
                val playPath = Path().apply {
                    moveTo(w * 0.40f, h * 0.18f)
                    lineTo(w * 0.65f, h * 0.325f)
                    lineTo(w * 0.40f, h * 0.47f)
                    close()
                }
                drawPath(playPath, color = color)
                // Stand pole and legs
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.65f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.88f),
                    strokeWidth = 1.8.dp.toPx()
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(w * 0.25f, h),
                    end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.88f),
                    strokeWidth = 1.8.dp.toPx()
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(w * 0.75f, h),
                    end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.88f),
                    strokeWidth = 1.8.dp.toPx()
                )
            }
        }
        2 -> { // Media (Music Note)
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val h = size.height
                // Note head
                drawCircle(
                    color = color,
                    radius = w * 0.22f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.72f)
                )
                // Stem
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.72f),
                    end = androidx.compose.ui.geometry.Offset(w * 0.50f, h * 0.15f),
                    strokeWidth = 2.dp.toPx()
                )
                // Flag / beam
                val flagPath = Path().apply {
                    moveTo(w * 0.50f, h * 0.15f)
                    cubicTo(w * 0.75f, h * 0.18f, w * 0.85f, h * 0.35f, w * 0.85f, h * 0.48f)
                    cubicTo(w * 0.75f, h * 0.36f, w * 0.62f, h * 0.32f, w * 0.50f, h * 0.32f)
                    close()
                }
                drawPath(flagPath, color = color)
            }
        }
        3 -> { // Hyprland (Origami logo)
            HyprlandLogo(Modifier.size(22.dp), color = color)
        }
        else -> { // Files (Folder)
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val h = size.height
                val folderPath = Path().apply {
                    moveTo(w * 0.05f, h * 0.25f)
                    lineTo(w * 0.40f, h * 0.25f)
                    lineTo(w * 0.52f, h * 0.38f)
                    lineTo(w * 0.95f, h * 0.38f)
                    lineTo(w * 0.95f, h * 0.85f)
                    lineTo(w * 0.05f, h * 0.85f)
                    close()
                }
                drawPath(folderPath, color = color, style = Stroke(width = 1.8.dp.toPx()))
            }
        }
    }
}

@Composable
fun DrawerItem(
    icon: String,
    label: String,
    textColor: Color = MochaText,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(14.dp))
            Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ───────────────────────── Main Screen with Sidebar (Drawer)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val conn by Hub.conn.collectAsState()
    val pairedDevices by Hub.pairedDevices.collectAsState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var showPairNewDialog by remember { mutableStateOf(false) }
    var deviceToDelete by remember { mutableStateOf<PairedDevice?>(null) }

    LaunchedEffect(Unit) { Hub.toasts.collect { snack.showSnackbar(it) } }
    DisposableEffect(tab) {
        PresenterState.active = tab == 1
        onDispose { PresenterState.active = false }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MochaMantle,
                modifier = Modifier.width(310.dp)
            ) {
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MochaBase)
                        .padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HyprlandLogo(Modifier.size(40.dp), color = HyprSky)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "HyprConnect",
                                color = HyprSky,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Hyprland Companion",
                                color = MochaSubtext,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Connection Status Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MochaMantle,
                        border = BorderStroke(1.dp, MochaSurface0),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dotColor = when (conn) {
                                is Hub.Conn.On -> MochaGreen
                                Hub.Conn.Connecting -> MochaYellow
                                Hub.Conn.Off -> MochaRed
                            }
                            val statusText = when (conn) {
                                is Hub.Conn.On -> Hub.store.pcName
                                Hub.Conn.Connecting -> "Connecting..."
                                Hub.Conn.Off -> "Offline"
                            }
                            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                statusText,
                                color = MochaText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(color = MochaSurface0)

                Spacer(Modifier.height(12.dp))

                // Section 1: Pairing & Connectivity
                Text(
                    "PAIRED DEVICES",
                    color = MochaSubtext,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )

                if (pairedDevices.isEmpty()) {
                    Text(
                        "No paired devices",
                        color = MochaSubtext,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                } else {
                    pairedDevices.forEach { dev ->
                        val isActive = dev.fp == Hub.store.fp
                        Surface(
                            onClick = {
                                if (!isActive) {
                                    scope.launch { drawerState.close() }
                                    Hub.switchDevice(dev)
                                    Hub.toast("Connecting to ${dev.name}...")
                                }
                            },
                            color = if (isActive) MochaBase else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💻", fontSize = 16.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            dev.name,
                                            color = if (isActive) HyprSky else MochaText,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isActive) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (conn is Hub.Conn.On) MochaGreen else MochaYellow)
                                            )
                                        }
                                    }
                                    Text(
                                        if (dev.host.isNotBlank()) dev.host else "Auto-discovery",
                                        color = MochaSubtext,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { deviceToDelete = dev },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", color = MochaSubtext, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                DrawerItem(
                    icon = "➕",
                    label = "Pair New Device",
                    onClick = {
                        scope.launch { drawerState.close() }
                        showPairNewDialog = true
                    }
                )

                if (Hub.store.hasPairing) {
                    DrawerItem(
                        icon = "✕",
                        label = "Unpair Current PC",
                        textColor = MochaRed,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showUnpairConfirm = true
                        }
                    )
                }

                HorizontalDivider(color = MochaSurface0, modifier = Modifier.padding(vertical = 8.dp))

                // Section 2: Preferences & Settings
                Text(
                    "PREFERENCES",
                    color = MochaSubtext,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )

                DrawerItem(
                    icon = "⚙",
                    label = "Settings",
                    onClick = {
                        scope.launch { drawerState.close() }
                        showSettingsDialog = true
                    }
                )

                HorizontalDivider(color = MochaSurface0, modifier = Modifier.padding(vertical = 8.dp))

                // Section 3: Information
                Text(
                    "SYSTEM",
                    color = MochaSubtext,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )

                DrawerItem(
                    icon = "ℹ",
                    label = "About HyprConnect",
                    onClick = {
                        scope.launch { drawerState.close() }
                        showAboutDialog = true
                    }
                )

                Spacer(Modifier.weight(1f))

                // Drawer Footer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "HyprConnect v0.1.0 (Beta)",
                        color = MochaSubtext,
                        fontSize = 11.sp
                    )
                    Text(
                        "Wayland / uinput Native",
                        color = HyprSkyDim,
                        fontSize = 10.sp
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MochaBackground,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HyprlandLogo(Modifier.size(24.dp), color = HyprSky)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "HyprConnect",
                                color = HyprSky,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", fontSize = 24.sp, color = MochaText)
                        }
                    },
                    actions = {
                        StatusPill(conn) { startLink(ctx) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MochaMantle
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MochaMantle,
                    tonalElevation = 4.dp
                ) {
                    TABS.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i; Haptics.click(ctx) },
                            icon = { TabIcon(i, tab == i) },
                            label = {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = HyprSky,
                                selectedTextColor = HyprSky,
                                indicatorColor = MochaSurface0,
                                unselectedTextColor = MochaSubtext,
                                unselectedIconColor = MochaSubtext
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> TrackpadTab()
                    1 -> PresenterTab()
                    2 -> MediaTab()
                    3 -> HyprlandTab()
                    else -> FilesTab()
                }
            }
        }
    }

    // Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(onDismiss = { showSettingsDialog = false })
    }

    // About Dialog
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // Unpair Confirmation Dialog
    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair PC?", fontWeight = FontWeight.Bold) },
            text = { Text("This will terminate the active connection and clear stored keys. You will need to pair again.") },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairConfirm = false
                        onUnpair()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MochaRed)
                ) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel", color = MochaSubtext) }
            },
            containerColor = MochaBase,
            textContentColor = MochaText,
            titleContentColor = MochaText
        )
    }

    // Device Removal Confirmation Dialog
    if (deviceToDelete != null) {
        val dev = deviceToDelete!!
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Remove Device?", fontWeight = FontWeight.Bold) },
            text = { Text("Remove '${dev.name}' from saved devices? You will need to pair it again to reconnect.") },
            confirmButton = {
                Button(
                    onClick = {
                        val targetFp = dev.fp
                        deviceToDelete = null
                        Hub.removeDevice(targetFp)
                        if (!Hub.store.hasPairing) {
                            onUnpair()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MochaRed)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) { Text("Cancel", color = MochaSubtext) }
            },
            containerColor = MochaBase,
            textContentColor = MochaText,
            titleContentColor = MochaText
        )
    }

    // Pair New Dialog
    if (showPairNewDialog) {
        var manualUrl by remember { mutableStateOf("") }
        var pairErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPairNewDialog = false },
            title = { Text("Pair New Device", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Scan the QR code from 'hyprconnect pair' or enter URL:",
                        color = MochaSubtext,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                                .addOnSuccessListener {
                                    if (applyPairUri(it.rawValue ?: "")) {
                                        showPairNewDialog = false
                                        Hub.stop()
                                        startLink(ctx)
                                        Hub.start()
                                    } else {
                                        pairErr = "Invalid QR code"
                                    }
                                }
                                .addOnFailureListener { pairErr = it.message }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Scan Terminal QR")
                    }
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        singleLine = true,
                        label = { Text("hyprconnect:// URL") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    pairErr?.let { Text(it, color = MochaRed, fontSize = 11.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (applyPairUri(manualUrl)) {
                            showPairNewDialog = false
                            Hub.stop()
                            startLink(ctx)
                            Hub.start()
                        } else {
                            pairErr = "Invalid pairing URL"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground)
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showPairNewDialog = false }) { Text("Cancel", color = MochaSubtext) }
            },
            containerColor = MochaBase,
            textContentColor = MochaText,
            titleContentColor = MochaText
        )
    }
}

// ───────────────────────── Top App Bar Status Pill
@Composable
fun StatusPill(conn: Hub.Conn, onReconnect: () -> Unit) {
    Row(
        Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MochaBase)
            .border(BorderStroke(1.dp, MochaSurface0), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (dotCol, label) = when (conn) {
            is Hub.Conn.On -> MochaGreen to Hub.store.pcName
            Hub.Conn.Connecting -> MochaYellow to "Connecting..."
            Hub.Conn.Off -> MochaRed to "Offline"
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotCol))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = MochaText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (conn == Hub.Conn.Off) {
            Spacer(Modifier.width(4.dp))
            Text(
                "Retry",
                color = HyprSky,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onReconnect() }
            )
        }
    }
}

// ───────────────────────── Settings Dialog
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var devName by remember { mutableStateOf(Hub.store.deviceName) }
    var trackSens by remember { mutableFloatStateOf(Hub.store.pointerSens) }
    var gyroSens by remember { mutableFloatStateOf(Hub.store.gyroSens) }
    var haptics by remember { mutableStateOf(Hub.store.hapticsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Device Name
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device Name (shown on PC):", fontSize = 12.sp, color = MochaSubtext)
                    OutlinedTextField(
                        value = devName,
                        onValueChange = { devName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HyprSky,
                            unfocusedBorderColor = MochaSurface0
                        )
                    )
                }

                HorizontalDivider(color = MochaSurface0)

                // Trackpad Sensitivity
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Trackpad Sensitivity", fontSize = 12.sp, color = MochaSubtext)
                        Text("%.1fx".format(trackSens), fontSize = 12.sp, color = HyprSky, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = trackSens,
                        onValueChange = { trackSens = it },
                        valueRange = 0.5f..3.0f,
                        colors = SliderDefaults.colors(thumbColor = HyprSky, activeTrackColor = HyprSky)
                    )
                }

                // Gyroscope Sensitivity
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Gyro Laser Sensitivity", fontSize = 12.sp, color = MochaSubtext)
                        Text("%.1fx".format(gyroSens), fontSize = 12.sp, color = HyprSky, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = gyroSens,
                        onValueChange = { gyroSens = it },
                        valueRange = 0.5f..3.5f,
                        colors = SliderDefaults.colors(thumbColor = HyprSky, activeTrackColor = HyprSky)
                    )
                }

                HorizontalDivider(color = MochaSurface0)

                // Haptic Feedback Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Haptic Feedback", color = MochaText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Vibrate on taps and slide advances", color = MochaSubtext, fontSize = 11.sp)
                    }
                    Switch(
                        checked = haptics,
                        onCheckedChange = { haptics = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MochaBackground,
                            checkedTrackColor = HyprSky
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    Hub.store.deviceName = devName.ifBlank { "Android Phone" }
                    Hub.store.pointerSens = trackSens
                    Hub.store.gyroSens = gyroSens
                    Hub.store.hapticsEnabled = haptics
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MochaSubtext) }
        },
        containerColor = MochaBase,
        titleContentColor = MochaText,
        textContentColor = MochaText
    )
}

// ───────────────────────── About Dialog
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HyprlandLogo(Modifier.size(28.dp), color = HyprSky)
                Spacer(Modifier.width(10.dp))
                Text("About HyprConnect", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "HyprConnect is a lightweight, Wayland-native KDE Connect alternative tailored specifically for Hyprland.",
                    fontSize = 13.sp,
                    color = MochaText
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MochaMantle,
                    border = BorderStroke(1.dp, MochaSurface0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Version: 0.1.0 Beta", color = HyprSky, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Protocol: uinput / hyprctl", color = MochaSubtext, fontSize = 11.sp)
                        Text("Host: ${Hub.store.host ?: "N/A"}:${Hub.store.port}", color = MochaSubtext, fontSize = 11.sp)
                        Text(
                            "Fingerprint: ${(Hub.store.fp ?: "None").take(16)}...",
                            color = MochaSubtext,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    "Features:\n• 80Hz Coalesced Mouse & Gyro Laser\n• Direct uinput live keyboard typing\n• University Presentation remote with timer\n• Hyprland Workspaces & Window switcher",
                    fontSize = 12.sp,
                    color = MochaSubtext
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground)
            ) { Text("Close") }
        },
        containerColor = MochaBase,
        titleContentColor = MochaText,
        textContentColor = MochaText
    )
}

// ───────────────────────── 1. Trackpad & Live Remote Typing Tab
private suspend fun PointerInputScope.trackpadGesture(sensitivity: Float) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val t0 = down.uptimeMillis
    var endMs = t0
    var moved = 0f
    var maxPointers = 1
    var accX = 0f; var accY = 0f; var scrollAcc = 0f
    do {
        val ev = awaitPointerEvent()
        endMs = ev.changes.first().uptimeMillis
        val pressed: List<PointerInputChange> = ev.changes.filter { it.pressed }
        maxPointers = maxOf(maxPointers, pressed.size)
        if (pressed.size >= 2) {
            val sumY = pressed.fold(0f) { acc, c -> acc + (c.position.y - c.previousPosition.y) }
            val dy = sumY / pressed.size.toFloat()
            moved += abs(dy)
            scrollAcc += dy
            val steps = (scrollAcc / 14f).toInt()
            if (steps != 0) {
                Hub.scroll(steps)
                scrollAcc -= steps * 14f
            }
        } else if (pressed.size == 1 && maxPointers == 1) {
            val c = pressed[0]
            val dx = c.position.x - c.previousPosition.x
            val dy = c.position.y - c.previousPosition.y
            moved += abs(dx) + abs(dy)
            accX += dx * sensitivity
            accY += dy * sensitivity
            val ix = accX.toInt()
            val iy = accY.toInt()
            if (ix != 0 || iy != 0) {
                Hub.move(ix, iy)
                accX -= ix
                accY -= iy
            }
        }
        ev.changes.forEach { it.consume() }
    } while (ev.changes.any { it.pressed })

    if (moved < 24f && endMs - t0 < 260) {
        Hub.click(if (maxPointers >= 2) "right" else "left")
    }
}

@Composable
fun TrackpadTab() {
    val ctx = LocalContext.current
    val sens = Hub.store.pointerSens
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardBuffer by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Virtual Touchpad Surface
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MochaBase)
                .border(BorderStroke(1.dp, MochaSurface0), RoundedCornerShape(20.dp))
                .pointerInput(sens) { trackpadGesture(sens) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🖱", fontSize = 32.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1 Finger: Move Pointer · Tap = Left Click",
                    color = MochaSubtext,
                    fontSize = 12.sp
                )
                Text(
                    "2 Fingers: Scroll · Tap = Right Click",
                    color = MochaSubtext,
                    fontSize = 12.sp
                )
            }
        }

        // Hardware mouse buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { Hub.click("left"); Haptics.click(ctx) },
                modifier = Modifier.weight(1.3f).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Left (L)", color = MochaText, fontWeight = FontWeight.SemiBold) }

            OutlinedButton(
                onClick = { Hub.click("middle"); Haptics.click(ctx) },
                modifier = Modifier.weight(0.9f).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Mid", color = MochaSubtext) }

            OutlinedButton(
                onClick = { Hub.click("right"); Haptics.click(ctx) },
                modifier = Modifier.weight(1.3f).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Right (R)", color = MochaText, fontWeight = FontWeight.SemiBold) }
        }

        // Quick Function / Modifier Keys
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { Hub.key("tab"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Tab ⇥", color = MochaText, fontSize = 11.sp) }

            OutlinedButton(
                onClick = { Hub.key("esc"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Esc", color = MochaText, fontSize = 11.sp) }

            OutlinedButton(
                onClick = { Hub.key("space"); Haptics.click(ctx) },
                modifier = Modifier.weight(1.1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Space", color = MochaText, fontSize = 11.sp) }

            OutlinedButton(
                onClick = { Hub.key("backspace"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("⌫", color = MochaText, fontSize = 12.sp) }
        }

        // Shortcut Chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Ctrl+C" to ("c" to "ctrl"), "Ctrl+V" to ("v" to "ctrl"), "Ctrl+Z" to ("z" to "ctrl"), "Ctrl+A" to ("a" to "ctrl")).forEach { (label, keyMod) ->
                OutlinedButton(
                    onClick = { Hub.key(keyMod.first, keyMod.second); Haptics.click(ctx) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text(label, color = MochaText, fontSize = 11.sp) }
            }
        }

        // Navigation Arrows
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

        // Hidden input to capture soft keyboard typing and forward live to laptop
        BasicTextField(
            value = keyboardBuffer,
            onValueChange = { next ->
                if (next.length > keyboardBuffer.length) {
                    val added = next.substring(keyboardBuffer.length)
                    Hub.text(added)
                } else if (next.length < keyboardBuffer.length) {
                    val deleted = keyboardBuffer.length - next.length
                    repeat(deleted) { Hub.key("backspace") }
                }
                keyboardBuffer = if (next.length > 50) next.takeLast(10) else next
            },
            modifier = Modifier.size(1.dp).focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                Hub.key("enter")
                Haptics.click(ctx)
            })
        )

        // Bottom Controls: Keyboard soft trigger + Blue Enter Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                    Haptics.click(ctx)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MochaSurface0, contentColor = MochaText),
                border = BorderStroke(1.dp, MochaSurface1)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌨", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Keyboard", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = {
                    Hub.key("enter")
                    Haptics.click(ctx)
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HyprSky, contentColor = MochaBackground)
            ) {
                Text("Enter ↵", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ───────────────────────── 2. Presenter Mode (University Defense & Seminars)
@Composable
fun PresenterTab() {
    val ctx = LocalContext.current
    var elapsedSecs by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var targetSecs by remember { mutableIntStateOf(0) } // Default Free (count-up)
    var warned2Min by remember { mutableStateOf(false) }
    var warnedOvertime by remember { mutableStateOf(false) }

    val laser = remember { mutableStateOf(false) }
    var hasGyro by remember { mutableStateOf(false) }
    var sens by remember { mutableFloatStateOf(Hub.store.gyroSens) }

    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            elapsedSecs++
            if (targetSecs > 0) {
                val rem = targetSecs - elapsedSecs
                if (rem in 1..120 && !warned2Min) {
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
                val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (!laser.value) { ax = 0f; ay = 0f; return }
                val yaw = -e.values[2]
                val pitch = -e.values[0]
                // Deadband noise filter (< 0.02 rad/s ignored to kill jitter)
                if ((yaw * yaw + pitch * pitch) < 0.0004f) return

                ax += yaw * 45f * sens
                ay += pitch * 45f * sens
                val dx = ax.toInt()
                val dy = ay.toInt()
                if (dx != 0 || dy != 0) {
                    Hub.laserMove(dx, dy)
                    ax -= dx
                    ay -= dy
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (gyro != null) {
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            sm.unregisterListener(listener)
            if (laser.value) {
                laser.value = false
                Hub.laserStop()
            }
        }
    }

    val remaining = if (targetSecs > 0) targetSecs - elapsedSecs else elapsedSecs
    val timerColor = when {
        targetSecs > 0 && remaining < 0 -> MochaRed
        targetSecs > 0 && remaining <= 120 -> MochaYellow
        else -> MochaGreen
    }

    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                        Text(
                            if (running) "Pause" else "Start",
                            color = if (running) MochaYellow else MochaGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = {
                            running = false
                            elapsedSecs = 0
                            warned2Min = false
                            warnedOvertime = false
                            Haptics.click(ctx)
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) {
                        Text("Reset", color = MochaSubtext, fontSize = 12.sp)
                    }
                }

                // Preset selector chips for university defense / seminars
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Free", 600 to "10 min", 900 to "15 min", 1200 to "20 min").forEach { (sec, label) ->
                        FilterChip(
                            selected = targetSecs == sec,
                            onClick = {
                                targetSecs = sec
                                elapsedSecs = 0
                                warned2Min = false
                                warnedOvertime = false
                                Haptics.click(ctx)
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MochaSurface1,
                                selectedLabelColor = HyprSky
                            )
                        )
                    }
                }
            }
        }

        val prevInteractionSource = remember { MutableInteractionSource() }
        val isPrevPressed by prevInteractionSource.collectIsPressedAsState()

        val nextInteractionSource = remember { MutableInteractionSource() }
        val isNextPressed by nextInteractionSource.collectIsPressedAsState()

        // Giant Presentation Slide Buttons (Equal size for easy blind tapping)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Hub.key("left"); Haptics.click(ctx) },
                interactionSource = prevInteractionSource,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPrevPressed) HyprSky else MochaSurface0,
                    contentColor = if (isPrevPressed) MochaBackground else MochaText
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◀", fontSize = 32.sp, color = if (isPrevPressed) MochaBackground else HyprSky)
                    Spacer(Modifier.height(4.dp))
                    Text("Prev Slide", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = { Hub.key("right"); Haptics.click(ctx) },
                interactionSource = nextInteractionSource,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isNextPressed) HyprSky else MochaSurface0,
                    contentColor = if (isNextPressed) MochaBackground else MochaText
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", fontSize = 32.sp, color = if (isNextPressed) MochaBackground else HyprSky)
                    Spacer(Modifier.height(4.dp))
                    Text("Next Slide", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
            ) { Text("Start (F5)", color = MochaGreen, fontSize = 12.sp) }

            OutlinedButton(
                onClick = { Hub.key("b"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Black (B)", color = MochaPeach, fontSize = 12.sp) }

            OutlinedButton(
                onClick = { Hub.key("esc"); Haptics.click(ctx) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MochaSurface0)
            ) { Text("Exit (Esc)", color = MochaRed, fontSize = 12.sp) }
        }

        // Dedicated Wayland Presentation Laser Pointer (Decoupled from mouse cursor)
        if (hasGyro) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (laser.value) HyprSky else MochaBase)
                    .border(BorderStroke(1.5.dp, if (laser.value) HyprSky else MochaSurface0), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            laser.value = true
                            Hub.laserStart()
                            Haptics.click(ctx)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            laser.value = false
                            Hub.laserStop()
                            Haptics.click(ctx)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (laser.value) "Laser Active · Tilt Phone to Aim" else "Presentation Laser (Hold to Aim)",
                    color = if (laser.value) MochaBackground else MochaText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        } else {
            Text("No gyroscope sensor found on this phone.", color = MochaSubtext, fontSize = 11.sp)
        }

        Text(
            "💡 Tip: Phone hardware volume buttons advance slides forward/backward.",
            color = MochaSubtext,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ───────────────────────── 3. Media Controls
@Composable
fun MediaTab() {
    val ctx = LocalContext.current
    @Composable
    fun B(
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
    ) =
        Button(
            onClick = { onClick(); Haptics.click(ctx) },
            modifier = modifier.height(54.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MochaBase, contentColor = MochaText),
            border = BorderStroke(1.dp, MochaSurface0)
        ) {
            Text(
                label,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Media Playback", color = HyprSky, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    B("Prev", { Hub.send("media", "action" to "prev") }, Modifier.weight(1f))
                    B("Play / Pause", { Hub.send("media", "action" to "play_pause") }, Modifier.weight(1.5f), fontSize = 13.sp)
                    B("Next", { Hub.send("media", "action" to "next") }, Modifier.weight(1f))
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
                Text("System Volume", color = MochaSapphire, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    B("− Down", { Hub.send("volume", "action" to "down") }, Modifier.weight(1f))
                    B("Mute", { Hub.send("volume", "action" to "mute") }, Modifier.weight(1f))
                    B("+ Up", { Hub.send("volume", "action" to "up") }, Modifier.weight(1f))
                }
            }
        }

        LaunchedEffect(Unit) { Hub.getBrightness() }
        val brightness = Hub.brightness.floatValue
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Screen Brightness", color = MochaYellow, fontWeight = FontWeight.Bold)
                    Text("${brightness.toInt()}%", color = MochaYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = brightness.coerceIn(1f, 100f),
                    onValueChange = {
                        Hub.brightness.floatValue = it
                        Hub.setBrightness(it.toInt())
                    },
                    valueRange = 1f..100f,
                    colors = SliderDefaults.colors(thumbColor = MochaYellow, activeTrackColor = MochaYellow)
                )
            }
        }
    }
}

// ───────────────────────── 4. Hyprland: Workspaces, Screen Share & Commands
@Composable
fun HyprlandTab() {
    val ctx = LocalContext.current
    val ws by Hub.workspaces.collectAsState()
    val active by Hub.activeWs.collectAsState()
    val cmds by Hub.commands.collectAsState()
    val isMirroring by MirrorManager.isMirroring.collectAsState()

    val mediaProjectionManager = remember {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }
    val mirrorLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        try {
            if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
                MirrorManager.start(ctx, res.resultCode, res.data!!)
            } else {
                Hub.toast("Screen share permission cancelled")
            }
        } catch (e: Exception) {
            Hub.toast("Screen mirror error: ${e.message}")
        }
    }

    LaunchedEffect(Unit) { Hub.send("ws.list"); Hub.send("cmd.list") }

    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Hyprland Workspaces", color = HyprSky, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..4).forEach { id ->
                    val isSelected = active == id
                    Button(
                        onClick = {
                            Hub.send("ws.switch", "id" to id)
                            Haptics.click(ctx)
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) HyprSky else MochaSurface0,
                            contentColor = if (isSelected) MochaBackground else MochaText
                        ),
                        border = BorderStroke(1.dp, if (isSelected) HyprSky else MochaSurface1)
                    ) {
                        Text(
                            id.toString(),
                            fontSize = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MochaBase),
                border = BorderStroke(1.dp, MochaSurface0),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Screen Mirroring to PC", color = MochaSapphire, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isMirroring) MochaGreen else MochaSurface2)
                        )
                    }

                    Text(
                        if (isMirroring)
                            "Live streaming phone screen to a floating window on your Hyprland desktop."
                        else
                            "Opens a floating window on your Hyprland desktop and mirrors your phone display live.",
                        color = MochaSubtext,
                        fontSize = 13.sp
                    )

                    Button(
                        onClick = {
                            if (isMirroring) {
                                MirrorManager.stop()
                            } else {
                                try {
                                    mirrorLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                } catch (e: Exception) {
                                    Hub.toast("Screen capture error: ${e.message}")
                                }
                            }
                            Haptics.click(ctx)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMirroring) MochaRed else HyprSky,
                            contentColor = MochaBackground
                        )
                    ) {
                        Text(
                            if (isMirroring) "Stop Sharing Screen" else "Share Phone Screen to Linux",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (cmds.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Custom Commands", color = MochaGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            items(cmds) { c ->
                OutlinedButton(
                    onClick = { Hub.send("cmd.run", "name" to c); Haptics.click(ctx) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) {
                    Text(c, color = MochaText)
                }
            }
        }
    }
}

// ───────────────────────── 5. Files, Clipboard, Ring & Integrations
@Composable
fun FilesTab() {
    val ctx = LocalContext.current
    val ringing by Hub.ringing.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Hub.sendFiles(it) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        Hub.toast(if (r.values.all { it }) "SMS permission enabled" else "SMS permission denied")
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
                Text("Silence Phone Alarm", fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val path = Path().apply {
                            moveTo(w * 0.05f, h * 0.25f)
                            lineTo(w * 0.40f, h * 0.25f)
                            lineTo(w * 0.52f, h * 0.38f)
                            lineTo(w * 0.95f, h * 0.38f)
                            lineTo(w * 0.95f, h * 0.85f)
                            lineTo(w * 0.05f, h * 0.85f)
                            close()
                        }
                        drawPath(path, color = HyprSky, style = Stroke(width = 1.8.dp.toPx()))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Send Files to Linux PC", color = HyprSky, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { pick.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MochaSurface0, contentColor = MochaText)
                ) {
                    Text("Choose Files to Send")
                }
                Text("Files received from PC are stored in Download/HyprConnect.", color = MochaSubtext, fontSize = 11.sp)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MochaBase),
            border = BorderStroke(1.dp, MochaSurface0),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        // Clipboard board
                        drawRoundRect(
                            color = MochaSapphire,
                            size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.85f),
                            topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.12f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            style = Stroke(width = 1.8.dp.toPx())
                        )
                        // Clip at top
                        drawRoundRect(
                            color = MochaSapphire,
                            size = androidx.compose.ui.geometry.Size(w * 0.35f, h * 0.18f),
                            topLeft = androidx.compose.ui.geometry.Offset(w * 0.325f, 0f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Two-Way Wayland Clipboard", color = MochaSapphire, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (t.isNullOrEmpty()) Hub.toast("Phone clipboard is empty") else {
                                Hub.send("clip.set", "text" to t)
                                Hub.toast("Pushed to PC clipboard")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) { Text("Phone → PC", color = MochaText, fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = { Hub.send("clip.get") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MochaSurface0)
                    ) { Text("PC → Phone", color = MochaText, fontSize = 12.sp) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp)) {
                        val w = size.width
                        val h = size.height
                        val bell = Path().apply {
                            moveTo(w * 0.5f, h * 0.15f)
                            cubicTo(w * 0.25f, h * 0.25f, w * 0.20f, h * 0.65f, w * 0.12f, h * 0.75f)
                            lineTo(w * 0.88f, h * 0.75f)
                            cubicTo(w * 0.80f, h * 0.65f, w * 0.75f, h * 0.25f, w * 0.5f, h * 0.15f)
                            close()
                        }
                        drawPath(bell, color = MochaGreen, style = Stroke(width = 1.8.dp.toPx()))
                        drawCircle(color = MochaGreen, radius = 2.dp.toPx(), center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.88f))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Notifications & SMS Integration", color = MochaGreen, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text("Enable Desktop Notification Mirroring", color = MochaText, fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { smsPerm.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MochaSurface0)
                ) { Text("Enable SMS Modem Service", color = MochaText, fontSize = 12.sp) }
            }
        }
    }
}
