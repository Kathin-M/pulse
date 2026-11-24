package app.pulse.news.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pulse.news.MainActivity
import app.pulse.news.PulseApp
import app.pulse.news.R
import app.pulse.news.data.Api
import app.pulse.news.data.Store
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** "Morning brief: 28 stories" — one optional notification a day at the hour you pick. */
class BriefWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (!Store.briefEnabled || !Store.loggedIn) return Result.success()
        val (feed, _) = runCatching { Api.feed("foryou", emptyList()) }.getOrElse { return Result.retry() }
        val n = feed.cards.size
        if (n == 0) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val top = feed.cards.first().displayTitle
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note = NotificationCompat.Builder(applicationContext, PulseApp.BRIEF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(0xFFF5A524.toInt())
            .setContentTitle("Morning brief: $n ${if (n == 1) "story" else "stories"}")
            .setContentText(top)
            .setStyle(NotificationCompat.BigTextStyle().bigText(top))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(1, note)
        return Result.success()
    }

    companion object {
        private const val NAME = "morning-brief"

        fun schedule(ctx: Context) {
            val now = LocalDateTime.now()
            var next = now.withHour(Store.briefHour).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<BriefWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}
