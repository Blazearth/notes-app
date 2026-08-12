package {{PACKAGE}}.share

import android.util.Log

/**
 * One tag for every log line the capture pipeline emits, so `adb logcat -s
 * WeavrShare` shows the whole lifecycle of a share in order: intent receipt,
 * local persistence, enqueue, worker start/finish, and any cold-start
 * recovery. Deliberately plain `android.util.Log` -- this is a debugging
 * trail, not telemetry, and the point is that it works with zero setup on a
 * device that shows the bug (see the capture-reliability fix in CLAUDE.md).
 */
object ShareLogger {
    private const val TAG = "WeavrShare"

    fun d(message: String) = Log.d(TAG, message)
    fun w(message: String, throwable: Throwable? = null) = Log.w(TAG, message, throwable)
    fun e(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)
}
