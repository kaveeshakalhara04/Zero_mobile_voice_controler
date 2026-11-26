package com.example.zer0

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var voicePrintManager: VoicePrintManager
    private lateinit var tvVoiceStatus: TextView
    private lateinit var btnEnroll: Button
    private lateinit var btnReset: Button
    private lateinit var btnBatteryOpt: Button
    private var pendingAction: (() -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingAction?.invoke()
            } else {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            }
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voicePrintManager = VoicePrintManager(this)

        tvVoiceStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        btnEnroll = Button(this).apply {
            text = "Enroll My Voice (Say \"Hey Zero\")"
            setOnClickListener {
                withMicPermission { startVoiceEnrollment() }
            }
        }

        btnReset = Button(this).apply {
            text = "Reset Registered Voice"
            setOnClickListener {
                voicePrintManager.clearEnrollment()
                Toast.makeText(this@MainActivity, "Voice reset. You can now enroll again.", Toast.LENGTH_SHORT).show()
                refreshUI()
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "Enable Accessibility (Screen Wake/Swipe)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnBatteryOpt = Button(this).apply {
            text = "Allow Background Running (No Battery Restriction)"
            setOnClickListener {
                requestIgnoreBatteryOptimizations()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            addView(tvVoiceStatus)
            addView(btnEnroll)
            addView(btnReset)
            addView(btnAccessibility)
            addView(btnBatteryOpt)
        }

        setContentView(layout)
        refreshUI()

        // Voice enroll කරලා තියෙනවා නම් සහ permissions තියෙනවා නම් automatically service එක start කරනවා
        if (voicePrintManager.isEnrolled()) {
            checkAndAutoStartService()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        val enrolled = voicePrintManager.isEnrolled()
        if (enrolled) {
            tvVoiceStatus.text = "Voice Status: Enrolled & Locked\n(Only YOUR voice wakes up Zer0. Phone will listen even when screen is locked.)"
            btnEnroll.visibility = View.GONE
            btnReset.visibility = View.VISIBLE
        } else {
            tvVoiceStatus.text = "Voice Status: Not Enrolled\n(Please enroll your voice once to lock recognition to your voice only.)"
            btnEnroll.visibility = View.VISIBLE
            btnEnroll.isEnabled = true
            btnEnroll.text = "Enroll My Voice (Say \"Hey Zero\")"
            btnReset.visibility = View.GONE
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "Battery optimization is already disabled for Zer0", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndAutoStartService() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startVoiceService()
        }
    }

    private fun withMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            action()
        } else {
            pendingAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVoiceEnrollment() {
        btnEnroll.isEnabled = false
        btnEnroll.text = "Listening... Say \"Hey Zero\" now (2s)"

        Thread {
            val sampleRate = 16000
            val totalSamples = sampleRate * 2
            val buffer = ShortArray(totalSamples)
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096)
            )

            var offset = 0
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
                while (offset < totalSamples) {
                    val read = recorder.read(buffer, offset, totalSamples - offset)
                    if (read > 0) offset += read else break
                }
                recorder.stop()
            }
            recorder.release()

            val success = offset > 0 && voicePrintManager.enrollSamples(listOf(buffer), sampleRate)
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Voice enrolled successfully! Service started automatically.", Toast.LENGTH_LONG).show()
                    startVoiceService()
                } else {
                    Toast.makeText(this, "Failed to record clear voice. Please try again.", Toast.LENGTH_SHORT).show()
                }
                refreshUI()
            }
        }.start()
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
