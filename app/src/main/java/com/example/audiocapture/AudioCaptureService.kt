package com.example.audiocapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures device internal/playback audio (audio from other apps) directly,
 * at full source quality: 48kHz, 16-bit PCM, stereo, saved as uncompressed WAV
 * into the phone's public Music/AudioCapture folder via MediaStore, so it's
 * visible in any file manager or music app and survives app uninstall.
 *
 * Requires: MediaProjection permission grant (passed in as result data from
 * MainActivity's screen-capture-permission style prompt), Android 10 (API 29)+.
 *
 * Note: apps can opt themselves out of capture (AudioAttributes.ALLOW_CAPTURE_BY_NONE),
 * in which case their audio will be silent in the recording. This is an OS-level
 * restriction, not something any app can bypass.
 */
class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_capture_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.example.audiocapture.STOP"

        const val SAMPLE_RATE = 48000
        const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        startCapture()

        return START_STICKY
    }

    private fun startCapture() {
        val projection = mediaProjection ?: return

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufferSize = minBufSize * 4

        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNELS)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "capture_$timestamp.wav"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/AudioCapture")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        outputUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)

        if (outputUri == null) {
            stopSelf()
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            writeAudioDataToWavFile(bufferSize)
        }
        recordingThread?.start()
    }

    private fun writeAudioDataToWavFile(bufferSize: Int) {
        val uri = outputUri ?: return
        val pfd = contentResolver.openFileDescriptor(uri, "rw") ?: return
        outputPfd = pfd

        val data = ByteArray(bufferSize)
        val fos = FileOutputStream(pfd.fileDescriptor)

        // Write placeholder header (44 bytes), filled in properly at the end.
        fos.write(ByteArray(44))

        var totalBytes = 0L
        val record = audioRecord ?: return

        while (isRecording) {
            val read = record.read(data, 0, data.size)
            if (read > 0) {
                fos.write(data, 0, read)
                totalBytes += read
            }
        }
        fos.flush()

        writeWavHeader(pfd, totalBytes)
        pfd.close()
        outputPfd = null

        // Clear IS_PENDING so the file becomes visible/playable outside this app.
        val doneValues = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, doneValues, null, null)
    }

    private fun writeWavHeader(pfd: ParcelFileDescriptor, totalAudioLen: Long) {
        val channels = 2
        val byteRate = SAMPLE_RATE * channels * 2
        val totalDataLen = totalAudioLen + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat = PCM
        header.putShort(channels.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort()) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(totalAudioLen.toInt())

        header.flip()
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        channel.position(0)
        channel.write(header)
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording internal audio")
            .setContentText("Tap to stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
