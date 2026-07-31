package {{PACKAGE}}.share

import android.app.Activity
import android.content.Intent
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
 * `Theme.NoDisplay` + `noHistory` + `excludeFromRecents` (see the manifest
 * entry the config plugin adds) mean this is never actually seen: it reads
 * the shared text, enqueues [ShareUploadWorker] via WorkManager so the upload
 * survives after this Activity is gone, shows a brief Toast, and finishes.
 * See CLAUDE.md "Capture flow: silent by default" for why opening the app is
 * opt-in rather than the default.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
        finish()
    }

    private fun handleShare() {
        val sharedText = readSharedText(intent)
        if (sharedText.isNullOrBlank()) {
            toast("Weavr couldn't read what was shared.")
            return
        }

        val config = ShareConfigStore.read(applicationContext)
        if (config?.accessToken.isNullOrBlank() || config?.apiBaseUrl.isNullOrBlank()) {
            toast("Sign in to Weavr to save links.")
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

        if (config?.openAppWhenSaving == true) {
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
    }

    /** Only plain-text SEND is handled today — no image/PDF share target yet. */
    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
