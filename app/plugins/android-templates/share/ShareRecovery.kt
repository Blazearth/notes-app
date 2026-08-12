package {{PACKAGE}}.share

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs once on every process start -- wired into `MainApplication.onCreate`
 * by the `withAndroidShareReceiver` config plugin, so it fires on an app
 * launch just as much as on a cold start triggered by tapping the app icon
 * after a share.
 *
 * WorkManager already persists enqueued work across ordinary process death by
 * itself, so this is not a blanket "redo everything" pass -- it exists for
 * two specific gaps [CaptureStore] closes and WorkManager's own database
 * cannot:
 *
 * 1. [ShareWork.enqueue]'s call in `ShareReceiverActivity` writes to
 *    WorkManager's database on a background executor thread; a kill in the
 *    instant after `finish()` can lose that write before it lands, even
 *    though [CaptureStore.insert] (synchronous, already committed) did not.
 * 2. Force-Stopping the app -- a real path here, since this bug is reported
 *    as OEM/battery-optimization-dependent -- cancels *all* of an app's
 *    scheduled WorkManager work outright, with no trace left in WorkManager's
 *    own state to recover from.
 *
 * [CaptureStore] is the source of truth in both cases: anything it still
 * lists as incomplete gets re-enqueued here, idempotently.
 */
object ShareRecovery {
    private const val MAX_FAILURE_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days

    fun recoverPendingCaptures(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = CaptureStore.getInstance(appContext)
                val pending = store.listIncomplete()
                if (pending.isNotEmpty()) {
                    ShareLogger.d("[RECOVERY] Resuming ${pending.size} pending capture(s)")
                }
                for (record in pending) {
                    ShareLogger.d("[RECOVERY] Resuming pending capture: ${record.id}")
                    ShareWork.enqueue(appContext, record.id, record.kind)
                }
                store.pruneOldFailures(MAX_FAILURE_AGE_MILLIS)
            } catch (e: Exception) {
                ShareLogger.e("[RECOVERY] Recovery scan threw", e)
            }
        }
    }
}
