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
            text = "SDK 8.38.0 · init: both rates = Double.MIN_VALUE\n" +
                    "Replay buffer is ON at launch — opt-in happens at login"
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
            text = "→ LoginActivity → opt-in: stop/start + onErrorSampleRate=1.0 → ContentActivity → DebugActivity\n" +
                    "stop() discards pre-login buffer; post-login content should appear in replay"
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
            text = "→ LoginActivity → opt-in: onErrorSampleRate=1.0 only (no stop/start) → ContentActivity → DebugActivity\n" +
                    "Buffer preserved — replay includes pre-login time + ContentActivity content"
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

        // ------------------------------------------------------------------
        // Scenario D — immediate login sim + 5s wait → DebugActivity
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario D: Login now + wait 5s → DebugActivity",
            Color.parseColor("#e67e22")
        ) { scenarioD() })

        root.addView(TextView(this).apply {
            text = "→ LoginActivity → opt-in: stop/start → 5s → DebugActivity\n" +
                    "Tests: very short post-login wait → empty/tiny replay?"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        })

        // ------------------------------------------------------------------
        // Scenario E — immediate login sim + 60s wait → DebugActivity
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario E: Login now + wait 60s → DebugActivity",
            Color.parseColor("#8e44ad")
        ) { scenarioE() })

        root.addView(TextView(this).apply {
            text = "→ LoginActivity → opt-in: stop/start → 60s → DebugActivity\n" +
                    "Compare D vs E: does replay content scale with post-login wait time?"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        })

        // ------------------------------------------------------------------
        // Scenario F — opt-out privacy check: stop() discards pre-login buffer
        // ------------------------------------------------------------------
        root.addView(makeButton(
            "Scenario F: Opt-out privacy check (stop → ContentActivity → Debug)",
            Color.parseColor("#c0392b")
        ) { scenarioF() })

        root.addView(TextView(this).apply {
            text = "→ LoginActivity → opt-OUT: stop() → ContentActivity → DebugActivity\n" +
                    "Pre-login buffer DISCARDED. Replay in Sentry must NOT show LoginActivity content."
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
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
    // Scenario A: customer's workaround — routed through login screen
    // -----------------------------------------------------------------------
    private fun scenarioA() {
        Log.d(TAG, "SCENARIO A → LoginActivity (stop/start on opt-in → ContentActivity → DebugActivity)")
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SCENARIO, "A"))
    }

    // -----------------------------------------------------------------------
    // Scenario B: proposed fix — routed through login screen
    // -----------------------------------------------------------------------
    private fun scenarioB() {
        Log.d(TAG, "SCENARIO B → LoginActivity (rate mutation only on opt-in → ContentActivity → DebugActivity)")
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SCENARIO, "B"))
    }

    // -----------------------------------------------------------------------
    // Scenario D: login screen → stop/start on opt-in → 5s → DebugActivity
    // -----------------------------------------------------------------------
    private fun scenarioD() {
        Log.d(TAG, "SCENARIO D → LoginActivity (stop/start on opt-in → 5s → DebugActivity)")
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SCENARIO, "D"))
    }

    // -----------------------------------------------------------------------
    // Scenario F: login screen → explicit stop() on opt-out → ContentActivity → DebugActivity
    // -----------------------------------------------------------------------
    private fun scenarioF() {
        Log.d(TAG, "SCENARIO F → LoginActivity (stop() on opt-out → ContentActivity → DebugActivity)")
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SCENARIO, "F"))
    }

    // -----------------------------------------------------------------------
    // Scenario E: login screen → stop/start on opt-in → 60s → DebugActivity
    // -----------------------------------------------------------------------
    private fun scenarioE() {
        Log.d(TAG, "SCENARIO E → LoginActivity (stop/start on opt-in → 60s → DebugActivity)")
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SCENARIO, "E"))
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
