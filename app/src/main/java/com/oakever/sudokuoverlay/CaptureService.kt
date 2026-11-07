package com.oakever.sudokuoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileOutputStream

class CaptureService : Service() {
    companion object {
        const val ACTION_CAPTURE = "com.oakever.sudokuoverlay.CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RECEIVER = "receiver"
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val KEY_PATH = "capture_path"
        const val KEY_ERROR = "capture_error"

        private const val CHANNEL_ID = "sudoku_capture"
        private const val NOTIFICATION_ID = 4101
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultReceiver: ResultReceiver? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var completed = false
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        resultReceiver = parcelableExtra(intent, EXTRA_RECEIVER, ResultReceiver::class.java)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = parcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            reportError("Screen-capture permission was not granted.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sudoku Overlay")
            .setContentText("Capturing the Sudoku screen…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                reportError("Android could not start screen capture.")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            beginOneShotCapture()
        } catch (security: SecurityException) {
            reportError("Android denied screen capture: ${security.message ?: "permission error"}")
            stopSelf(startId)
        } catch (error: Exception) {
            reportError("Capture failed: ${error.message ?: error.javaClass.simpleName}")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun <T : android.os.Parcelable> parcelableExtra(intent: Intent, key: String, type: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, type)
        else intent.getParcelableExtra(key) as? T
    }

    private fun beginOneShotCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        workerThread = HandlerThread("SudokuScreenCapture").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!completed) {
                    reportError("Android stopped the screen-capture session.")
                    finishCapture()
                }
            }
        }
        projection?.registerCallback(projectionCallback!!, workerHandler!!)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (completed) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                saveAndReturn(bitmap)
                if (bitmap !== padded) padded.recycle()
                bitmap.recycle()
            } catch (error: Exception) {
                reportError("Could not read the captured screen: ${error.message ?: "image error"}")
                finishCapture()
            } finally {
                image.close()
            }
        }, workerHandler)

        // Gives the user a moment to switch to the Oakever Sudoku game on Android versions
        // where the system picker captures the entire display instead of one app window.
        workerHandler?.postDelayed({
            if (completed) return@postDelayed
            try {
                virtualDisplay = projection?.createVirtualDisplay(
                    "Sudoku Overlay Capture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    workerHandler
                )
                workerHandler?.postDelayed({
                    if (!completed) {
                        reportError("No screen frame arrived. Please try Scan again.")
                        finishCapture()
                    }
                }, 6000L)
            } catch (error: Exception) {
                reportError("Could not create the screen capture: ${error.message ?: "capture error"}")
                finishCapture()
            }
        }, 3000L)
    }

    private fun saveAndReturn(bitmap: Bitmap) {
        if (completed) return
        try {
            val file = File(cacheDir, "sudoku_capture.png")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            completed = true
            val data = Bundle().apply { putString(KEY_PATH, file.absolutePath) }
            resultReceiver?.send(RESULT_OK, data)
        } catch (error: Exception) {
            reportError("Could not save the screenshot: ${error.message ?: "file error"}")
        } finally {
            finishCapture()
        }
    }

    private fun reportError(message: String) {
        if (completed) return
        completed = true
        val data = Bundle().apply { putString(KEY_ERROR, message) }
        resultReceiver?.send(RESULT_ERROR, data)
    }

    private fun finishCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try {
            projectionCallback?.let { callback -> projection?.unregisterCallback(callback) }
            projection?.stop()
        } catch (_: Exception) { }
        projectionCallback = null
        projection = null
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sudoku screen capture",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (!completed && resultReceiver != null) {
            reportError("Screen capture stopped.")
        }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        workerThread?.quitSafely()
        super.onDestroy()
    }
}
