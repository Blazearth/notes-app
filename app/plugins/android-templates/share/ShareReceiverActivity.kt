package {{PACKAGE}}.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * The OS "Share to Weavr" target -- the whole of Android silent capture.
 *
 * Theme.NoDisplay + noHistory + excludeFromRecents (see the manifest entry
 * the config plugin adds) mean this is never actually seen: it reads the
 * shared content, durably persists it, enqueues a WorkManager worker for the
 * upload, shows a brief Toast, and finishes.
 *
 * ### Reliability model
 * Android can kill this process the instant [finish] returns -- that is the
 * assumption this whole file is written against, not an edge case. Nothing
 * here may depend on surviving past that point:
 *
 * - [CaptureStore.insert] is a **synchronous** SQLite write on the calling
 *   thread. By the time "Saved to Weavr" is shown, the capture is durably on
 *   disk -- independent of `WorkManager.enqueue`, which persists to *its own*
 *   database asynchronously and can lose that write in the exact same window
 *   a process kill would otherwise hit.
 * - The success toast fires **only** after that write succeeds. A failed
 *   write shows "Couldn't save this yet" instead of a false positive --
 *   see CLAUDE.md's Share → Weavr capture-flow fix for why the previous
 *   version's "toast first, persist later (maybe)" ordering is exactly the
 *   bug being fixed here.
 * - [ShareRecovery] (wired into `MainApplication.onCreate`) re-enqueues
 *   anything this activity persisted but a WorkManager enqueue or a
 *   Force-Stop later lost, so a queued item cannot disappear just because
 *   the process died.
 *
 * Supported MIME types:
 *  - text/plain  -> ShareUploadWorker       (URL / text)
 *  - image types -> ShareImageUploadWorker  (screenshot / photo)
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareLogger.d("[SHARE] Intent received")
        handleShare()
        finish()
    }

    private fun handleShare() {
        val type = intent?.type ?: run {
            ShareLogger.w("[SHARE] No MIME type on the intent")
            toast("Weavr couldn't read what was shared.")
            return
        }

        val config = ShareConfigStore.read(applicationContext)
        if (config?.accessToken.isNullOrBlank() || config?.apiBaseUrl.isNullOrBlank()) {
            ShareLogger.w("[SHARE] No session mirrored -- user must sign in")
            toast("Sign in to Weavr to save.")
            return
        }

        when {
            type.startsWith("image/") -> handleImageShare(config!!)
            type.startsWith("text/") -> handleTextShare(config!!)
            else -> {
                ShareLogger.w("[SHARE] Unsupported MIME type: $type")
                toast("Weavr doesn't support sharing this type of content yet.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Text / URL path
    // -------------------------------------------------------------------------

    private fun handleTextShare(config: ShareConfig) {
        val sharedText = readSharedText(intent)
        if (sharedText.isNullOrBlank()) {
            ShareLogger.w("[SHARE] Text intent carried no usable text")
            toast("Weavr couldn't read what was shared.")
            return
        }
        ShareLogger.d("[SHARE] URL extracted")

        val record = CaptureRecord(
            id = UUID.randomUUID().toString(),
            kind = CaptureKind.URL,
            content = sharedText,
            sourceApp = referrerPackage(),
            createdAt = System.currentTimeMillis(),
            status = CaptureStatus.PERSISTED,
            retryCount = 0,
            errorMessage = null,
            idempotencyKey = UUID.randomUUID().toString(),
        )
        persistAndEnqueue(record, config, successMessage = "✓ Saved to Weavr")
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    // -------------------------------------------------------------------------
    // Image path -- screenshot / photo share
    // -------------------------------------------------------------------------

    private fun handleImageShare(config: ShareConfig) {
        val sourceUri = readSharedImageUri(intent) ?: run {
            ShareLogger.w("[SHARE] Image intent carried no EXTRA_STREAM")
            toast("Weavr couldn't read the image.")
            return
        }

        // Copy the bytes into our own private storage *now*, synchronously,
        // rather than only remembering the content:// URI. The read grant on
        // that URI is scoped to this process's lifetime -- exactly the
        // lifetime the rest of this file assumes can end at any moment -- and
        // the source app's provider is not guaranteed reachable by the time a
        // delayed WorkManager retry (or a cold-start recovery, possibly
        // minutes or hours later) actually runs.
        val localFile = copyImageSynchronously(sourceUri)
        if (localFile == null) {
            ShareLogger.e("[SHARE] Persistence failed: could not copy the shared image")
            toast("Couldn't save this yet. Tap to retry.")
            return
        }
        ShareLogger.d("[SHARE] URL extracted") // image bytes stand in for "content extracted" here

        val record = CaptureRecord(
            id = UUID.randomUUID().toString(),
            kind = CaptureKind.IMAGE,
            content = localFile.absolutePath,
            sourceApp = referrerPackage(),
            createdAt = System.currentTimeMillis(),
            status = CaptureStatus.PERSISTED,
            retryCount = 0,
            errorMessage = null,
            idempotencyKey = UUID.randomUUID().toString(),
        )
        persistAndEnqueue(record, config, successMessage = "✓ Screenshot saved to Weavr")
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

    /**
     * Blocks the calling (main) thread on purpose -- see the class doc.
     * Bounded by [IMAGE_COPY_TIMEOUT_MS] so a slow or network-backed content
     * provider degrades to "couldn't save yet" rather than hanging the
     * activity indefinitely.
     */
    private fun copyImageSynchronously(uri: Uri): File? = runBlocking {
        withTimeoutOrNull(IMAGE_COPY_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val dir = File(applicationContext.filesDir, "share_captures").apply { mkdirs() }
                    val dest = File(dir, "${UUID.randomUUID()}.img")
                    val input = applicationContext.contentResolver.openInputStream(uri) ?: return@withContext null
                    input.use { source -> dest.outputStream().use { output -> source.copyTo(output) } }
                    dest
                } catch (e: Exception) {
                    ShareLogger.e("[SHARE] Image copy threw", e)
                    null
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared persist -> enqueue -> confirm sequence
    // -------------------------------------------------------------------------

    private fun persistAndEnqueue(record: CaptureRecord, config: ShareConfig, successMessage: String) {
        ShareLogger.d("[SHARE] Persisting capture")
        val store = CaptureStore.getInstance(applicationContext)
        if (!store.insert(record)) {
            ShareLogger.e("[SHARE] Persistence failed")
            toast("Couldn't save this yet. Tap to retry.")
            return
        }
        ShareLogger.d("[SHARE] Capture persisted: ${record.id}")

        // Only now -- after a synchronous, durable local write -- do we tell
        // the user it's saved. Everything from here down is best-effort and
        // recoverable; nothing above this line was allowed to be.
        toast(successMessage)

        ShareWork.enqueue(applicationContext, record.id, record.kind)
        store.updateStatus(record.id, CaptureStatus.QUEUED)
        ShareLogger.d("[SHARE] Queueing processing")
        ShareLogger.d("[SHARE] Success returned to Share Sheet")

        if (config.openAppWhenSaving) {
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
    }

    private fun referrerPackage(): String? = try {
        referrer?.host
    } catch (e: Exception) {
        null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val IMAGE_COPY_TIMEOUT_MS = 5_000L
    }
}
