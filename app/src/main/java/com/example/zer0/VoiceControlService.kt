package com.example.zer0

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

class VoiceControlService : Service() {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var voicePrintManager: VoicePrintManager

    private val isListening = AtomicBoolean(false)
    private var listenThread: Thread? = null

    private val sampleRate = 16000
    private val ringBufferSize = sampleRate * 2 // 2 seconds ring buffer
    private val ringBuffer = ShortArray(ringBufferSize)
    private var ringIndex = 0

    private val channelId = "offline_voice_channel"
    private val notificationId = 1

    private var isAwake = false
    private val handler = Handler(Looper.getMainLooper())
    private val commandTimeoutMs = 10000L

    private val sleepRunnable = Runnable {
        isAwake = false
        updateNotification("Say \"Hey Zero\" to wake...")
        sendGuiUpdate(heard = "", feedback = "Sleeping. Say \"Hey Zero\" to wake me up.", status = "Sleeping", dismiss = true)
        Log.d("VoiceCommand", "Awake timeout -> back to sleep mode")
    }

    override fun onCreate() {
        super.onCreate()
        voicePrintManager = VoicePrintManager(this)
        createNotificationChannel()
        startForeground(notificationId, buildNotification("Say \"Hey Zero\" to wake..."))
        acquireWakeLock()
        initVoskModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            acquireWakeLock()
        }
        if (model != null && !isListening.get()) {
            startListening()
        }
        return START_STICKY
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

    @SuppressLint("MissingPermission")
    private fun startListening() {
        val loadedModel = model ?: return
        if (isListening.get()) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, 4096)

        // MIC is universally supported and works on all device chipsets & emulators
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VoiceControl", "AudioRecord failed to initialize")
            return
        }

        recognizer = Recognizer(loadedModel, sampleRate.toFloat())
        isListening.set(true)
        audioRecord?.startRecording()

        listenThread = Thread {
            val readBuf = ShortArray(2048)
            while (isListening.get()) {
                val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                if (read > 0) {
                    appendToRingBuffer(readBuf, read)
                    val rec = recognizer ?: continue
                    val isFinal = rec.acceptWaveForm(readBuf, read)
                    if (isFinal) {
                        parseVoiceText(rec.result, isPartial = false)
                    } else {
                        parseVoiceText(rec.partialResult, isPartial = true)
                    }
                }
            }
        }.apply { start() }

        Log.d("VoiceControl", "Started listening locally with speaker verification...")
    }

    private fun appendToRingBuffer(data: ShortArray, count: Int) {
        for (i in 0 until count) {
            ringBuffer[ringIndex] = data[i]
            ringIndex = (ringIndex + 1) % ringBufferSize
        }
    }

    private fun getRecentPcm(): ShortArray {
        val out = ShortArray(ringBufferSize)
        val firstPart = ringBufferSize - ringIndex
        System.arraycopy(ringBuffer, ringIndex, out, 0, firstPart)
        System.arraycopy(ringBuffer, 0, out, firstPart, ringIndex)
        return out
    }

    private fun extractText(jsonStr: String?, isPartial: Boolean): String {
        if (jsonStr.isNullOrBlank()) return ""
        val obj = JSONObject(jsonStr)
        val key = if (isPartial) "partial" else "text"
        return obj.optString(key, "").lowercase().trim()
    }

    private fun isWakeWord(text: String): Boolean {
        // Vosk small phonetic variations for "Hey Zero":
        // "hey zero", "zero", "hey siro", "hey hero", "a zero", "the zero", "hey zorro"
        return text.contains("hey zero") ||
               text.contains("hey hero") ||
               text.contains("hey siro") ||
               text.contains("hey zorro") ||
               text.contains("a zero") ||
               text.contains("the zero") ||
               text == "zero"
    }

    private fun cleanCommandText(text: String): String {
        var clean = text
        val wakePhrases = listOf("hey zero", "hey hero", "hey siro", "hey zorro", "a zero", "the zero", "zero")
        for (phrase in wakePhrases) {
            clean = clean.replace(phrase, "")
        }
        return clean.trim()
    }

    private fun parseVoiceText(jsonStr: String?, isPartial: Boolean) {
        try {
            val text = extractText(jsonStr, isPartial)
            if (text.isEmpty()) return

            Log.d("VoiceCommand", "Heard (${if (isPartial) "partial" else "final"}): $text")

            // 1. Wake word check: "hey zero"
            if (!isAwake && isWakeWord(text)) {
                val recentAudio = getRecentPcm()
                if (voicePrintManager.isOwnerVoice(recentAudio, sampleRate)) {
                    handler.post { activateAssistant() }
                } else {
                    Log.d("VoiceCommand", "Wake word ignored: voice did not match owner")
                }
                return
            }

            // 2. If already awake, process feedback and commands
            if (isAwake) {
                if (isPartial) {
                    val cleanPartial = cleanCommandText(text)
                    if (cleanPartial.isNotEmpty()) {
                        sendGuiUpdate(heard = cleanPartial, feedback = "Listening...", status = "Hearing you...")
                    }
                } else {
                    val cleanCmd = cleanCommandText(text)
                    if (cleanCmd.isNotEmpty()) {
                        handler.post { executeCommand(cleanCmd) }
                    }
                }
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
        Log.d("VoiceCommand", "Assistant AWAKENED for owner!")

        wakeScreenOnly()
        openAssistantGUI("Hey! How can I help you?")
    }

    private fun openAssistantGUI(initialFeedback: String) {
        val intent = Intent(this, AssistantDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AssistantDialogActivity.EXTRA_FEEDBACK, initialFeedback)
            putExtra(AssistantDialogActivity.EXTRA_STATUS, "Listening...")
        }
        startActivity(intent)
    }

    private fun sendGuiUpdate(heard: String, feedback: String, status: String, dismiss: Boolean = false) {
        val intent = Intent(AssistantDialogActivity.ACTION_UPDATE_GUI).apply {
            setPackage(packageName)
            putExtra(AssistantDialogActivity.EXTRA_HEARD, heard)
            putExtra(AssistantDialogActivity.EXTRA_FEEDBACK, feedback)
            putExtra(AssistantDialogActivity.EXTRA_STATUS, status)
            putExtra(AssistantDialogActivity.EXTRA_DISMISS, dismiss)
        }
        sendBroadcast(intent)
    }

    private fun executeCommand(text: String) {
        when {
            text.contains("unlock phone") || text.contains("open lock") || text.contains("unlock") -> {
                sendGuiUpdate(heard = text, feedback = "Unlocking device now...", status = "Unlocking...")
                wakeScreenAndSwipe()
                handler.removeCallbacks(sleepRunnable)
                handler.postDelayed(sleepRunnable, commandTimeoutMs)
                handler.postDelayed({
                    sendGuiUpdate(heard = text, feedback = "Phone Unlocked! Listening for next command...", status = "Listening...")
                }, 1200)
            }
            text.contains("open camera") || text.contains("camera") -> {
                sendGuiUpdate(heard = text, feedback = "Opening Camera...", status = "Opening App", dismiss = true)
                wakeScreenAndSwipe()
                launchAppByPackage("com.android.camera")
                finishCommand()
            }
            text.contains("open whatsapp") || text.contains("whatsapp") -> {
                sendGuiUpdate(heard = text, feedback = "Opening WhatsApp...", status = "Opening App", dismiss = true)
                wakeScreenAndSwipe()
                launchAppByPackage("com.whatsapp")
                finishCommand()
            }
            text.contains("open youtube") || text.contains("youtube") -> {
                sendGuiUpdate(heard = text, feedback = "Opening YouTube...", status = "Opening App", dismiss = true)
                wakeScreenAndSwipe()
                launchAppByPackage("com.google.android.youtube")
                finishCommand()
            }
            else -> {
                sendGuiUpdate(heard = text, feedback = "Command not recognized: \"$text\"", status = "Listening...")
                handler.removeCallbacks(sleepRunnable)
                handler.postDelayed(sleepRunnable, commandTimeoutMs)
            }
        }
    }

    private fun finishCommand() {
        handler.removeCallbacks(sleepRunnable)
        isAwake = false
        updateNotification("Say \"Hey Zero\" to wake...")
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenOnly() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Zer0Voice::ScreenWakeLock"
        )
        screenWakeLock.acquire(3000L)
    }

    private fun wakeScreenAndSwipe() {
        wakeScreenOnly()
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
        isListening.set(false)
        listenThread?.interrupt()
        audioRecord?.stop()
        audioRecord?.release()
        recognizer?.close()
        handler.removeCallbacks(sleepRunnable)
        model?.close()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
