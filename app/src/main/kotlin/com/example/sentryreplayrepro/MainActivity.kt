package com.example.sentryreplayrepro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.sentry.Sentry
import io.sentry.android.replay.ReplayIntegration

/**
 * Entry point. Lets you choose which reproduction scenario to run before
 * navigating to ContentActivity to build up 30s of buffer.
 */
class MainActivity : Activity() {

    private val TAG = App.TAG
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        root.addView(TextView(this).apply {
            text = "Sentry Session Replay\nBuffer Repro"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "SDK 8.38.0 · onErrorSampleRate = Double.MIN_VALUE\n" +
                    "sessionSampleRate = Double.MIN_VALUE"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 24)
        })

        statusText = TextView(this).apply {
            text = "Choose a scenario below, then go to ContentActivity."
            setTextColor(Color.parseColor("#1a1a2e"))
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        root.addView(sectionLabel("━━━  Reproduction Scenarios  ━━━"))

        // ------------------------------------------------------------------
        // Scenario A — customer's current flow: stop → mutate → start
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario A: Login (stop/start workaround)",
            Color.parseColor("#c0392b")
        ) { scenarioA() })

        root.addView(TextView(this).apply {
            text = "stop() deletes the buffer → replay likely starts cold at DebugActivity"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        })

        // ------------------------------------------------------------------
        // Scenario B — proposed fix: mutate onErrorSampleRate, skip stop/start
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario B: Login (mutate rate only)",
            Color.parseColor("#27ae60")
        ) { scenarioB() })

        root.addView(TextView(this).apply {
            text = "No stop/start → buffer preserved → replay should include full 30s"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        })

        // ------------------------------------------------------------------
        // Scenario C — background timeout test
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario C: Test background timeout",
            Color.parseColor("#2980b9")
        ) {
            updateStatus(
                "Scenario C: go to ContentActivity → wait 30s → press HOME → " +
                        "wait 35+ seconds → return → go to DebugActivity immediately.\n\n" +
                        "Expected: buffer empty (LifecycleWatcher called stop() after sessionTimeoutInterval)."
            )
            startActivity(Intent(this, ContentActivity::class.java))
        })

        root.addView(sectionLabel("━━━  State & Navigation  ━━━"))

        root.addView(makeButton("Check replay state", Color.parseColor("#7f8c8d")) {
            checkAndDisplayReplayState()
        })

        root.addView(makeButton("Go to ContentActivity →", Color.parseColor("#2c3e50")) {
            startActivity(Intent(this, ContentActivity::class.java))
        })

        scroll.addView(root)
        setContentView(scroll)

        Log.d(TAG, "MainActivity.onCreate — initial replay state: replayId=${App.getReplayId()}, isRecording=${App.getIsRecording()}")
    }

    // -----------------------------------------------------------------------
    // Scenario A: customer's current workaround
    // -----------------------------------------------------------------------
    private fun scenarioA() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "SCENARIO A: stop/start workaround begin")
        Log.d(TAG, "========================================")

        val app = App.get(this)
        // IReplayController (returned by Sentry.replay()) lacks stop/start/resume in 8.38.0 —
        // cast to the implementation class to reach the full lifecycle API.
        val replayController = Sentry.replay() as? ReplayIntegration

        val idBefore = App.getReplayId()
        val recBefore = App.getIsRecording()
        Log.d(TAG, "[A] BEFORE stop  → replayId=$idBefore  isRecording=$recBefore")

        // ← THIS IS THE BUG: stop() discards the in-memory frame buffer.
        replayController?.stop()
        Log.d(TAG, "[A] AFTER  stop  → replayId=${App.getReplayId()}  isRecording=${App.getIsRecording()}")

        // Mutate rate while stopped (SDK in session mode after start).
        app.sentryOptions.sessionReplay.sessionSampleRate = 1.0
        Log.d(TAG, "[A] sessionSampleRate mutated to 1.0")

        // Restart — switches mode to SESSION (continuous recording, no buffer).
        replayController?.start()
        replayController?.resume()
        val idAfter = App.getReplayId()
        Log.d(TAG, "[A] AFTER  start → replayId=$idAfter  isRecording=${App.getIsRecording()}")
        Log.d(TAG, "[A] Buffer was cleared by stop(). New session starts NOW.")

        updateStatus(
            "Scenario A done.\n" +
                    "replayId before stop: $idBefore\n" +
                    "replayId after start: $idAfter\n\n" +
                    "Buffer was erased by stop(). If replay starts cold at DebugActivity — this is the bug."
        )
    }

    // -----------------------------------------------------------------------
    // Scenario B: proposed fix — mutate rate only, never stop/start
    // -----------------------------------------------------------------------
    private fun scenarioB() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "SCENARIO B: mutate rate only (no stop/start)")
        Log.d(TAG, "========================================")

        val app = App.get(this)
        val idBefore = App.getReplayId()
        val recBefore = App.getIsRecording()
        Log.d(TAG, "[B] BEFORE rate change → replayId=$idBefore  isRecording=$recBefore")

        // Only change onErrorSampleRate. captureReplay() reads this live at call time.
        // Stays in BUFFER mode — the rolling frame buffer is never discarded.
        app.sentryOptions.sessionReplay.onErrorSampleRate = 1.0
        Log.d(TAG, "[B] onErrorSampleRate mutated to 1.0 (buffer preserved, still BUFFER mode)")

        val idAfter = App.getReplayId()
        Log.d(TAG, "[B] AFTER  rate change → replayId=$idAfter  isRecording=${App.getIsRecording()}")

        updateStatus(
            "Scenario B done.\n" +
                    "onErrorSampleRate = 1.0 (was Double.MIN_VALUE)\n" +
                    "replayId: $idAfter\n\n" +
                    "Buffer intact — replay should include full 30s from ContentActivity."
        )
    }

    private fun checkAndDisplayReplayState() {
        val app = App.get(this)
        val replayId = App.getReplayId()
        val isRecording = App.getIsRecording()
        val onErrorRate = app.sentryOptions.sessionReplay.onErrorSampleRate
        val sessionRate = app.sentryOptions.sessionReplay.sessionSampleRate
        val replayClass = Sentry.replay()?.javaClass?.name ?: "null"

        val state = "replayId:         $replayId\n" +
                "isRecording:      $isRecording\n" +
                "onErrorRate:      $onErrorRate\n" +
                "sessionRate:      $sessionRate\n" +
                "replayClass:      $replayClass"

        Log.d(TAG, "=== Check replay state ===\n$state")
        updateStatus(state)
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.DKGRAY)
        setPadding(0, 8, 0, 8)
        gravity = Gravity.CENTER
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
