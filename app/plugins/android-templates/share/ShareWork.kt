package {{PACKAGE}}.share

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The one place that turns a [CaptureRecord] id into a WorkManager request --
 * used both by [ShareReceiverActivity] (the first enqueue, right after the
 * capture is durably persisted) and by [ShareRecovery] (re-enqueuing on cold
 * start anything that never finished). Sharing this means both call sites
 * use the identical unique work name, so a recovery run can never schedule a
 * second worker for a capture that is already running or already queued --
 * [ExistingWorkPolicy.KEEP] makes the re-enqueue a safe no-op in that case
 * rather than a duplicate upload.
 */
object ShareWork {
    const val KEY_CAPTURE_ID = "capture_id"

    fun enqueue(context: Context, captureId: String, kind: CaptureKind) {
        val workerClass: Class<out ListenableWorker> = when (kind) {
            CaptureKind.URL -> ShareUploadWorker::class.java
            CaptureKind.IMAGE -> ShareImageUploadWorker::class.java
        }

        val request = OneTimeWorkRequest.Builder(workerClass)
            .setInputData(workDataOf(KEY_CAPTURE_ID to captureId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(captureId), ExistingWorkPolicy.KEEP, request)
    }

    private fun uniqueWorkName(captureId: String): String = "share-upload-$captureId"
}
