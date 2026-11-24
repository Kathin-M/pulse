package app.pulse.news.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import app.pulse.news.BuildConfig
import app.pulse.news.data.Api
import app.pulse.news.data.Store
import app.pulse.news.ui.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * In-app updates from the project's GitHub releases. Each APK build publishes a release tagged
 * `android-<versionCode>` with Pulse.apk attached; the app compares that with its own version.
 */
object Updater {
    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String)

    @Serializable
    private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String)

    @Serializable
    private data class GhRelease(@SerialName("tag_name") val tag: String, val assets: List<GhAsset> = emptyList(), val draft: Boolean = false)

    suspend fun check(force: Boolean): Release? {
        if (!enabled) return null
        val now = System.currentTimeMillis()
        if (!force && now - Store.lastUpdateCheck < 6 * 3600_000L) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val body = Api.http.newCall(req).execute().use { it.body?.string().orEmpty() }
                Store.lastUpdateCheck = now
                val rel = Api.json.decodeFromString<GhRelease>(body)
                val code = rel.tag.substringAfter("android-").toIntOrNull() ?: return@runCatching null
                val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@runCatching null
                if (code <= BuildConfig.VERSION_CODE) return@runCatching null
                if (!force && code == Store.skippedVersion) return@runCatching null
                Release(code, "2.$code", apk.url)
            }.getOrNull()
        }
    }

    /** Download with the system download manager, then hand the APK to Android's installer. */
    fun install(ctx: Context, rel: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Toaster.show("Allow Pulse to install updates, then tap Update again")
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val file = "Pulse-${rel.versionCode}.apk"
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(file)?.delete()
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(rel.apkUrl))
                .setTitle("Pulse ${rel.versionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, file)
        )
        Toaster.show("Downloading update…")
        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(700)
                val status = withContext(Dispatchers.IO) {
                    dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
                        if (cur.moveToFirst()) cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else DownloadManager.STATUS_FAILED
                    }
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uri = dm.getUriForDownloadedFile(id)
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    break
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    Toaster.show("Update download failed. Try again later.")
                    break
                }
            }
        }
    }
}
