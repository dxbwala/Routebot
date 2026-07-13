package com.routedns.routebot.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.routedns.routebot.common.RouteBotLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class CapturedMedia(val file: File, val contentType: String, val encrypted: Boolean = true)

@Singleton
class MediaCaptureHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    fun captureScreenshot(projection: MediaProjection?, width: Int, height: Int, density: Int): CapturedMedia? {
        if (projection == null) return null
        return try {
            val imageReader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val display = projection.createVirtualDisplay(
                "routebot_screenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, Handler(Looper.getMainLooper())
            )
            Thread.sleep(300)
            val image = imageReader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            display.release()
            imageReader.close()

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val raw = createTempFile("screenshot", ".png")
            FileOutputStream(raw).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            val encrypted = encryptFile(raw)
            CapturedMedia(encrypted, "image/png")
        } catch (e: Exception) {
            RouteBotLog.e("screenshot_failed", throwable = e)
            null
        }
    }

    fun startAudioRecording(durationSec: Int): File? = startRecording(durationSec, video = false)
    fun startVideoRecording(durationSec: Int): File? = startRecording(durationSec, video = true)

    private fun startRecording(durationSec: Int, video: Boolean): File? {
        return try {
            val output = createTempFile(if (video) "video" else "audio", if (video) ".mp4" else ".m4a")
            recordingFile = output
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (video) {
                recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(640, 480)
                recorder.setVideoFrameRate(15)
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            }
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(output.absolutePath)
            recorder.setMaxDuration(durationSec * 1000)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            output
        } catch (e: Exception) {
            RouteBotLog.e("recording_start_failed", throwable = e)
            null
        }
    }

    fun stopRecording(): CapturedMedia? {
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            val raw = recordingFile ?: return null
            recordingFile = null
            val ext = if (raw.extension == "mp4") "video/mp4" else "audio/mp4"
            val encrypted = encryptFile(raw)
            CapturedMedia(encrypted, ext)
        } catch (e: Exception) {
            RouteBotLog.e("recording_stop_failed", throwable = e)
            null
        }
    }

    fun deleteAfterUpload(file: File) {
        runCatching { file.delete() }
        runCatching { File(file.absolutePath + ".meta").delete() }
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        val dir = File(context.cacheDir, "media_capture").apply { mkdirs() }
        return File.createTempFile(prefix, suffix, dir)
    }

    private fun encryptFile(source: File): File {
        val key = generateAesKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val input = source.readBytes()
        val encrypted = cipher.doFinal(input)
        val dest = File(source.parent, source.name + ".enc")
        dest.outputStream().use { out ->
            out.write(iv)
            out.write(encrypted)
        }
        File(dest.absolutePath + ".meta").writeText(
            android.util.Base64.encodeToString(key.encoded, android.util.Base64.NO_WRAP)
        )
        source.delete()
        return dest
    }

    private fun generateAesKey(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }
}
