package com.example.replayzero

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.sentry.Sentry
import io.sentry.android.replay.ReplayIntegration

class HomeActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var captureButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var secondsRecording = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            secondsRecording++
            val ready = secondsRecording >= 10
            timerText.text = if (ready)
                "Recording: ${secondsRecording}s ✓ ready to capture"
            else
                "Recording: ${secondsRecording}s — wait ${10 - secondsRecording}s before capturing"
            timerText.setTextColor(if (ready) Color.parseColor("#27ae60") else Color.parseColor("#e67e22"))
            captureButton.isEnabled = ready
            captureButton.setBackgroundColor(if (ready) Color.parseColor("#e74c3c") else Color.GRAY)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "Home"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        timerText = TextView(this).apply {
            text = "Recording: 0s — wait 10s before capturing"
            textSize = 14f
            setTextColor(Color.parseColor("#e67e22"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        root.addView(timerText)

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        captureButton = Button(this).apply {
            text = "Capture replay"
            setBackgroundColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener { captureReplay() }
        }
        root.addView(captureButton)

        root.addView(Button(this).apply {
            text = "Check state"
            setBackgroundColor(Color.parseColor("#7f8c8d"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnClickListener { updateStatus() }
        })

        setContentView(root)
        updateStatus()
        handler.postDelayed(tickRunnable, 1_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun captureReplay() {
        Log.d(App.TAG, "--- CAPTURE REPLAY (${secondsRecording}s of recording) ---")
        val controller = Sentry.getCurrentScopes().options.replayController as? ReplayIntegration
        if (controller != null) {
            controller.captureReplay(false)
            Log.d(App.TAG, "captureReplay() called — replayId=${replayId()}")
        } else {
            Log.e(App.TAG, "captureReplay: controller is null — integration was never registered")
        }
        updateStatus()
    }

    private fun updateStatus() {
        val id = replayId()
        val recording = isRecording()
        val msg = "replayId:    $id\nisRecording: $recording"
        Log.d(App.TAG, "state — $msg")
        statusText.text = msg
    }

    private fun replayId() = (Sentry.replay() as? ReplayIntegration)?.replayId?.toString() ?: "null"
    private fun isRecording() = (Sentry.replay() as? ReplayIntegration)?.isRecording
}
