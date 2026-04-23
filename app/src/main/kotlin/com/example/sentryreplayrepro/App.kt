package com.example.sentryreplayrepro

import android.app.Application
import android.content.Context
import android.util.Log
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.android.replay.ReplayIntegration

class App : Application() {

    // Cached after init so activities can mutate sample rates and access the replay controller
    // without going through deprecated Hub APIs.
    lateinit var sentryOptions: SentryAndroidOptions
        private set

    override fun onCreate() {
        super.onCreate()

        SentryAndroid.init(this) { options ->
            options.dsn = "https://514d5e38a4003c2cf958c53c77679925@o4508236363464704.ingest.us.sentry.io/4508847444918272" // TODO: replace with your DSN
            options.isDebug = true        // enables SDK debug logging to logcat

            // Customer's approach: Double.MIN_VALUE (not 0.0) keeps replay "enabled"
            // because the SDK's enabled-check is `> 0`. Setting to 0.0 disables replay
            // entirely. MIN_VALUE is the smallest positive double, so it passes the check
            // but effectively never samples on its own — manual captureReplay() is the trigger.
            options.sessionReplay.onErrorSampleRate = Double.MIN_VALUE
            options.sessionReplay.sessionSampleRate = Double.MIN_VALUE

            // Unmask everything so replay recordings show full UI content.
            options.sessionReplay.setMaskAllText(false)
            options.sessionReplay.setMaskAllImages(false)

            sentryOptions = options
        }

        Log.d(TAG, "=== Sentry initialized ===")
        Log.d(TAG, "  dsn               = ${sentryOptions.dsn}")
        Log.d(TAG, "  onErrorSampleRate = ${sentryOptions.sessionReplay.onErrorSampleRate}  (Double.MIN_VALUE = ${Double.MIN_VALUE})")
        Log.d(TAG, "  sessionSampleRate = ${sentryOptions.sessionReplay.sessionSampleRate}")
        Log.d(TAG, "  replay enabled?   = ${(sentryOptions.sessionReplay.onErrorSampleRate ?: 0.0) > 0 || (sentryOptions.sessionReplay.sessionSampleRate ?: 0.0) > 0}")
    }

    companion object {
        const val TAG = "ReplayRepro"

        // TODO: set your Sentry org slug so DebugActivity can construct the replay URL.
        // Found in Settings → General Settings in the Sentry web UI (e.g. "acme-corp").
        const val SENTRY_ORG_SLUG = "YOUR_ORG_SLUG" // TODO: replace with your org slug

        fun get(context: Context): App = context.applicationContext as App

        // ---------------------------------------------------------------------------
        // Shared replay state helpers — used by all three activities
        // ---------------------------------------------------------------------------

        /**
         * Returns the current replay ID as the 32-char no-dash string that Sentry URLs need.
         * SentryId.toString() strips dashes by design.
         */
        fun getReplayId(): String {
            return try {
                (Sentry.replay() as? ReplayIntegration)?.replayId?.toString() ?: "null"
            } catch (e: Exception) {
                Log.w(TAG, "getReplayId error: ${e.message}")
                "error(${e.message})"
            }
        }

        /**
         * Cast to ReplayIntegration to read the internal isRecording field.
         * captureReplay() is also not on the public IReplayController interface —
         * same cast is needed there.
         */
        fun getIsRecording(): Boolean? {
            return try {
                val replay = Sentry.replay() ?: return null
                (replay as? ReplayIntegration)?.isRecording
            } catch (e: Exception) {
                Log.w(TAG, "getIsRecording error: ${e.message}")
                null
            }
        }

        /**
         * Calls captureReplay(isTerminating = false) — the customer's trigger for their
         * Shake-to-Report flow. isTerminating=false means "flush buffer now, keep recording";
         * true is only used by crash handlers.
         *
         * Strategy: try options.replayController first (cached reference), fall back to
         * Sentry.replay(). Both need casting because captureReplay() is on ReplayIntegration,
         * not on the public IReplayController interface.
         *
         * If NEITHER cast succeeds at runtime, the log will tell you — check the actual
         * class name and update the cast target accordingly.
         */
        fun doCapture(app: App): Boolean {
            return try {
                val viaOptions = app.sentryOptions.replayController as? ReplayIntegration
                if (viaOptions != null) {
                    Log.d(TAG, "captureReplay: calling via options.replayController (ReplayIntegration)")
                    viaOptions.captureReplay(false)
                    true
                } else {
                    Log.w(TAG, "captureReplay: options.replayController class = ${app.sentryOptions.replayController?.javaClass?.name}")
                    val viaSentry = Sentry.replay() as? ReplayIntegration
                    if (viaSentry != null) {
                        Log.d(TAG, "captureReplay: calling via Sentry.replay() cast to ReplayIntegration")
                        viaSentry.captureReplay(false)
                        true
                    } else {
                        Log.e(TAG, "captureReplay: BOTH casts failed. Sentry.replay() class = ${Sentry.replay()?.javaClass?.name}")
                        Log.e(TAG, "captureReplay: Update the cast target type in App.doCapture()")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureReplay threw: ${e.message}", e)
                false
            }
        }

        /**
         * Reflects into ReplayIntegration to read the captureStrategy field.
         * Returns a string identifying the strategy class and the replay_type value
         * that would appear in the Sentry envelope payload:
         *   "SessionCaptureStrategy → envelope replay_type='session'" = SDK is in session mode
         *   "BufferCaptureStrategy  → envelope replay_type='buffer'"  = SDK is still in buffer mode
         *
         * Tries the known field name "captureStrategy" first, then falls back to scanning
         * all declared fields for anything with "strategy" in its name or type.
         */
        fun getCaptureStrategyInfo(): String {
            return try {
                val replay = Sentry.replay() as? ReplayIntegration ?: return "replay=null"
                var field = try {
                    ReplayIntegration::class.java.getDeclaredField("captureStrategy")
                } catch (e: NoSuchFieldException) {
                    replay.javaClass.declaredFields.firstOrNull { f ->
                        f.name.contains("strategy", ignoreCase = true) ||
                            f.type.simpleName.contains("Strategy", ignoreCase = true)
                    }
                }
                if (field != null) {
                    field.isAccessible = true
                    val strategy = field.get(replay)
                    val className = strategy?.javaClass?.simpleName ?: "null"
                    val replayType = when {
                        className.contains("Session", ignoreCase = true) -> "session"
                        className.contains("Buffer", ignoreCase = true) -> "buffer"
                        else -> "unknown"
                    }
                    "$className → envelope replay_type='$replayType'"
                } else {
                    val allFields = replay.javaClass.declaredFields
                        .joinToString { "${it.name}:${it.type.simpleName}" }
                    "no strategy field found — all fields: $allFields"
                }
            } catch (e: Exception) {
                "getCaptureStrategyInfo error: ${e.message}"
            }
        }

        fun buildReplayUrl(replayId: String): String =
            "https://$SENTRY_ORG_SLUG.sentry.io/explore/replays/$replayId"
    }
}
