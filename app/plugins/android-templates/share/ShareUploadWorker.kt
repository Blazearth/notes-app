package {{PACKAGE}}.share

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads one persisted [CaptureRecord] of kind [CaptureKind.URL] to
 * `POST /v1/saves`, off the main thread.
 *
 * Unlike the previous version, this worker does not carry the URL in its own
 * input data -- it reads [CaptureStore] by [ShareWork.KEY_CAPTURE_ID] instead,
 * so a worker re-created by [ShareRecovery] after a cold start (a fresh
 * `OneTimeWorkRequest`, not a resumed one) still has the content and the
 * retry history to work from. `retry_count` therefore lives in the store,
 * not in WorkManager's own (per-request) `runAttemptCount` -- the latter
 * resets on every re-enqueue and would let a capture retry forever across
 * enough app restarts.
 *
 * The idempotency key is generated once when the capture is first persisted
 * and carried in the record, so every attempt -- WorkManager retry or
 * recovery re-enqueue alike -- reuses it; the server dedupes on a repeated
 * `Idempotency-Key` (see api/.../SaveService.create), which is what stops a
 * retried upload from creating a second save.
 */
class ShareUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val captureId = inputData.getString(ShareWork.KEY_CAPTURE_ID) ?: run {
            ShareLogger.e("[PROCESS] Failed: worker started with no capture id")
            return@withContext Result.failure()
        }

        val store = CaptureStore.getInstance(applicationContext)
        val record = store.get(captureId) ?: run {
            // Already completed (and its row deleted), or somehow never
            // persisted -- either way there is nothing left to do, and this
            // is not a failure of this run.
            ShareLogger.w("[PROCESS] No capture record for $captureId, skipping")
            return@withContext Result.success()
        }

        if (record.status == CaptureStatus.COMPLETED) return@withContext Result.success()
        if (record.retryCount >= MAX_ATTEMPTS) {
            ShareLogger.e("[PROCESS] Failed: $captureId exceeded $MAX_ATTEMPTS attempts")
            store.updateStatus(captureId, CaptureStatus.FAILED, "Exceeded max retry attempts")
            return@withContext Result.failure()
        }

        ShareLogger.d("[PROCESS] Started: $captureId")
        store.updateStatus(captureId, CaptureStatus.PROCESSING)

        val config = ShareConfigStore.read(applicationContext)
        val accessToken = config?.accessToken
        val apiBaseUrl = config?.apiBaseUrl
        if (accessToken.isNullOrBlank() || apiBaseUrl.isNullOrBlank()) {
            // Signed out, or the config was never mirrored -- retrying will not fix this.
            ShareLogger.e("[PROCESS] Failed: $captureId -- not signed in")
            store.updateStatus(captureId, CaptureStatus.FAILED, "Not signed in")
            return@withContext Result.failure()
        }

        try {
            when (val status = postSave(apiBaseUrl, accessToken, record)) {
                200, 202 -> {
                    ShareLogger.d("[PROCESS] Completed: $captureId")
                    store.delete(captureId)
                    Result.success()
                }
                401, 403 -> {
                    ShareLogger.e("[PROCESS] Failed: $captureId -- HTTP $status")
                    store.updateStatus(captureId, CaptureStatus.FAILED, "HTTP $status")
                    Result.failure()
                }
                429, in 500..599 -> {
                    ShareLogger.w("[PROCESS] Retry scheduled: $captureId -- HTTP $status")
                    store.incrementRetryAndMarkPending(captureId, "HTTP $status")
                    Result.retry()
                }
                else -> {
                    ShareLogger.e("[PROCESS] Failed: $captureId -- HTTP $status")
                    store.updateStatus(captureId, CaptureStatus.FAILED, "HTTP $status")
                    Result.failure()
                }
            }
        } catch (e: IOException) {
            ShareLogger.w("[PROCESS] Retry scheduled: $captureId -- ${e.message}")
            store.incrementRetryAndMarkPending(captureId, e.message)
            Result.retry()
        }
    }

    private fun postSave(apiBaseUrl: String, accessToken: String, record: CaptureRecord): Int {
        val connection = URL("$apiBaseUrl/v1/saves").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Idempotency-Key", record.idempotencyKey)

            val body = JSONObject().apply {
                put("sourceType", "url")
                put("sourceUrl", record.content)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
