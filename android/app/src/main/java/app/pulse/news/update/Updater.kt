package app.pulse.news.update

import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /**
     * Download the APK ourselves (same DNS fallback as the API), then install it through Android's
     * PackageInstaller session API. Unlike opening a downloaded file, this works on phones whose
     * installers refuse content:// download URIs (common on Xiaomi, Realme, Oppo, Vivo).
     */
    fun install(ctx: Context, rel: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Toaster.show("Allow Pulse to install updates, then tap Update again")
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        Toaster.show("Downloading update…")
        CoroutineScope(Dispatchers.Main).launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val installer = ctx.packageManager.packageInstaller
                    val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(ctx.packageName)
                    val sessionId = installer.createSession(params)
                    installer.openSession(sessionId).use { session ->
                        Api.http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { res ->
                            if (!res.isSuccessful) error("download failed: ${res.code}")
                            val body = res.body ?: error("empty download")
                            session.openWrite("pulse.apk", 0, body.contentLength()).use { out ->
                                body.byteStream().use { it.copyTo(out) }
                                session.fsync(out)
                            }
                        }
                        val callback = android.app.PendingIntent.getBroadcast(
                            ctx, sessionId,
                            Intent(ctx, InstallResultReceiver::class.java),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                        )
                        session.commit(callback.intentSender)
                    }
                    true
                }.getOrElse { false }
            }
            if (!ok) Toaster.show("Update download failed. Check your connection and try again.")
        }
    }
}

/** Receives the installer's result; when Android needs the user to confirm, shows its install prompt. */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                android.widget.Toast.makeText(context, "Update not installed${msg?.let { ": $it" } ?: ""}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
