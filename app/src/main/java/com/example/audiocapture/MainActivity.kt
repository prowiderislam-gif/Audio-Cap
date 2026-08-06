package com.example.audiocapture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var bubbleToggle: Switch
    private lateinit var projectionManager: MediaProjectionManager
    private var isRecording = false
    private var overlayPermissionRequested = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioCaptureService.ACTION_RECORDING_STARTED -> {
                    isRecording = true
                    updateUi()
                }
                AudioCaptureService.ACTION_RECORDING_FAILED -> {
                    isRecording = false
                    updateUi()
                    val msg = intent.getStringExtra(AudioCaptureService.EXTRA_MESSAGE)
                        ?: "Recording failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                AudioCaptureService.ACTION_RECORDING_SAVED -> {
                    val name = intent.getStringExtra(AudioCaptureService.EXTRA_MESSAGE)
                    Toast.makeText(
                        this@MainActivity,
                        "Saved $name to Music/AudioCapture",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                statusText.text = "Starting…"
            } else {
                Toast.makeText(this, "Permission denied — can't record without it", Toast.LENGTH_SHORT).show()
            }
        }

    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                launchProjectionPrompt()
            } else {
                Toast.makeText(this, "Permissions are required to record audio", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startButton = findViewById<android.widget.Button>(R.id.startButton)
        val stopButton = findViewById<android.widget.Button>(R.id.stopButton)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startButton.setOnClickListener {
            if (!isRecording) checkPermissionsAndStart()
        }

        stopButton.setOnClickListener {
            val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(stopIntent)
            isRecording = false
            updateUi()
        }

        bubbleToggle = findViewById(R.id.bubbleToggle)
        bubbleToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    startService(Intent(this, BubbleOverlayService::class.java))
                } else {
                    overlayPermissionRequested = true
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                stopService(Intent(this, BubbleOverlayService::class.java))
            }
        }

        val stateFilter = IntentFilter().apply {
            addAction(AudioCaptureService.ACTION_RECORDING_STARTED)
            addAction(AudioCaptureService.ACTION_RECORDING_FAILED)
            addAction(AudioCaptureService.ACTION_RECORDING_SAVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, stateFilter)
        }

        updateUi()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            launchProjectionPrompt()
        }
    }

    private fun launchProjectionPrompt() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun updateUi() {
        statusText.text = if (isRecording) {
            "Recording internal audio…\nCheck your notification shade for a live timer and Stop button."
        } else {
            "Not recording.\nTap Start, then approve the capture prompt.\nMinimize the app if you like — recording continues in the background."
        }
    }

    override fun onResume() {
        super.onResume()
        if (overlayPermissionRequested) {
            overlayPermissionRequested = false
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, BubbleOverlayService::class.java))
                bubbleToggle.isChecked = true
            } else {
                bubbleToggle.isChecked = false
                Toast.makeText(
                    this,
                    "Overlay permission is needed for the floating control",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
    }
}
