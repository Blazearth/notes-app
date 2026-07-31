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
 * Uploads one shared URL to `POST /v1/saves`, off the main thread.
 *
 * The idempotency key is generated once when [ShareReceiverActivity] enqueues
 * this worker and carried in the input data, so every WorkManager retry of
 * the same share reuses it — the server dedupes on a repeated
 * `Idempotency-Key` (see api/.../SaveService.create), which is what stops a
 * retried upload from creating a second save.
 */
class ShareUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()

        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: return@withContext Result.failure()
        val idempotencyKey = inputData.getString(KEY_IDEMPOTENCY_KEY) ?: return@withContext Result.failure()
        val config = ShareConfigStore.read(applicationContext)
        val accessToken = config?.accessToken
        val apiBaseUrl = config?.apiBaseUrl

        if (accessToken.isNullOrBlank() || apiBaseUrl.isNullOrBlank()) {
            // Signed out, or the config was never mirrored — retrying will not fix this.
            return@withContext Result.failure()
        }

        try {
            when (val status = postSave(apiBaseUrl, accessToken, idempotencyKey, sourceUrl)) {
                200, 202 -> Result.success()
                401, 403 -> Result.failure()
                429 -> Result.retry()
                in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (e: IOException) {
            Result.retry()
        }
    }

    private fun postSave(
        apiBaseUrl: String,
        accessToken: String,
        idempotencyKey: String,
        sourceUrl: String,
    ): Int {
        val connection = URL("$apiBaseUrl/v1/saves").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Idempotency-Key", idempotencyKey)

            val body = JSONObject().apply {
                put("sourceType", "url")
                put("sourceUrl", sourceUrl)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
        private const val MAX_ATTEMPTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
