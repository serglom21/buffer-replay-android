package com.example.replayzero

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.replay.ReplayIntegration

class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "Welcome back"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "Sentry init: onErrorSampleRate=0.0, sessionSampleRate=0.0"
            textSize = 11f
            setTextColor(Color.parseColor("#e74c3c"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })

        val username = EditText(this).apply {
            hint = "Username"
            setText("sergio@example.com")
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(username)

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 16)
        })

        val password = EditText(this).apply {
            hint = "Password"
            setText("••••••••")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(password)

        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 32)
        })

        root.addView(Button(this).apply {
            text = "Log in"
            setBackgroundColor(Color.parseColor("#1cb0f6"))
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnClickListener { showConsentDialog() }
        })

        setContentView(root)

        Log.d(App.TAG, "LoginActivity — replayId=${replayId()}, isRecording=${isRecording()}")
    }

    private fun showConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("Help improve the app")
            .setMessage("Allow session recording so we can identify and fix issues faster? You can change this at any time in Settings.")
            .setPositiveButton("Allow") { _, _ -> onOptIn() }
            .setNegativeButton("No thanks") { _, _ -> navigateHome() }
            .setCancelable(false)
            .show()
    }

    private fun onOptIn() {
        Log.d(App.TAG, "--- USER OPTED IN ---")
        Log.d(App.TAG, "BEFORE  replayId=${replayId()}, isRecording=${isRecording()}")

        // After login — user opted IN: start recording
        Sentry.getCurrentScopes().options.sessionReplay.onErrorSampleRate = 1.0

        Log.d(App.TAG, "AFTER rate=1.0  replayId=${replayId()}, isRecording=${isRecording()}")

        val controller = Sentry.getCurrentScopes().options.replayController as? ReplayIntegration
        Log.d(App.TAG, "controller=${controller?.javaClass?.simpleName ?: "null — actual=${Sentry.getCurrentScopes().options.replayController?.javaClass?.name}"}")

        controller?.start()
        Log.d(App.TAG, "AFTER start()   replayId=${replayId()}, isRecording=${isRecording()}")

        navigateHome()
    }

    private fun navigateHome() {
        startActivity(Intent(this, HomeActivity::class.java))
    }

    private fun replayId() = (Sentry.replay() as? ReplayIntegration)?.replayId?.toString() ?: "null"
    private fun isRecording() = (Sentry.replay() as? ReplayIntegration)?.isRecording
}
