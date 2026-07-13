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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.routedns.routebot.common.RouteBotLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val KEYSTORE_ALIAS = "routebot_media_key"

data class CapturedMedia(val file: File, val contentType: String, val encrypted: Boolean = true)

@Singleton
class MediaCaptureHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    @Suppress("DEPRECATION")
    private var legacyCamera: android.hardware.Camera? = null

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

    @Suppress("DEPRECATION")
    private fun startRecording(durationSec: Int, video: Boolean): File? {
        return try {
            val output = createTempFile(if (video) "video" else "audio", if (video) ".mp4" else ".m4a")
            recordingFile = output
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            if (video) {
                // MediaRecorder.VideoSource.CAMERA requires an app-owned, unlocked Camera
                // instance handed off explicitly — without this the recorder has no actual
                // capture surface and recording silently fails or produces an empty file.
                val camera = android.hardware.Camera.open()
                camera.unlock()
                legacyCamera = camera
                recorder.setCamera(camera)
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
            releaseLegacyCamera()
            null
        }
    }

    fun stopRecording(): CapturedMedia? {
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            releaseLegacyCamera()
            val raw = recordingFile ?: return null
            recordingFile = null
            val ext = if (raw.extension == "mp4") "video/mp4" else "audio/mp4"
            val encrypted = encryptFile(raw)
            CapturedMedia(encrypted, ext)
        } catch (e: Exception) {
            RouteBotLog.e("recording_stop_failed", throwable = e)
            releaseLegacyCamera()
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseLegacyCamera() {
        try {
            legacyCamera?.lock()
            legacyCamera?.release()
        } catch (_: Exception) {
            // Best effort — camera may already be in an unexpected state after a failure.
        } finally {
            legacyCamera = null
        }
    }

    fun deleteAfterUpload(file: File) {
        runCatching { file.delete() }
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        val dir = File(context.cacheDir, "media_capture").apply { mkdirs() }
        return File.createTempFile(prefix, suffix, dir)
    }

    /**
     * Encrypts [source] with AES-GCM using a hardware-backed Android Keystore key that never
     * leaves the keystore and is never written to disk. Only the (non-secret) IV is stored,
     * as a 12-byte header in front of the ciphertext, matching standard AES-GCM practice.
     */
    private fun encryptFile(source: File): File {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val input = source.readBytes()
        val encrypted = cipher.doFinal(input)
        val dest = File(source.parent, source.name + ".enc")
        dest.outputStream().use { out ->
            out.write(iv)
            out.write(encrypted)
        }
        source.delete()
        return dest
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }
}
