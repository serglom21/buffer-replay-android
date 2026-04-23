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
import io.sentry.Sentry
import io.sentry.android.replay.ReplayIntegration

/**
 * Simulates the customer's login screen — the decision point where the app
 * learns whether the user has opted in to analytics.
 *
 * Context: the customer initialises Sentry with Double.MIN_VALUE sample rates
 * so replay is technically enabled (buffer is accumulating) but will never
 * auto-send. They don't know until login + consent check whether to enable it.
 * If the user opts in, the error sample rate is increased (their workaround is
 * stop → onErrorSampleRate=1.0 → start). Time spent here appears in the
 * pre-login buffer — which stop() will discard.
 *
 * Scenarios routed through here:
 *   A  → stop/start on opt-in  → ContentActivity (30 s) → DebugActivity
 *   B  → rate mutation only    → ContentActivity (30 s) → DebugActivity  (buffer preserved)
 *   D  → stop/start on opt-in  → 5 s countdown          → DebugActivity
 *   E  → stop/start on opt-in  → 60 s countdown         → DebugActivity
 */
class LoginActivity : Activity() {

    companion object {
        const val EXTRA_SCENARIO = "scenario"
    }

    private val TAG = App.TAG
    private lateinit var statusText: TextView
    private lateinit var preLoginStateText: TextView
    private var secondsOnScreen = 0
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            secondsOnScreen++
            preLoginStateText.text = buildPreLoginState()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: "A"

        val workaroundLabel = when (scenario) {
            "B" -> "rate mutation only (no stop/start)"
            else -> "stop/start → onErrorSampleRate=1.0"
        }
        val forwardLabel = when (scenario) {
            "D" -> "5 s → DebugActivity"
            "E" -> "60 s → DebugActivity"
            else -> "ContentActivity"
        }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        root.addView(TextView(this).apply {
            text = "LoginActivity\n(pre-login · opt-in decision)"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "Scenario $scenario  ·  $workaroundLabel  →  $forwardLabel\n\n" +
                    "SDK is already initialised. Time here is pre-login content\n" +
                    "in the replay buffer (init used Double.MIN_VALUE → buffer mode).\n" +
                    "Opt-in below simulates the customer's analytics consent check."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        // Live pre-login state panel — updates every second
        preLoginStateText = TextView(this).apply {
            text = buildPreLoginState()
            textSize = 12f
            setTextColor(Color.parseColor("#333355"))
            setBackgroundColor(Color.parseColor("#eeeef8"))
            setPadding(12, 10, 12, 10)
        }
        root.addView(preLoginStateText)

        root.addView(TextView(this).apply {
            text = "↑ Updates every second. captureStrategy should be BufferCaptureStrategy\n" +
                    "  because only onErrorSampleRate > 0 at init. Pre-login content IS buffered."
            textSize = 10f
            setTextColor(Color.DKGRAY)
            setPadding(8, 2, 8, 20)
        })

        statusText = TextView(this).apply {
            text = "Waiting for login decision…"
            setTextColor(Color.parseColor("#1a1a2e"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusText)

        root.addView(sectionLabel("━━━  Login decision  ━━━"))

        // Login + opt in
        root.addView(makeButton(
            "Login  +  Opt in to analytics  ✓",
            Color.parseColor("#27ae60")
        ) { doOptIn(scenario) })

        val optInDetail = when (scenario) {
            "B" -> "onErrorSampleRate → 1.0  (no stop/start — pre-login buffer PRESERVED)"
            else -> "stop() → pre-login buffer DISCARDED\nonErrorSampleRate → 1.0 → start() → resume()"
        }
        root.addView(TextView(this).apply {
            text = optInDetail
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 12)
        })

        // Explicit opt-out: stop() to discard the pre-login buffer (privacy-safe)
        root.addView(makeButton(
            "Login  +  Opt OUT  ✗  (stop() — buffer discarded)",
            Color.parseColor("#c0392b")
        ) { doOptOut(scenario) })

        root.addView(TextView(this).apply {
            text = "stop() called immediately — pre-login buffer DISCARDED for privacy.\n" +
                    "captureReplay() in DebugActivity should send nothing / start cold."
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 12)
        })

        // Login without opt-in (no stop — risky: buffer lingers in memory)
        root.addView(makeButton(
            "Login  (no opt-in, no stop — RISKY)",
            Color.parseColor("#7f8c8d")
        ) { doLoginWithoutOptIn(scenario) })

        root.addView(TextView(this).apply {
            text = "Rates stay at Double.MIN_VALUE, stop() NOT called.\n" +
                    "Pre-login buffer lingers — a downstream captureReplay() could leak it."
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 16)
        })

        root.addView(makeButton("← Back", Color.parseColor("#2c3e50")) {
            finish()
        })

        scroll.addView(root)
        setContentView(scroll)

        handler.postDelayed(tickRunnable, 1_000)

        Log.d(TAG, "LoginActivity.onCreate — scenario=$scenario — pre-login SDK state:")
        Log.d(TAG, buildPreLoginState())
    }

    // -----------------------------------------------------------------------
    // Opt-in path: triggers the workaround matching the scenario
    // -----------------------------------------------------------------------
    private fun doOptIn(scenario: String) {
        handler.removeCallbacks(tickRunnable)
        val app = App.get(this)
        val replayController = Sentry.replay() as? ReplayIntegration

        Log.d(TAG, "========================================")
        Log.d(TAG, "LOGIN + OPT-IN — scenario=$scenario  preLoginTime=${secondsOnScreen}s")
        Log.d(TAG, "========================================")

        val idBefore = App.getReplayId()
        Log.d(TAG, "[login] BEFORE workaround: replayId=$idBefore  isRecording=${App.getIsRecording()}")
        Log.d(TAG, "[login] captureStrategy BEFORE: ${App.getCaptureStrategyInfo()}")
        Log.d(TAG, "[login] Pre-login buffer had ~${secondsOnScreen}s of content")

        when (scenario) {
            "B" -> {
                // Proposed fix: only mutate onErrorSampleRate — no stop/start, buffer stays intact.
                app.sentryOptions.sessionReplay.onErrorSampleRate = 1.0
                Log.d(TAG, "[login-B] onErrorSampleRate → 1.0  (no stop/start — pre-login buffer PRESERVED)")
                Log.d(TAG, "[login-B] captureStrategy AFTER: ${App.getCaptureStrategyInfo()}")
            }
            else -> {
                // Customer's workaround: stop → onErrorSampleRate=1.0 → start → resume.
                // Uses onErrorSampleRate (error/buffer mode), NOT sessionSampleRate (session mode),
                // matching the customer's description: "the error sample rate will be increased".
                // This should keep SDK in BufferCaptureStrategy, not switch to SessionCaptureStrategy.
                replayController?.stop()
                Log.d(TAG, "[login-$scenario] stop() called — pre-login buffer DISCARDED")
                Log.d(TAG, "[login-$scenario] AFTER stop: replayId=${App.getReplayId()}  isRecording=${App.getIsRecording()}")

                app.sentryOptions.sessionReplay.onErrorSampleRate = 1.0
                replayController?.start()
                replayController?.resume()

                val idAfter = App.getReplayId()
                Log.d(TAG, "[login-$scenario] onErrorSampleRate → 1.0  start() + resume() called")
                Log.d(TAG, "[login-$scenario] AFTER start: replayId=$idAfter  isRecording=${App.getIsRecording()}")
                Log.d(TAG, "[login-$scenario] captureStrategy AFTER: ${App.getCaptureStrategyInfo()}")
                Log.d(TAG, "[login-$scenario] Expect BufferCaptureStrategy (onError=1.0, session=MIN_VALUE)")
                Log.d(TAG, "[login-$scenario] → envelope replay_type should be 'buffer', NOT 'session'")
                Log.d(TAG, "[login-$scenario] Filter logcat tag 'Sentry' for envelope payload after captureReplay")
            }
        }

        val idAfter = App.getReplayId()
        val strategyAfter = App.getCaptureStrategyInfo()
        statusText.text = "Opted in.\nreplayId=$idAfter\n$strategyAfter"

        navigateForward(scenario)
    }

    // -----------------------------------------------------------------------
    // Explicit opt-out: stop() to discard the pre-login buffer (privacy-safe)
    // -----------------------------------------------------------------------
    private fun doOptOut(scenario: String) {
        handler.removeCallbacks(tickRunnable)
        val replayController = Sentry.replay() as? ReplayIntegration

        Log.d(TAG, "========================================")
        Log.d(TAG, "LOGIN + OPT-OUT — scenario=$scenario  preLoginTime=${secondsOnScreen}s")
        Log.d(TAG, "========================================")

        val idBefore = App.getReplayId()
        val stratBefore = App.getCaptureStrategyInfo()
        Log.d(TAG, "[opt-out] BEFORE stop: replayId=$idBefore  isRecording=${App.getIsRecording()}")
        Log.d(TAG, "[opt-out] captureStrategy BEFORE: $stratBefore")
        Log.d(TAG, "[opt-out] Pre-login buffer had ~${secondsOnScreen}s of content — discarding for privacy")

        replayController?.stop()

        val idAfter = App.getReplayId()
        Log.d(TAG, "[opt-out] stop() called — pre-login buffer DISCARDED")
        Log.d(TAG, "[opt-out] AFTER stop: replayId=$idAfter  isRecording=${App.getIsRecording()}")
        Log.d(TAG, "[opt-out] Rates left unchanged (Double.MIN_VALUE) — SDK will not auto-send")
        Log.d(TAG, "[opt-out] Expected: captureReplay() in DebugActivity sends nothing or starts cold")

        statusText.text = "Opted OUT.\nstop() called — buffer discarded for privacy.\nreplayId=$idAfter  isRecording=${App.getIsRecording()}"
        navigateForward(scenario)
    }

    // -----------------------------------------------------------------------
    // No opt-in path: proceed without changing any rates
    // -----------------------------------------------------------------------
    private fun doLoginWithoutOptIn(scenario: String) {
        handler.removeCallbacks(tickRunnable)
        val app = App.get(this)

        Log.d(TAG, "========================================")
        Log.d(TAG, "LOGIN (no opt-in) — scenario=$scenario  preLoginTime=${secondsOnScreen}s")
        Log.d(TAG, "========================================")
        Log.d(TAG, "[login-no-optin] Rates unchanged:")
        Log.d(TAG, "[login-no-optin]   onErrorSampleRate = ${app.sentryOptions.sessionReplay.onErrorSampleRate}")
        Log.d(TAG, "[login-no-optin]   sessionSampleRate = ${app.sentryOptions.sessionReplay.sessionSampleRate}")
        Log.d(TAG, "[login-no-optin] captureReplay() will likely not send — rates still at Double.MIN_VALUE")

        statusText.text = "Logged in (no opt-in).\nRates unchanged — Double.MIN_VALUE.\ncaptureReplay() will likely not send."
        navigateForward(scenario)
    }

    private fun navigateForward(scenario: String) {
        when (scenario) {
            "A", "B", "F" -> {
                Log.d(TAG, "[nav] scenario $scenario → ContentActivity")
                startActivity(Intent(this, ContentActivity::class.java))
            }
            "D" -> navigateToDebugAfter(5, scenario)
            "E" -> navigateToDebugAfter(60, scenario)
        }
    }

    private fun navigateToDebugAfter(waitSeconds: Int, scenarioLabel: String) {
        val idNow = App.getReplayId()
        val stratNow = App.getCaptureStrategyInfo()
        Log.d(TAG, "[$scenarioLabel] post-login: counting down ${waitSeconds}s then → DebugActivity")
        Log.d(TAG, "[$scenarioLabel] captureStrategy now: $stratNow")

        var remaining = waitSeconds
        val countdownRunnable = object : Runnable {
            override fun run() {
                remaining--
                if (remaining > 0) {
                    Log.d(TAG, "[$scenarioLabel] ${remaining}s until DebugActivity")
                    statusText.text = "Opted in. DebugActivity in ${remaining}s…\nreplayId=$idNow\n$stratNow"
                    handler.postDelayed(this, 1_000)
                } else {
                    Log.d(TAG, "[$scenarioLabel] ${waitSeconds}s elapsed → launching DebugActivity")
                    startActivity(Intent(this@LoginActivity, DebugActivity::class.java))
                }
            }
        }
        statusText.text = "Opted in. DebugActivity in ${waitSeconds}s…\nreplayId=$idNow\n$stratNow"
        handler.postDelayed(countdownRunnable, 1_000)
    }

    private fun buildPreLoginState(): String {
        val app = App.get(this)
        return "pre-login state  [${secondsOnScreen}s on screen]\n" +
                "  replayId:          ${App.getReplayId()}\n" +
                "  isRecording:       ${App.getIsRecording()}\n" +
                "  onErrorSampleRate: ${app.sentryOptions.sessionReplay.onErrorSampleRate}\n" +
                "  sessionSampleRate: ${app.sentryOptions.sessionReplay.sessionSampleRate}\n" +
                "  captureStrategy:   ${App.getCaptureStrategyInfo()}"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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
