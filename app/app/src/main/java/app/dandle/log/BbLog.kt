package app.dandle.log

import android.os.Build
import android.util.Log
import app.dandle.BuildConfig

/**
 * Debug logging gate for the app (product decision: robust debug logging is the DEFAULT so QA
 * is never diagnosing blind). Tags are FIXED and consumed by QA tooling (scripts/qa/applog.sh):
 *
 *  - `BB/API`   every HTTP request/response (method, URL, status, duration, body)
 *  - `BB/LOGIN` login dance steps (csrf fetched, form posted, profile fetched, key stored)
 *  - `BB/SYNC`  outbox drain start/end, per-op dispatch + outcome + failure reason
 *  - `BB/TIMER` timer start/stop/discard/restore with ids + elapsed
 *  - `BB/UI`    one line per user action (quick actions, saves, edits, child switch, sign out)
 *
 * Unit tests stay log-agnostic: the default gate is off under Robolectric/plain JVM tests
 * (fingerprint check) and every call is fail-safe, so the mockable android.jar never throws.
 * QA/device builds can flip [enabled] at runtime if a future settings screen needs it.
 */
object BbLog {

    const val TAG_API = "BB/API"
    const val TAG_LOGIN = "BB/LOGIN"
    const val TAG_SYNC = "BB/SYNC"
    const val TAG_TIMER = "BB/TIMER"
    const val TAG_UI = "BB/UI"

    /**
     * Cold-start milestones (App.onCreate segments, activity create, nav start destination,
     * dashboard content ready) — startup attribution for QA/perf work without a profiler.
     */
    const val TAG_START = "BB/START"

    /** Widget render/refresh failures (blank-widget diagnosis; never logged on success). */
    const val TAG_WIDGET = "BB/WIDGET"

    /** Injectable/stubbable master switch (checked on every call). */
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG && !(Build.FINGERPRINT ?: "").contains("robolectric", ignoreCase = true)

    fun d(tag: String, message: String) {
        if (!enabled) return
        runCatching { Log.d(tag, message) } // mockable android.jar throws outside instrumentation
    }
}
