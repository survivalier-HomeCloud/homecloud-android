package fr.survivalier.homecloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Tâche WorkManager : un passage de HomeCloud Sync (périodique, manuel ou suite d'un passage interrompu). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params), SyncHost {
    override var foreground: Boolean = false
    private var lastNotify = 0L

    override suspend fun doWork(): Result {
        val outcome = SyncEngine.run(applicationContext, this) ?: return Result.success()
        if (outcome.more) SyncScheduler.continueSoon(applicationContext)
        return Result.success()
    }

    override suspend fun transferStarted(pending: Int) {
        // Autorisé ou non selon la version d'Android et l'état de l'appli : sans notification, le travail continue
        // simplement avec la limite de temps habituelle (le moteur reprend au passage suivant).
        runCatching {
            setForeground(info("Envoi de $pending fichier(s)…", 0, 0))
            foreground = true
        }
    }

    override fun onProgress(p: SyncProgress) {
        if (!foreground) return
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, notification("${p.done}/${p.total} — ${p.text.substringAfterLast('/')}", p.done, p.total)) }
    }

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        val n = notification(text, done, total)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "HomeCloud Sync", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HomeCloud Sync")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()
    }

    companion object {
        private const val CHANNEL = "homecloud_sync"
        private const val NOTIF_ID = 4711
    }
}

object SyncScheduler {
    private const val PERIODIC = "hcsync_periodic"
    private const val NOW = "hcsync_now"
    private const val CONT = "hcsync_cont"

    /** Programme (ou annule) la synchronisation périodique selon les réglages. */
    fun apply(ctx: Context, replace: Boolean = true) {
        val app = ctx.applicationContext
        val cfg = SyncConfig(app)
        val wm = WorkManager.getInstance(app)
        if (!cfg.enabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(CONT)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(cfg.intervalMin.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(cfg))
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /** « Synchroniser maintenant » : sans contrainte de réseau ni de charge. */
    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Suite d'un passage coupé par la limite de temps (gros envois). */
    fun continueSoon(ctx: Context) {
        val cfg = SyncConfig(ctx.applicationContext)
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(cfg)).build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(CONT, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun constraints(cfg: SyncConfig): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (cfg.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(cfg.chargingOnly)
        .build()
}
