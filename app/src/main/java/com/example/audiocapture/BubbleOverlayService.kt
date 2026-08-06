package com.example.audiocapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import java.util.Locale

/**
 * Small draggable floating control (a "bubble") that stays on screen over any
 * app. Lets you start/stop and pause/resume a recording without switching back
 * to this app. Requires the "draw over other apps" permission, granted from
 * the main screen's toggle.
 */
class BubbleOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "bubble_channel"
        const val NOTIF_ID = 2
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var isRecording = false
    private var isPaused = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioCaptureService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    isPaused = false
                    updateBubbleUi()
                }
                AudioCaptureService.ACTION_RECORDING_FAILED,
                AudioCaptureService.ACTION_RECORDING_SAVED -> {
                    isRecording = false
                    isPaused = false
                    updateBubbleUi()
                }
                AudioCaptureService.ACTION_TIMER_TICK -> {
                    val ms = intent.getLongExtra(AudioCaptureService.EXTRA_ELAPSED_MS, 0L)
                    bubbleView?.findViewById<TextView>(R.id.bubbleTimer)?.text = formatMillis(ms)
                }
                AudioCaptureService.ACTION_PAUSE_STATE -> {
                    isPaused = intent.getBooleanExtra(AudioCaptureService.EXTRA_PAUSED, false)
                    updateBubbleUi()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildBubbleNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()

        val filter = IntentFilter().apply {
            addAction(AudioCaptureService.ACTION_RECORDING_STARTED)
            addAction(AudioCaptureService.ACTION_RECORDING_FAILED)
            addAction(AudioCaptureService.ACTION_RECORDING_SAVED)
            addAction(AudioCaptureService.ACTION_TIMER_TICK)
            addAction(AudioCaptureService.ACTION_PAUSE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun addBubble() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.bubble_layout, null)
        bubbleView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 0
        params.y = 300

        // Drag the whole bubble by its handle at the top.
        val dragHandle = view.findViewById<View>(R.id.bubbleDragHandle)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.bubbleRecordButton).setOnClickListener {
            if (isRecording) {
                startService(Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP
                })
            } else {
                startActivity(
                    Intent(this, ConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }
        }

        view.findViewById<View>(R.id.bubblePauseButton).setOnClickListener {
            if (isRecording) {
                startService(Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_TOGGLE_PAUSE
                })
            }
        }

        view.findViewById<View>(R.id.bubbleCloseButton).setOnClickListener {
            stopSelf()
        }

        windowManager.addView(view, params)
        updateBubbleUi()
    }

    private fun updateBubbleUi() {
        val view = bubbleView ?: return
        val recordBtn = view.findViewById<TextView>(R.id.bubbleRecordButton)
        val pauseBtn = view.findViewById<TextView>(R.id.bubblePauseButton)
        recordBtn.text = if (isRecording) "■" else "●"
        pauseBtn.text = if (isPaused) "▶" else "❙❙"
        pauseBtn.visibility = if (isRecording) View.VISIBLE else View.GONE
        if (!isRecording) {
            view.findViewById<TextView>(R.id.bubbleTimer).text = "00:00"
        }
    }

    private fun formatMillis(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun buildBubbleNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Floating control", NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating control active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        runCatching { unregisterReceiver(receiver) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
