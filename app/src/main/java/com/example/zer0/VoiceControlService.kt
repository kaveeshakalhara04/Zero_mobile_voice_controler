package com.example.zer0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoiceControlService : Service(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val channelId = "offline_voice_channel"
    private val notificationId = 1

    private var isAwake = false
    private val handler = Handler(Looper.getMainLooper())
    private val commandTimeoutMs = 8000L // "Hey Zero" කියලා තත්පර 8ක් ඇතුළත command එක කියන්න ඕනේ

    private val sleepRunnable = Runnable {
        isAwake = false
        updateNotification("Say \"Hey Zero\" to wake...")
        Log.d("VoiceCommand", "Awake timeout -> back to sleep mode")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, buildNotification("Say \"Hey Zero\" to wake..."))
        acquireWakeLock()
        initVoskModel()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Zer0Voice::MicWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun initVoskModel() {
        StorageService.unpack(
            this,
            "model-small-en-us-0.15",
            "model",
            { unpackedModel ->
                model = unpackedModel
                startListening()
            },
            { exception ->
                Log.e("VoiceControl", "Model unpack failed: ${exception.message}")
            }
        )
    }

    private fun startListening() {
        val loadedModel = model ?: return
        try {
            speechService?.stop()
            speechService?.shutdown()
            val recognizer = Recognizer(loadedModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            Log.d("VoiceControl", "Started listening locally...")
        } catch (e: IOException) {
            Log.e("VoiceControl", "Failed to start SpeechService: ${e.message}")
        }
    }

    override fun onResult(hypothesis: String?) {
        parseVoiceText(hypothesis, isPartial = false)
    }

    override fun onFinalResult(hypothesis: String?) {
        parseVoiceText(hypothesis, isPartial = false)
    }

    override fun onPartialResult(hypothesis: String?) {
        // Wake word එක ඉක්මනින් අල්ලගන්න partial result එකත් බලනවා
        parseVoiceText(hypothesis, isPartial = true)
    }

    override fun onError(exception: Exception?) {
        Log.e("VoiceControl", "Recognition error: ${exception?.message}")
    }

    override fun onTimeout() {
        startListening()
    }

    private fun extractText(jsonStr: String?, isPartial: Boolean): String {
        if (jsonStr.isNullOrBlank()) return ""
        val obj = JSONObject(jsonStr)
        val key = if (isPartial) "partial" else "text"
        return obj.optString(key, "").lowercase().trim()
    }

    private fun parseVoiceText(jsonStr: String?, isPartial: Boolean) {
        try {
            val text = extractText(jsonStr, isPartial)
            if (text.isEmpty()) return

            if (!isPartial) {
                Log.d("VoiceCommand", "Heard (final): $text")
            }

            // 1. Wake word check: "hey zero" (සමහර වෙලාවට "hey hero" / "hey siro" ලෙස ඇහෙන්න පුළුවන්)
            if (!isAwake && (text.contains("hey zero") || text.contains("hey hero"))) {
                activateAssistant()
                return
            }

            // 2. Awake වෙලා ඉන්න වෙලාවේ final result එකෙන් command එක execute කරනවා
            if (isAwake && !isPartial) {
                // "hey zero" විතරක් ආවොත් තව command එකක් එනකම් ඉන්නවා
                val cleanCmd = text.replace("hey zero", "").replace("hey hero", "").trim()
                if (cleanCmd.isEmpty()) return

                executeCommand(cleanCmd)
            }
        } catch (e: Exception) {
            Log.e("VoiceCommand", "Parse error: ${e.message}")
        }
    }

    private fun activateAssistant() {
        isAwake = true
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, commandTimeoutMs)

        updateNotification("Listening... Say a command")
        Log.d("VoiceCommand", "Assistant AWAKENED!")

        wakeScreenAndSwipe()
        bringAppToFront()
    }

    private fun executeCommand(text: String) {
        var matched = false

        when {
            text.contains("unlock phone") || text.contains("open lock") || text.contains("unlock") -> {
                wakeScreenAndSwipe()
                matched = true
            }
            text.contains("open camera") || text.contains("camera") -> {
                wakeScreenAndSwipe()
                launchAppByPackage("com.android.camera")
                matched = true
            }
            text.contains("open whatsapp") || text.contains("whatsapp") -> {
                wakeScreenAndSwipe()
                launchAppByPackage("com.whatsapp")
                matched = true
            }
            text.contains("open youtube") || text.contains("youtube") -> {
                wakeScreenAndSwipe()
                launchAppByPackage("com.google.android.youtube")
                matched = true
            }
        }

        if (matched) {
            handler.removeCallbacks(sleepRunnable)
            isAwake = false
            updateNotification("Say \"Hey Zero\" to wake...")
        }
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenAndSwipe() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Zer0Voice::ScreenWakeLock"
        )
        screenWakeLock.acquire(3000L)
        UnlockAccessibilityService.instance?.swipeUpToUnlock()
    }

    private fun launchAppByPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Log.w("VoiceCommand", "App not found: $packageName")
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Offline Voice Control",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Zer0 Voice Assistant Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}