package {{PACKAGE}}.share

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class CaptureKind { URL, IMAGE }

/**
 * RECEIVED is deliberately not a value here: by definition nothing durable
 * exists yet at that point (see [ShareReceiverActivity]), so it is a log-only
 * moment, never a row in this table. Every value below is a state a capture
 * can be recovered from after the process dies.
 */
enum class CaptureStatus { PERSISTED, QUEUED, PROCESSING, RETRY_PENDING, FAILED, COMPLETED }

data class CaptureRecord(
    val id: String,
    val kind: CaptureKind,
    /** The shared URL for [CaptureKind.URL], or an absolute path to the locally-copied image for [CaptureKind.IMAGE]. */
    val content: String,
    val sourceApp: String?,
    val createdAt: Long,
    val status: CaptureStatus,
    val retryCount: Int,
    val errorMessage: String?,
    val idempotencyKey: String,
)

/**
 * The durable local queue the whole capture-reliability fix hinges on.
 *
 * [ShareReceiverActivity] blocks on [insert] -- a synchronous SQLite write on
 * the calling thread -- before it shows "Saved to Weavr". SQLite commits are
 * fsynced by default, so by the time that toast appears the capture survives
 * a process kill a moment later, independent of WorkManager's own enqueue,
 * which persists to *its* database asynchronously and can lose that write in
 * exactly the same window this store closes. See [ShareRecovery] for how a
 * cold start reconciles anything left incomplete.
 *
 * Plain `SQLiteOpenHelper` rather than Room: this project already avoids
 * Room (see `ShareConfigStore`'s plain-JSON-file precedent for the same
 * reason) and one small table needs none of Room's generated-code machinery.
 */
class CaptureStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                source_app TEXT,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT,
                idempotency_key TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Synchronous insert -- returns only once the row is durably committed. */
    fun insert(record: CaptureRecord): Boolean = try {
        val values = ContentValues().apply {
            put("id", record.id)
            put("kind", record.kind.name)
            put("content", record.content)
            put("source_app", record.sourceApp)
            put("created_at", record.createdAt)
            put("status", record.status.name)
            put("retry_count", record.retryCount)
            put("error_message", record.errorMessage)
            put("idempotency_key", record.idempotencyKey)
        }
        writableDatabase.insertOrThrow(TABLE, null, values)
        true
    } catch (e: Exception) {
        ShareLogger.e("[SHARE] CaptureStore insert threw", e)
        false
    }

    fun updateStatus(id: String, status: CaptureStatus, errorMessage: String? = null) {
        try {
            val values = ContentValues().apply {
                put("status", status.name)
                if (errorMessage != null) put("error_message", errorMessage)
            }
            writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            ShareLogger.e("[SHARE] CaptureStore updateStatus threw", e)
        }
    }

    /** Atomic "still trying" transition: bumps retry_count and flips to RETRY_PENDING together. */
    fun incrementRetryAndMarkPending(id: String, errorMessage: String?) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET status = ?, retry_count = retry_count + 1, error_message = ? WHERE id = ?",
                arrayOf(CaptureStatus.RETRY_PENDING.name, errorMessage, id),
            )
        } catch (e: Exception) {
            ShareLogger.e("[SHARE] CaptureStore incrementRetryAndMarkPending threw", e)
        }
    }

    fun get(id: String): CaptureRecord? = try {
        readableDatabase.query(TABLE, null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    } catch (e: Exception) {
        ShareLogger.e("[SHARE] CaptureStore get threw", e)
        null
    }

    /**
     * Everything not finished and not permanently given up on -- what
     * [ShareRecovery] re-enqueues on cold start. FAILED is excluded on
     * purpose: that status means the retry budget was already exhausted, and
     * resurrecting it on every launch would turn a bounded retry policy into
     * an unbounded one.
     */
    fun listIncomplete(): List<CaptureRecord> = try {
        readableDatabase.query(
            TABLE, null, "status != ?", arrayOf(CaptureStatus.FAILED.name), null, null, "created_at ASC",
        ).use { cursor ->
            val records = mutableListOf<CaptureRecord>()
            while (cursor.moveToNext()) records.add(cursor.toRecord())
            records
        }
    } catch (e: Exception) {
        ShareLogger.e("[SHARE] CaptureStore listIncomplete threw", e)
        emptyList()
    }

    /** Called once a capture reaches COMPLETED -- there is nothing left to recover. */
    fun delete(id: String) {
        try {
            writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            ShareLogger.e("[SHARE] CaptureStore delete threw", e)
        }
    }

    /** Bounds table growth: FAILED rows are kept for debugging, but not forever. */
    fun pruneOldFailures(maxAgeMillis: Long) {
        try {
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            writableDatabase.delete(
                TABLE, "status = ? AND created_at < ?", arrayOf(CaptureStatus.FAILED.name, cutoff.toString()),
            )
        } catch (e: Exception) {
            ShareLogger.e("[SHARE] CaptureStore pruneOldFailures threw", e)
        }
    }

    companion object {
        private const val DB_NAME = "weavr_share_captures.db"
        private const val DB_VERSION = 1
        private const val TABLE = "captures"

        @Volatile private var instance: CaptureStore? = null

        fun getInstance(context: Context): CaptureStore =
            instance ?: synchronized(this) {
                instance ?: CaptureStore(context.applicationContext).also { instance = it }
            }
    }
}

private fun Cursor.toRecord(): CaptureRecord = CaptureRecord(
    id = getString(getColumnIndexOrThrow("id")),
    kind = CaptureKind.valueOf(getString(getColumnIndexOrThrow("kind"))),
    content = getString(getColumnIndexOrThrow("content")),
    sourceApp = getString(getColumnIndexOrThrow("source_app")),
    createdAt = getLong(getColumnIndexOrThrow("created_at")),
    status = CaptureStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
    retryCount = getInt(getColumnIndexOrThrow("retry_count")),
    errorMessage = getString(getColumnIndexOrThrow("error_message")),
    idempotencyKey = getString(getColumnIndexOrThrow("idempotency_key")),
)
