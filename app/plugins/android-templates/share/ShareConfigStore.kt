package {{PACKAGE}}.share

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What the share Activity and Worker need, mirrored from JS.
 *
 * The JS side (app/src/share/nativeShareConfig.ts) writes this as a plain JSON
 * file whenever the Supabase session or the "open app when saving" preference
 * changes. On Android there is no separate-process boundary the way iOS's
 * share extension has — this Activity and WorkManager worker run in the same
 * process as the JS runtime, just outside it — so a private file is enough;
 * no App Group / Keychain sharing is required here.
 */
data class ShareConfig(
    val accessToken: String?,
    val refreshToken: String?,
    val apiBaseUrl: String?,
    val openAppWhenSaving: Boolean,
)

object ShareConfigStore {
    private const val FILE_NAME = "weavr_share_config.json"

    fun read(context: Context): ShareConfig? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            ShareConfig(
                accessToken = json.optStringOrNull("accessToken"),
                refreshToken = json.optStringOrNull("refreshToken"),
                apiBaseUrl = json.optStringOrNull("apiBaseUrl"),
                openAppWhenSaving = json.optBoolean("openAppWhenSaving", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
