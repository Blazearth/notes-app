package {{PACKAGE}}.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The OS "Share to Weavr" target — the whole of Android silent capture.
 *
 * Theme.NoDisplay + noHistory + excludeFromRecents (see the manifest
 * entry the config plugin adds) mean this is never actually seen: it reads
 * the shared content, enqueues the appropriate WorkManager worker so the upload
 * survives after this Activity is gone, shows a brief Toast, and finishes.
 * See CLAUDE.md "Capture flow: silent by default" for why opening the app is
 * opt-in rather than the default.
 *
 * Supported MIME types:
 *  - text/plain  -> ShareUploadWorker  (URL / text)
 *  - image types -> ShareImageUploadWorker (screenshot / photo)
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
        finish()
    }

    private fun handleShare() {
        val type = intent?.type ?: run {
            toast("Weavr couldn't read what was shared.")
            return
        }

        val config = ShareConfigStore.read(applicationContext)
        if (config?.accessToken.isNullOrBlank() || config?.apiBaseUrl.isNullOrBlank()) {
            toast("Sign in to Weavr to save.")
            return
        }

        when {
            type.startsWith("image/") -> handleImageShare(config!!)
            type.startsWith("text/")  -> handleTextShare(config!!)
            else -> toast("Weavr doesn't support sharing this type of content yet.")
        }
    }

    // -------------------------------------------------------------------------
    // Text / URL path (unchanged from original)
    // -------------------------------------------------------------------------

    private fun handleTextShare(config: ShareConfig) {
        val sharedText = readSharedText(intent)
        if (sharedText.isNullOrBlank()) {
            toast("Weavr couldn't read what was shared.")
            return
        }

        val request = OneTimeWorkRequestBuilder<ShareUploadWorker>()
            .setInputData(
                workDataOf(
                    ShareUploadWorker.KEY_SOURCE_URL to sharedText,
                    ShareUploadWorker.KEY_IDEMPOTENCY_KEY to UUID.randomUUID().toString(),
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueue(request)
        toast("Saved to Weavr")

        if (config.openAppWhenSaving) {
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    // -------------------------------------------------------------------------
    // Image path — screenshot / photo share
    // -------------------------------------------------------------------------

    private fun handleImageShare(config: ShareConfig) {
        val imageUri = readSharedImageUri(intent) ?: run {
            toast("Weavr couldn't read the image.")
            return
        }

        // Grant ourselves permission to read the content URI from this Activity's
        // process. WorkManager workers run in the same process so the grant is
        // inherited; we pass the URI string and re-open it in the worker.
        grantUriPermission(packageName, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val request = OneTimeWorkRequestBuilder<ShareImageUploadWorker>()
            .setInputData(
                workDataOf(
                    ShareImageUploadWorker.KEY_IMAGE_URI to imageUri.toString(),
                    ShareImageUploadWorker.KEY_IDEMPOTENCY_KEY to UUID.randomUUID().toString(),
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueue(request)
        toast("Screenshot saved to Weavr ✓")

        if (config.openAppWhenSaving) {
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
    }

    private fun readSharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        @Suppress("DEPRECATION")
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
