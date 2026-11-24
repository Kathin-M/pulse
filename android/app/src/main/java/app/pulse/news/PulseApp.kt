package app.pulse.news

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.pulse.news.data.Store
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

class PulseApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(BRIEF_CHANNEL, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One daily note with how many stories are waiting"
            }
        )
    }

    // News images: browser-like user agent (some CDNs block default clients), no referrer, disk cache.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
                        .build()
                )
            }.build()
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(250)
        .respectCacheHeaders(false)
        .build()

    companion object {
        const val BRIEF_CHANNEL = "brief"
    }
}
