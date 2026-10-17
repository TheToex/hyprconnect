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
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

object MirrorManager {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var job: Job? = null
    val isMirroring = MutableStateFlow(false)

    fun start(ctx: Context, resultCode: Int, data: Intent) {
        if (isMirroring.value) return
        val appCtx = ctx.applicationContext
        val service = ConnectionService.instance
        service?.updateForeground(true)

        val mgr = appCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: return
        projection = proj

        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Scale resolution down for fast, low-latency streaming (e.g., target 800px max dimension)
        val maxDim = 800
        val srcW = metrics.widthPixels
        val srcH = metrics.heightPixels
        val scale = if (maxOf(srcW, srcH) > maxDim) maxDim.toFloat() / maxOf(srcW, srcH) else 1f
        val w = ((srcW * scale).toInt() / 2) * 2
        val h = ((srcH * scale).toInt() / 2) * 2
        val dpi = (metrics.densityDpi * scale).toInt()

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val vd = proj.createVirtualDisplay(
            "HyprConnectMirror",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        virtualDisplay = vd

        val title = "${Hub.store.deviceName} Screen"
        Hub.send("mirror.start", "width" to w, "height" to h, "title" to title)
        isMirroring.value = true

        job = CoroutineScope(Dispatchers.Default).launch {
            var lastSent = 0L
            val frameIntervalMs = 50L // ~20 fps target
            var bitmap: Bitmap? = null

            reader.setOnImageAvailableListener({ ir ->
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
            }, null)
        }
    }

    fun stop() {
        if (!isMirroring.value) return
        job?.cancel()
        job = null
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        isMirroring.value = false
        ConnectionService.instance?.updateForeground(false)
        Hub.send("mirror.stop")
    }
}
