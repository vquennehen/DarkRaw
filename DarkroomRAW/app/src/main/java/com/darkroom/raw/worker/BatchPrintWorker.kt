// File: app/src/main/java/com/darkroom/raw/worker/BatchPrintWorker.kt
package com.darkroom.raw.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.darkroom.raw.data.PresetConverters
import com.darkroom.raw.data.RawNativeBridge
import com.darkroom.raw.gl.RawGlRenderer
import com.darkroom.raw.model.DevelopParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BatchPrintWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val uriStrings = inputData.getStringArray(KEY_URI_ARRAY) ?: return@withContext Result.failure()
        val paramsJsonArray = inputData.getStringArray(KEY_PARAMS_JSON_ARRAY) ?: return@withContext Result.failure()
        val exportAsTiff16 = inputData.getBoolean(KEY_EXPORT_TIFF16, false)
        val jpegQuality = inputData.getInt(KEY_JPEG_QUALITY, 95).coerceIn(90, 100)

        val converter = PresetConverters()
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val total = uriStrings.size

        setForeground(createForegroundInfo(0, total))

        // Initialize headless EGL 1.4 OpenGL ES 3.0 context for background GPU grading
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val renderer = RawGlRenderer()
        renderer.onSurfaceCreated(null, null)

        try {
            for (i in uriStrings.indices) {
                if (isStopped) break

                // Thermal guard for Galaxy S22 Snapdragon 8 Gen 1 sustained workloads
                if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                    delay(1800L) // Cool-down pacing between 24MP+ RAW frames
                }

                val sourceUri = Uri.parse(uriStrings[i])
                val params: DevelopParams = converter.toParams(paramsJsonArray[i])
                val meta = IntArray(6)

                val pfd = applicationContext.contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: continue
                val rawDirectBuffer = pfd.use {
                    RawNativeBridge.decodeRawFromFd(it.fd, false, meta)
                } ?: continue

                val width = meta[0]
                val height = meta[1]

                // Allocate offscreen FBO (GL_RGBA16F for 16-bit TIFF or GL_RGBA8 for JPEG)
                val fboIds = IntArray(1)
                val fboTexIds = IntArray(1)
                GLES30.glGenFramebuffers(1, fboIds, 0)
                GLES30.glGenTextures(1, fboTexIds, 0)

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexIds[0])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    0,
                    if (exportAsTiff16) GLES30.GL_RGBA16F else GLES30.GL_RGBA8,
                    width,
                    height,
                    0,
                    GLES30.GL_RGBA,
                    if (exportAsTiff16) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE,
                    null
                )

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D,
                    fboTexIds[0],
                    0
                )

                renderer.onSurfaceChanged(null, width, height)
                renderer.setRawImageBuffer(rawDirectBuffer, width, height)
                renderer.updateParams(params)
                renderer.onDrawFrame(null)

                // Immediately release native LibRaw buffer to prevent OOM
                RawNativeBridge.freeNativeBuffer(rawDirectBuffer)

                // Export via MediaStore to Pictures/DarkroomRAW
                val ext = if (exportAsTiff16) "tiff" else "jpg"
                val mime = if (exportAsTiff16) "image/tiff" else "image/jpeg"
                val displayName = "DR_${System.currentTimeMillis()}_$i.$ext"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DarkroomRAW")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val outUri = applicationContext.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )

                if (outUri != null) {
                    if (exportAsTiff16) {
                        val floatReadBuffer = ByteBuffer.allocateDirect(width * height * 8)
                            .order(ByteOrder.nativeOrder())
                        GLES30.glReadPixels(
                            0, 0, width, height,
                            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, floatReadBuffer
                        )
                        applicationContext.contentResolver.openFileDescriptor(outUri, "w")?.use { outPfd ->
                            RawNativeBridge.write16BitTiffToFd(outPfd.fd, floatReadBuffer, width, height)
                        }
                    } else {
                        val byteReadBuffer = ByteBuffer.allocateDirect(width * height * 4)
                            .order(ByteOrder.nativeOrder())
                        GLES30.glReadPixels(
                            0, 0, width, height,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, byteReadBuffer
                        )
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(byteReadBuffer)

                        val tempFile = File(applicationContext.cacheDir, "temp_export_$i.jpg")
                        tempFile.outputStream().use { outStream ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outStream)
                        }
                        bmp.recycle()

                        // Preserve original .CR2 / .DNG / .NEF / .ARW EXIF tags
                        copyRawExifToOutput(sourceUri, tempFile)

                        applicationContext.contentResolver.openOutputStream(outUri)?.use { finalOut ->
                            tempFile.inputStream().use { it.copyTo(finalOut) }
                        }
                        tempFile.delete()
                    }

                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    applicationContext.contentResolver.update(outUri, values, null, null)
                }

                GLES30.glDeleteFramebuffers(1, fboIds, 0)
                GLES30.glDeleteTextures(1, fboTexIds, 0)

                setProgress(workDataOf(KEY_PROGRESS_DONE to (i + 1), KEY_PROGRESS_TOTAL to total))
                setForeground(createForegroundInfo(i + 1, total))
            }
        } finally {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        Result.success()
    }

    private fun copyRawExifToOutput(sourceRawUri: Uri, targetFile: File) {
        val tagsToPreserve = arrayOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_WHITE_BALANCE
        )
        applicationContext.contentResolver.openInputStream(sourceRawUri)?.use { rawStream ->
            val srcExif = ExifInterface(rawStream)
            val dstExif = ExifInterface(targetFile)
            for (tag in tagsToPreserve) {
                srcExif.getAttribute(tag)?.let { value ->
                    dstExif.setAttribute(tag, value)
                }
            }
            dstExif.saveAttributes()
        }
    }

    private fun createForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val channelId = "darkroom_batch_channel"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Darkroom Batch Print", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Developing RAW Roll ($done / $total)")
            .setContentText("16-bit GPU Darkroom Pipeline active")
            .setOngoing(true)
            .setProgress(total, done, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(1001, notification)
        }
    }

    companion object {
        const val KEY_URI_ARRAY = "key_uri_array"
        const val KEY_PARAMS_JSON_ARRAY = "key_params_json_array"
        const val KEY_EXPORT_TIFF16 = "key_export_tiff16"
        const val KEY_JPEG_QUALITY = "key_jpeg_quality"
        const val KEY_PROGRESS_DONE = "key_progress_done"
        const val KEY_PROGRESS_TOTAL = "key_progress_total"
    }
}
