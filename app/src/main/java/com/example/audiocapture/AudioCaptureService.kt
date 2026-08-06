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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
 * Supports pause/resume: while paused, audio is still read from the source
 * (to avoid overflowing the internal buffer) but discarded rather than written,
 * so the saved file only contains the parts you actually wanted.
 *
 * Requires: MediaProjection permission grant (passed in as result data from
 * a screen-capture-permission style prompt), Android 10 (API 29)+.
 *
 * Note: apps can opt themselves out of capture (AudioAttributes.ALLOW_CAPTURE_BY_NONE),
 * in which case their audio will be silent in the recording. This is an OS-level
 * restriction, not something any app can bypass. Likewise, the one-time system
 * "record entire screen or a single app" consent prompt cannot be skipped by any
 * app — it's an Android privacy requirement, not a bug. It only appears once per
 * recording session; pause/resume during that session never re-triggers it.
 */
class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_capture_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.example.audiocapture.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.example.audiocapture.TOGGLE_PAUSE"

        // Broadcasts so the UI (app + bubble) reflects what actually happened.
        const val ACTION_RECORDING_STARTED = "com.example.audiocapture.RECORDING_STARTED"
        const val ACTION_RECORDING_FAILED = "com.example.audiocapture.RECORDING_FAILED"
        const val ACTION_RECORDING_SAVED = "com.example.audiocapture.RECORDING_SAVED"
        const val ACTION_TIMER_TICK = "com.example.audiocapture.TIMER_TICK"
        const val ACTION_PAUSE_STATE = "com.example.audiocapture.PAUSE_STATE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_PAUSED = "paused"

        const val SAMPLE_RATE = 48000
        const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    private var recordStartElapsedRealtime = 0L
    private var pausedAccumMillis = 0L
    private var pauseStartedAt = 0L

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                sendBroadcast(
                    Intent(ACTION_TIMER_TICK).setPackage(packageName)
                        .putExtra(EXTRA_ELAPSED_MS, currentElapsedMillis())
                        .putExtra(EXTRA_PAUSED, isPaused)
                )
                tickHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                togglePause()
                return START_STICKY
            }
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

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        } catch (e: SecurityException) {
            broadcastFailure("Microphone permission wasn't granted — can't record")
            stopSelf()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            broadcastFailure("Recording couldn't start on this device")
            stopSelf()
            return
        }

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
            broadcastFailure("Couldn't create the output file")
            stopSelf()
            return
        }

        isRecording = true
        isPaused = false
        recordStartElapsedRealtime = SystemClock.elapsedRealtime()
        pausedAccumMillis = 0L

        audioRecord?.startRecording()
        sendBroadcast(Intent(ACTION_RECORDING_STARTED).setPackage(packageName))
        tickHandler.post(tickRunnable)

        recordingThread = Thread {
            writeAudioDataToWavFile(bufferSize)
        }
        recordingThread?.start()
    }

    private fun togglePause() {
        if (!isRecording) return
        isPaused = !isPaused
        if (isPaused) {
            pauseStartedAt = SystemClock.elapsedRealtime()
        } else {
            pausedAccumMillis += SystemClock.elapsedRealtime() - pauseStartedAt
        }
        updateNotification()
        sendBroadcast(
            Intent(ACTION_PAUSE_STATE).setPackage(packageName).putExtra(EXTRA_PAUSED, isPaused)
        )
    }

    private fun currentElapsedMillis(): Long {
        val now = SystemClock.elapsedRealtime()
        val pausedSoFar = pausedAccumMillis + if (isPaused) now - pauseStartedAt else 0L
        return (now - recordStartElapsedRealtime - pausedSoFar).coerceAtLeast(0L)
    }

    private fun broadcastFailure(message: String) {
        sendBroadcast(
            Intent(ACTION_RECORDING_FAILED).setPackage(packageName).putExtra(EXTRA_MESSAGE, message)
        )
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
            // Keep draining the source even while paused (avoids buffer overflow glitches
            // on resume) — but only persist bytes when NOT paused, so pausing genuinely
            // skips the parts you don't want instead of just muting them.
            if (read > 0 && !isPaused) {
                fos.write(data, 0, read)
                totalBytes += read
            }
        }
        fos.flush()

        writeWavHeader(pfd, totalBytes)
        pfd.close()
        outputPfd = null

        if (totalBytes == 0L) {
            // Nothing was actually captured — delete the empty entry and report failure honestly.
            contentResolver.delete(uri, null, null)
            broadcastFailure("No audio was captured — nothing was playing, or the source app blocks recording")
            return
        }

        // Clear IS_PENDING so the file becomes visible/playable outside this app.
        val doneValues = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, doneValues, null, null)

        val fileName = MediaStore.Audio.Media.DISPLAY_NAME.let { column ->
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } ?: "recording"
        sendBroadcast(
            Intent(ACTION_RECORDING_SAVED).setPackage(packageName).putExtra(EXTRA_MESSAGE, fileName)
        )
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
        isPaused = false
        tickHandler.removeCallbacks(tickRunnable)
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio Capture", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, AudioCaptureService::class.java).apply { action = ACTION_TOGGLE_PAUSE }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Stop", stopPendingIntent)
            .addAction(0, if (isPaused) "Resume" else "Pause", pausePendingIntent)
            .setOngoing(true)

        if (isPaused) {
            builder.setContentTitle("Recording paused")
                .setContentText("${formatElapsed(currentElapsedMillis())} captured so far")
                .setUsesChronometer(false)
        } else {
            builder.setContentTitle("Recording internal audio")
                .setContentText("Tap Stop when you're done")
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - currentElapsedMillis())
        }

        return builder.build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
