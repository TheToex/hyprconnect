package app.hyprconnect

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

object MirrorManager {
    private const val TAG = "HyprMirror"
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var job: Job? = null
    val isMirroring = MutableStateFlow(false)

    fun start(ctx: Context, resultCode: Int, data: Intent) {
        if (isMirroring.value) return
        val appCtx = ctx.applicationContext

        try {
            // 1. Promote ConnectionService to FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before getMediaProjection
            val service = ConnectionService.instance
            if (service != null) {
                service.updateForeground(true)
            } else {
                val startIntent = Intent(appCtx, ConnectionService::class.java).apply {
                    action = ConnectionService.ACTION_START_MIRROR
                }
                ContextCompat.startForegroundService(appCtx, startIntent)
            }

            // 2. Obtain MediaProjection
            val mgr = appCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data) ?: run {
                Hub.toast("Screen capture permission not granted")
                stop()
                return
            }
            projection = proj

            // 3. Register MediaProjection.Callback (Strictly REQUIRED on Android 14+ prior to createVirtualDisplay)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stop()
                }
            }
            proj.registerCallback(callback, Handler(Looper.getMainLooper()))
            projectionCallback = callback

            // 4. Calculate safe dimensions (scaled for fast ~20 FPS JPEG transmission)
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val maxDim = 800
            val srcW = maxOf(100, metrics.widthPixels)
            val srcH = maxOf(100, metrics.heightPixels)
            val scale = if (maxOf(srcW, srcH) > maxDim) maxDim.toFloat() / maxOf(srcW, srcH) else 1f
            val w = maxOf(100, ((srcW * scale).toInt() / 2) * 2)
            val h = maxOf(100, ((srcH * scale).toInt() / 2) * 2)
            val dpi = maxOf(120, (metrics.densityDpi * scale).toInt())

            // 5. Dedicated background thread so image processing never lags the UI
            val thread = HandlerThread("HyprMirrorWorker").apply { start() }
            backgroundThread = thread
            val handler = Handler(thread.looper)
            backgroundHandler = handler

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            val vd = proj.createVirtualDisplay(
                "HyprConnectMirror",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            virtualDisplay = vd

            val title = "${Hub.store.deviceName} Screen"
            Hub.send("mirror.start", "width" to w, "height" to h, "title" to title)
            isMirroring.value = true

            var lastSent = 0L
            val frameIntervalMs = 50L // ~20 fps target
            var bitmap: Bitmap? = null

            reader.setOnImageAvailableListener({ ir ->
                if (!isMirroring.value) return@setOnImageAvailableListener
                val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastSent < frameIntervalMs) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastSent = now

                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * w

                    val rawW = w + rowPadding / pixelStride
                    if (bitmap == null || bitmap?.width != rawW || bitmap?.height != h) {
                        bitmap = Bitmap.createBitmap(rawW, h, Bitmap.Config.ARGB_8888)
                    }
                    bitmap?.copyPixelsFromBuffer(buffer)

                    val finalBmp = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap!!, 0, 0, w, h)
                    } else {
                        bitmap!!
                    }

                    val baos = ByteArrayOutputStream()
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    val bytes = baos.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    Hub.send("mirror.frame", "data" to b64)
                } catch (e: Exception) {
                    // Frame drop or conversion error
                } finally {
                    image.close()
                }
            }, handler)

        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start screen mirror", t)
            Hub.toast("Screen share error: ${t.localizedMessage ?: t.message}")
            stop()
        }
    }

    fun stop() {
        if (!isMirroring.value && projection == null && virtualDisplay == null) return
        isMirroring.value = false
        job?.cancel()
        job = null

        runCatching { projectionCallback?.let { projection?.unregisterCallback(it) } }
        projectionCallback = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { backgroundThread?.quitSafely() }

        backgroundThread = null
        backgroundHandler = null
        virtualDisplay = null
        imageReader = null
        projection = null

        val service = ConnectionService.instance
        if (service != null) {
            service.updateForeground(false)
        } else {
            runCatching {
                val stopIntent = Intent(Hub.appContext, ConnectionService::class.java).apply {
                    action = ConnectionService.ACTION_STOP_MIRROR
                }
                ContextCompat.startForegroundService(Hub.appContext, stopIntent)
            }
        }
        Hub.send("mirror.stop")
    }
}
