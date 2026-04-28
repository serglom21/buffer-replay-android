package com.example.replayzero

import android.app.Application
import android.util.Log
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.replay.ReplayIntegration

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        SentryAndroid.init(this) { options ->
            options.dsn = "https://514d5e38a4003c2cf958c53c77679925@o4508236363464704.ingest.us.sentry.io/4508847444918272"
            options.isDebug = true
            options.sessionReplay.onErrorSampleRate = Double.MIN_VALUE
            options.sessionReplay.sessionSampleRate = Double.MIN_VALUE
            options.sessionReplay.setMaskAllText(false)
            options.sessionReplay.setMaskAllImages(false)
        }

        (Sentry.getCurrentScopes().options.replayController as? ReplayIntegration)?.stop()

        Log.d(TAG, "Sentry init — onErrorSampleRate=Double.MIN_VALUE, sessionSampleRate=Double.MIN_VALUE, stop() called immediately")
    }

    companion object {
        const val TAG = "ReplayZero"
    }
}
