package com.example.sentryreplayrepro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/**
 * Simulates a normal app screen (e.g. a lesson or content screen).
 * Spend 30+ seconds here tapping the "User interaction" buttons so the replay
 * buffer fills with visible, identifiable content before going to DebugActivity.
 *
 * In Sentry's replay UI, SUCCESS looks like: ContentActivity content (with button
 * taps visible) appears BEFORE DebugActivity in the recording.
 */
class ContentActivity : Activity() {

    private val TAG = App.TAG

    private lateinit var timerText: TextView
    private lateinit var stateText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var secondsOnScreen = 0
    private var tapCount = 0

    // Tick every 1 second → updates the on-screen counter
    private val tickRunnable = object : Runnable {
        override fun run() {
            secondsOnScreen++
            val msg = if (secondsOnScreen < 30) {
                "Time on screen: ${secondsOnScreen}s — wait ${30 - secondsOnScreen}s more to fill buffer"
            } else {
                "Time on screen: ${secondsOnScreen}s ✓ Buffer should be full — go to DebugActivity!"
            }
            timerText.text = msg
            handler.postDelayed(this, 1_000)
        }
    }

    // Every 10 seconds → log replay state to logcat
    private val logRunnable = object : Runnable {
        override fun run() {
            logReplayState("auto-log @ ${secondsOnScreen}s")
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        root.addView(TextView(this).apply {
            text = "ContentActivity\n(buffer-building screen)"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "Tap the buttons below to create replay-visible interactions.\n" +
                    "Wait 30+ seconds, then go to DebugActivity to capture the replay."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        })

        timerText = TextView(this).apply {
            text = "Time on screen: 0s — wait 30s to fill buffer"
            textSize = 15f
            setTextColor(Color.parseColor("#c0392b"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(timerText)

        stateText = TextView(this).apply {
            text = "Replay state: tap 'Check state' or wait 10s for auto-log"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        }
        root.addView(stateText)

        // 4 user interaction buttons — these will appear as taps in the replay recording
        listOf(
            "User interaction 1 — Tap me!",
            "User interaction 2 — Tap me!",
            "User interaction 3 — Tap me!",
            "User interaction 4 — Tap me!"
        ).forEachIndexed { i, label ->
            val color = listOf(
                Color.parseColor("#8e44ad"),
                Color.parseColor("#16a085"),
                Color.parseColor("#d35400"),
                Color.parseColor("#2980b9")
            )[i]
            root.addView(makeButton(label, color) {
                tapCount++
                Log.d(TAG, "ContentActivity: tap #$tapCount on button ${i + 1} at ${secondsOnScreen}s, replayId=${App.getReplayId()}")
                stateText.text = "Tap #$tapCount on button ${i + 1} at ${secondsOnScreen}s  |  replayId=${App.getReplayId()}"
            })
        }

        root.addView(sectionLabel("━━━  Navigation  ━━━"))

        root.addView(makeButton("Go to DebugActivity (capture replay) →", Color.parseColor("#c0392b")) {
            Log.d(TAG, "ContentActivity: navigating to DebugActivity after ${secondsOnScreen}s on screen")
            startActivity(Intent(this, DebugActivity::class.java))
        })

        root.addView(makeButton("← Back to MainActivity", Color.parseColor("#7f8c8d")) {
            finish()
        })

        root.addView(makeButton("Check replay state", Color.parseColor("#2c3e50")) {
            logReplayState("manual check")
        })

        scroll.addView(root)
        setContentView(scroll)

        // Start timers
        handler.postDelayed(tickRunnable, 1_000)
        handler.postDelayed(logRunnable, 10_000)

        logReplayState("ContentActivity.onCreate")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "ContentActivity.onResume at ${secondsOnScreen}s, replayId=${App.getReplayId()}")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "ContentActivity.onPause at ${secondsOnScreen}s, replayId=${App.getReplayId()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(logRunnable)
        Log.d(TAG, "ContentActivity.onDestroy")
    }

    private fun logReplayState(trigger: String) {
        val app = App.get(this)
        val replayId = App.getReplayId()
        val isRecording = App.getIsRecording()
        val onErrorRate = app.sentryOptions.sessionReplay.onErrorSampleRate
        val sessionRate = app.sentryOptions.sessionReplay.sessionSampleRate

        val msg = "[$trigger] replayId=$replayId  isRecording=$isRecording  " +
                "onError=$onErrorRate  session=$sessionRate  screenTime=${secondsOnScreen}s"
        Log.d(TAG, msg)
        stateText.text = msg
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.DKGRAY)
        gravity = Gravity.CENTER
        setPadding(0, 16, 0, 8)
    }

    private fun makeButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 6, 0, 2)
            }
        }
    }
}
