package {{PACKAGE}}.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the image [ShareReceiverActivity] already copied into private
 * storage, compresses it, and uploads it to `POST /v1/saves/image` as
 * `multipart/form-data`.
 *
 * Like [ShareUploadWorker], this reads its [CaptureRecord] from
 * [CaptureStore] by id rather than carrying the payload in WorkManager's
 * input data, so retry history and content both survive a cold-start
 * recovery re-enqueue.
 *
 * ### Why compress here and not in the Activity?
 * The Activity's local copy is a raw byte-for-byte copy done synchronously
 * on the main thread, bounded to a few seconds -- decoding to a [Bitmap] and
 * re-encoding as JPEG is comparatively expensive and has no reason to block
 * the "is this durably saved yet" answer the Activity exists to give
 * quickly. Screenshots from modern phones can be 3-8 MB as PNGs; compressing
 * to JPEG 85% typically brings that to 200-800 KB, which matters for upload
 * time on a flaky connection and for Gemini's input-token cost.
 *
 * The local copy is deleted once its capture reaches a terminal state
 * (COMPLETED or FAILED) -- see [cleanupFile] -- but kept across RETRY_PENDING
 * so a later attempt still has bytes to read.
 */
class ShareImageUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val captureId = inputData.getString(ShareWork.KEY_CAPTURE_ID) ?: run {
            ShareLogger.e("[PROCESS] Failed: worker started with no capture id")
            return@withContext Result.failure()
        }

        val store = CaptureStore.getInstance(applicationContext)
        val record = store.get(captureId) ?: run {
            ShareLogger.w("[PROCESS] No capture record for $captureId, skipping")
            return@withContext Result.success()
        }

        if (record.status == CaptureStatus.COMPLETED) return@withContext Result.success()
        if (record.retryCount >= MAX_ATTEMPTS) {
            ShareLogger.e("[PROCESS] Failed: $captureId exceeded $MAX_ATTEMPTS attempts")
            store.updateStatus(captureId, CaptureStatus.FAILED, "Exceeded max retry attempts")
            cleanupFile(record.content)
            return@withContext Result.failure()
        }

        ShareLogger.d("[PROCESS] Started: $captureId")
        store.updateStatus(captureId, CaptureStatus.PROCESSING)

        val config = ShareConfigStore.read(applicationContext)
        val accessToken = config?.accessToken
        val apiBaseUrl = config?.apiBaseUrl
        if (accessToken.isNullOrBlank() || apiBaseUrl.isNullOrBlank()) {
            ShareLogger.e("[PROCESS] Failed: $captureId -- not signed in")
            store.updateStatus(captureId, CaptureStatus.FAILED, "Not signed in")
            return@withContext Result.failure()
        }

        val imageBytes = try {
            readAndCompressImage(record.content)
        } catch (e: Exception) {
            ShareLogger.w("[PROCESS] Retry scheduled: $captureId -- image decode failed: ${e.message}")
            store.incrementRetryAndMarkPending(captureId, "Decode failed: ${e.message}")
            return@withContext Result.retry()
        }

        if (imageBytes == null || imageBytes.isEmpty()) {
            ShareLogger.e("[PROCESS] Failed: $captureId -- empty or unreadable image")
            store.updateStatus(captureId, CaptureStatus.FAILED, "Empty image")
            cleanupFile(record.content)
            return@withContext Result.failure()
        }

        try {
            when (val status = postImage(apiBaseUrl, accessToken, record.idempotencyKey, imageBytes)) {
                200, 202 -> {
                    ShareLogger.d("[PROCESS] Completed: $captureId")
                    store.delete(captureId)
                    cleanupFile(record.content)
                    Result.success()
                }
                401, 403 -> {
                    ShareLogger.e("[PROCESS] Failed: $captureId -- HTTP $status")
                    store.updateStatus(captureId, CaptureStatus.FAILED, "HTTP $status")
                    cleanupFile(record.content)
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
                    cleanupFile(record.content)
                    Result.failure()
                }
            }
        } catch (e: IOException) {
            ShareLogger.w("[PROCESS] Retry scheduled: $captureId -- ${e.message}")
            store.incrementRetryAndMarkPending(captureId, e.message)
            Result.retry()
        }
    }

    private fun readAndCompressImage(path: String): ByteArray? {
        val raw = File(path).readBytes()
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /**
     * POSTs the JPEG bytes to `POST /v1/saves/image` as `multipart/form-data`.
     *
     * Built manually rather than pulling in OkHttp -- the APK already
     * carries OkHttp transitively through WorkManager, but using
     * `HttpURLConnection` keeps this file self-contained and avoids a hard
     * dependency on the transitive version. The `Idempotency-Key` header is
     * sent even though the server does not dedupe image saves by key today
     * (a separate, pre-existing backend gap) -- forward-compatible for free,
     * and this file's own duplicate-prevention comes from
     * `enqueueUniqueWork`'s `KEEP` policy plus deleting the record on
     * success, not from the server honoring this header.
     */
    private fun postImage(apiBaseUrl: String, accessToken: String, idempotencyKey: String, imageBytes: ByteArray): Int {
        val boundary = "WeavrBoundary${System.currentTimeMillis()}"
        val connection = URL("$apiBaseUrl/v1/saves/image").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", idempotencyKey)

            connection.outputStream.use { out ->
                val header = "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"screenshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(imageBytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanupFile(path: String) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            // Best-effort -- a leftover file in our own private storage costs
            // disk space, never correctness.
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000 // upload can be slow
        private const val JPEG_QUALITY = 85
    }
}
