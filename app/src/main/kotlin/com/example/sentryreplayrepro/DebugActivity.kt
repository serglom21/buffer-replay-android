package com.example.sentryreplayrepro

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.sentry.Sentry
import io.sentry.android.replay.ReplayIntegration

/**
 * Simulates the customer's DebugActivity / Shake-to-Report screen.
 *
 * On open, it immediately captures the replay and constructs the Sentry URL.
 * Open that URL in a browser — if the ContentActivity taps appear BEFORE this
 * screen, the buffer was preserved. If the replay starts here cold, the buffer
 * was lost (the bug).
 */
class DebugActivity : Activity() {

    private val TAG = App.TAG
    private lateinit var outputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        root.addView(TextView(this).apply {
            text = "DebugActivity\n(bug report / replay capture)"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })

        root.addView(TextView(this).apply {
            text = "captureReplay(false) was called automatically on open.\n" +
                    "Open the URL below in Sentry's Replay UI."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        outputText = TextView(this).apply {
            text = "Capturing…"
            textSize = 13f
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
        }
        scroll.addView(outputText)
        root.addView(scroll)

        root.addView(makeButton("Capture replay again (manual)", Color.parseColor("#c0392b")) {
            captureAndDisplay()
        })

        root.addView(makeButton("Check replay state", Color.parseColor("#2980b9")) {
            displayState("manual check")
        })

        root.addView(makeButton("← Back", Color.parseColor("#7f8c8d")) {
            finish()
        })

        setContentView(root)

        // Immediately capture on open — mirrors the customer's flow
        captureAndDisplay()
    }

    private fun captureAndDisplay() {
        val app = App.get(this)

        // 1. Log state BEFORE capture
        val idBefore = App.getReplayId()
        val recBefore = App.getIsRecording()
        val onErrorBefore = app.sentryOptions.sessionReplay.onErrorSampleRate
        val sessionBefore = app.sentryOptions.sessionReplay.sessionSampleRate
        Log.d(TAG, "=== DebugActivity: captureReplay triggered ===")
        Log.d(TAG, "  BEFORE capture: replayId=$idBefore  isRecording=$recBefore")
        Log.d(TAG, "  onErrorSampleRate=$onErrorBefore  sessionSampleRate=$sessionBefore")

        // 2. Capture the replay
        //    captureReplay(false) — false = isTerminating (irrelevant here; true is for crash handlers)
        //    Not on IReplayController, so we cast via App.doCapture().
        val captured = App.doCapture(app)
        Log.d(TAG, "  captureReplay() returned, success=$captured")

        // 3. Log state AFTER capture
        val idAfter = App.getReplayId()
        val recAfter = App.getIsRecording()
        Log.d(TAG, "  AFTER  capture: replayId=$idAfter  isRecording=$recAfter")

        // 4. Build the URL
        //    SentryId.toString() returns 32 hex chars with NO dashes — that is the URL format.
        val replayId = (Sentry.replay() as? ReplayIntegration)?.replayId
        val replayIdStr = replayId?.toString() ?: idAfter  // both call toString(); shown separately for clarity
        val url = App.buildReplayUrl(replayIdStr)

        val output = buildString {
            appendLine("━━━ captureReplay() result ━━━")
            appendLine()
            appendLine("replayId (SentryId object): $replayId")
            appendLine("replayId.toString():         $replayIdStr")
            appendLine("  ↑ no dashes — this is what the URL uses")
            appendLine()
            appendLine("isRecording before: $recBefore")
            appendLine("isRecording after:  $recAfter")
            appendLine()
            appendLine("onErrorSampleRate:  $onErrorBefore")
            appendLine("sessionSampleRate:  $sessionBefore")
            appendLine()
            appendLine("captureReplay() success: $captured")
            appendLine()
            appendLine("━━━ Sentry Replay URL ━━━")
            appendLine()
            appendLine(url)
            appendLine()
            appendLine("Open this URL in your browser.")
            appendLine("SUCCESS: ContentActivity taps appear before this screen.")
            appendLine("BUG REPRODUCED: replay starts cold at DebugActivity.")
        }

        Log.d(TAG, "DebugActivity output:\n$output")
        outputText.text = output
    }

    private fun displayState(trigger: String) {
        val app = App.get(this)
        val replayId = App.getReplayId()
        val isRecording = App.getIsRecording()
        val onErrorRate = app.sentryOptions.sessionReplay.onErrorSampleRate
        val sessionRate = app.sentryOptions.sessionReplay.sessionSampleRate
        val replayClass = Sentry.replay()?.javaClass?.name ?: "null"

        val state = buildString {
            appendLine("━━━ Replay state [$trigger] ━━━")
            appendLine("replayId:         $replayId")
            appendLine("isRecording:      $isRecording")
            appendLine("onErrorRate:      $onErrorRate")
            appendLine("sessionRate:      $sessionRate")
            appendLine("replayController: $replayClass")
            appendLine("url:              ${App.buildReplayUrl(replayId)}")
        }

        Log.d(TAG, state)
        outputText.text = state
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
