package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Checks this fork's own GitHub releases for a newer version and installs one, in place
 * of the two things that would otherwise be needed to keep this personal build current:
 * BrightMarket (which not everyone installs — including whoever forked this) or manually
 * pulling and sideloading a new APK by hand.
 *
 * Deliberately dependency-free, matching the rest of the app: plain [HttpURLConnection] +
 * `org.json`, same as [com.gios.lightchat.api.BlueBubblesApi].
 *
 * Points at a fixed repo rather than reading it from anywhere configurable — this *is*
 * the fork it's built from, not a general-purpose updater, so there's nothing to point
 * elsewhere.
 */
object Updater {
    private const val REPO = "Zarrasko/BrightChat"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** One release worth of update: what to show, and where to get it. */
    data class Update(val version: String, val notes: String?, val downloadUrl: String)

    /**
     * The latest release, if it's newer than what's running — null if this is already
     * current, the repo has no releases yet, or the check just failed (no network, GitHub
     * down, whatever). A failed check reads the same as "nothing to update to" rather than
     * an error the user has to do anything about; there's always a next check.
     */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val root = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = root.optString("tag_name").removePrefix("v")
                if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
                val assets = root.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                apkUrl ?: return@withContext null
                Update(version = tag, notes = root.optString("body").takeIf { it.isNotBlank() }, downloadUrl = apkUrl)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * "2.36.1" > "2.35.0", component by component, missing components treated as 0 — so a
     * three-segment release version compares fine against this build's four-segment
     * CI-stamped one (e.g. "2.35.0.42") and vice versa. Not real semver (no pre-release
     * tags to reason about here, every release is a plain build), just enough to answer
     * "is that newer than this."
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val ri = r.getOrElse(i) { 0 }
            val li = l.getOrElse(i) { 0 }
            if (ri != li) return ri > li
        }
        return false
    }

    /**
     * Downloads [update]'s APK to the cache, or null on failure. The caller decides what
     * "failed" means to the user — this just doesn't throw into the middle of a Settings
     * screen.
     */
    suspend fun download(context: Context, update: Update): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Named by version rather than reused across calls, so a previous partial
            // download from an interrupted attempt can't be mistaken for a complete one.
            val dest = File(dir, "BrightChat-${update.version}.apk")
            val conn = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                dest
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Whether the OS will let this app prompt to install another APK at all — the one
     *  runtime permission this needs, and it has no in-dialog request; only Settings. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Allow from this source" screen for this app specifically. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands [apk] to the system installer. Fire-and-forget: once this returns, it's the
     *  installer's UI on screen, not this app's — there's no completion callback to wait on. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }
}
