package com.example.zer0

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AssistantDialogActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvHeardText: TextView
    private lateinit var tvFeedback: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val dismissRunnable = Runnable {
        finishAndRemoveTask()
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val heard = intent.getStringExtra(EXTRA_HEARD)
            val feedback = intent.getStringExtra(EXTRA_FEEDBACK)
            val status = intent.getStringExtra(EXTRA_STATUS)
            val autoDismiss = intent.getBooleanExtra(EXTRA_DISMISS, false)

            if (!heard.isNullOrBlank()) {
                tvHeardText.text = "\"$heard\""
            }
            if (!feedback.isNullOrBlank()) {
                tvFeedback.text = feedback
            }
            if (!status.isNullOrBlank()) {
                tvStatus.text = status
            }

            // Reset dismiss timer on any update
            handler.removeCallbacks(dismissRunnable)
            if (autoDismiss) {
                handler.postDelayed(dismissRunnable, 2500)
            } else {
                handler.postDelayed(dismissRunnable, 10000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lockscreen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_assistant_dialog)

        tvStatus = findViewById(R.id.tvStatus)
        tvHeardText = findViewById(R.id.tvHeardText)
        tvFeedback = findViewById(R.id.tvFeedback)

        val initialPrompt = intent.getStringExtra(EXTRA_FEEDBACK) ?: "I'm listening... Say a command"
        tvFeedback.text = initialPrompt

        val filter = IntentFilter(ACTION_UPDATE_GUI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        findViewById<android.view.View>(R.id.rootContainer).setOnClickListener {
            finishAndRemoveTask()
        }

        handler.postDelayed(dismissRunnable, 12000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(dismissRunnable)
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_UPDATE_GUI = "com.example.zer0.ACTION_UPDATE_GUI"
        const val EXTRA_HEARD = "extra_heard"
        const val EXTRA_FEEDBACK = "extra_feedback"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DISMISS = "extra_dismiss"
    }
}
