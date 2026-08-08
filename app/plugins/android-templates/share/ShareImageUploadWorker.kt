package {{PACKAGE}}.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a shared image from a content URI, compresses it, and uploads it to
 * `POST /v1/saves/image` as a `multipart/form-data` request.
 *
 * ### Why compress on-device?
 * Screenshots from modern phones can be 3–8 MB as PNGs. The backend caps
 * uploads at 10 MB but compressing to JPEG 85% typically brings a screenshot
 * to 200–800 KB, reducing upload time on flaky connections and lowering
 * Gemini input-token costs (fewer pixels = fewer tokens in the inline_data
 * part).
 *
 * ### Idempotency
 * The idempotency key is generated once in [ShareReceiverActivity] and carried
 * in the input data. Each WorkManager retry reuses the same key. The server
 * does **not** dedupe image saves by key today (unlike URL saves), but
 * WorkManager's unique-work policy ensures only one worker runs per share event.
 */
class ShareImageUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runAttemptCount >= MAX_ATTEMPTS) return@withContext Result.failure()

        val imageUriStr = inputData.getString(KEY_IMAGE_URI) ?: return@withContext Result.failure()
        val config = ShareConfigStore.read(applicationContext)
        val accessToken = config?.accessToken
        val apiBaseUrl = config?.apiBaseUrl

        if (accessToken.isNullOrBlank() || apiBaseUrl.isNullOrBlank()) {
            return@withContext Result.failure()
        }

        val imageBytes = try {
            readAndCompressImage(Uri.parse(imageUriStr))
        } catch (e: Exception) {
            return@withContext Result.failure()
        }

        if (imageBytes == null || imageBytes.isEmpty()) {
            return@withContext Result.failure()
        }

        try {
            when (val status = postImage(apiBaseUrl, accessToken, imageBytes)) {
                200, 202 -> Result.success()
                401, 403 -> Result.failure()
                429      -> Result.retry()
                in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (e: IOException) {
            Result.retry()
        }
    }

    /**
     * Reads the image from the content URI, decodes it as a [Bitmap], and
     * re-encodes as JPEG at [JPEG_QUALITY]% to bound the upload size.
     */
    private fun readAndCompressImage(uri: Uri): ByteArray? {
        val resolver = applicationContext.contentResolver
        val inputStream = resolver.openInputStream(uri) ?: return null
        val raw = inputStream.use { it.readBytes() }

        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /**
     * POSTs the JPEG bytes to `POST /v1/saves/image` as `multipart/form-data`.
     *
     * We build the multipart body manually rather than pulling in OkHttp —
     * the APK already carries OkHttp transitively through WorkManager, but
     * using `HttpURLConnection` keeps this file self-contained and avoids a
     * hard dependency on the transitive version.
     */
    private fun postImage(apiBaseUrl: String, accessToken: String, imageBytes: ByteArray): Int {
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

            connection.outputStream.use { out ->
                // Part: image
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

    companion object {
        const val KEY_IMAGE_URI = "image_uri"
        const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
        private const val MAX_ATTEMPTS = 8
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000    // upload can be slow
        private const val JPEG_QUALITY = 85
    }
}
