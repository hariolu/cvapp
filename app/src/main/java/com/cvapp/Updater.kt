package com.cvapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update against GitHub Releases.
 *
 * There is no store here, so the app has to notice its own updates. It asks the
 * GitHub API for the latest release, compares versions, downloads the APK and
 * hands it to the system installer.
 *
 * What keeps this safe is not anything in this file: Android refuses to install
 * an update whose signature differs from the installed app's. A tampered or
 * substituted APK fails at install time, not at our discretion. That is also why
 * the release key can never be rotated once published — see docs/RELEASING.md.
 */
object Updater {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS    = 15_000

    /** Unauthenticated GitHub API allows 60 requests/hour per IP; nowhere near it at this rate. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    data class Release(
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long
    )

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Latest published release, or null if unreachable / malformed / has no APK attached. */
    suspend fun fetchLatest(repo: String = BuildConfig.UPDATE_REPO): Release? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("https://api.github.com/repos/$repo/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    // GitHub rejects API requests that arrive without a User-Agent.
                    setRequestProperty("User-Agent", "CVapp/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                    parseRelease(JSONObject(conn.inputStream.bufferedReader().readText()))
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private fun parseRelease(json: JSONObject): Release? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                ?: continue
            return Release(
                versionName = json.optString("tag_name").removePrefix("v").trim(),
                notes       = json.optString("body").trim(),
                apkUrl      = downloadUrl,
                apkName     = name,
                sizeBytes   = asset.optLong("size", 0L)
            )
        }
        // A release with notes but no APK attached is a half-finished publish, not an update.
        return null
    }

    // ── Version comparison ───────────────────────────────────────────────────

    /**
     * Dotted numeric comparison, so 1.10 correctly beats 1.9 — which a string
     * compare gets backwards. Missing components count as zero, so 1.8 == 1.8.0.
     * Anything non-numeric compares as 0 rather than throwing; a malformed tag
     * should mean "no update", never a crash on startup.
     */
    fun compareVersions(a: String, b: String): Int {
        val left  = a.removePrefix("v").split('.')
        val right = b.removePrefix("v").split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val r = right.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    fun isNewerThanInstalled(release: Release): Boolean =
        compareVersions(release.versionName, BuildConfig.VERSION_NAME) > 0

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Downloads to app-private storage — no storage permission, and the file is
     * cleaned up with the app. Reports progress as 0..100, or -1 while the total
     * size is unknown. Returns null on any failure, leaving no partial file.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
        if (!dir.exists() && !dir.mkdirs()) return@withContext null

        // One stale APK per install at most: clear the directory before writing.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.apkName)

        runCatching {
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "CVapp/${BuildConfig.VERSION_NAME}")
                instanceFollowRedirects = true   // assets 302 to objects.githubusercontent.com
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                var written = 0L
                var lastReported = -1
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // InputStream.read blocks and knows nothing about
                            // coroutines, so cancellation is only observed here.
                            // Without this the Cancel button dismisses the dialog
                            // and the download carries on in the background.
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                                // Only cross the main thread when the number actually changes.
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            } else if (lastReported != -1) {
                                lastReported = -1
                                onProgress(-1)
                            }
                        }
                    }
                }
                target
            } finally {
                conn.disconnect()
            }
        }.getOrElse { failure ->
            target.delete()
            // A cancelled download is not a failed one. Swallowing this would
            // leave the calling coroutine looking like it completed normally.
            if (failure is CancellationException) throw failure
            null
        }
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Android 8+ gates sideloading behind a per-app "install unknown apps" grant.
     * Without it the installer Intent opens and immediately dies, so check first
     * and send the user to the right settings page instead.
     */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * A file:// URI would trip FileUriExposedException on anything modern, so the
     * APK is handed over through a FileProvider content:// URI with a one-shot
     * read grant for the installer.
     */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun hasInstaller(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null ||
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}
