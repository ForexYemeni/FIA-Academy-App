package com.fiaacademy.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * فحص إشعارات الخلفية (شبكة أمان تحت RealtimeService):
 *  • سلسلة ذاتية الجدولة كل 60 ثانية (اسمان يتناوبان بـ REPLACE — بلا نمو
 *    في قاعدة البيانات وبتنظيف تلقائي لأي سلاسل مكرّرة عالقة).
 *  • عمل دوري كل 15 دقيقة يشغّله MainActivity كشبكة أمان إضافية.
 *
 * المنطق نفسه في FiaPoller — والمنع من التكرار ذرّي مشترك، فلا يظهر
 * الإشعار الواحد مرتين ولو تزامنت سلاسل الفحص كلها.
 */
class NotificationWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        private const val KEY_STEP = "fia_step"
        private const val INTERVAL_SECONDS = 60L
        private const val CHAIN_A = "fia-notif-chain-a"
        private const val CHAIN_B = "fia-notif-chain-b"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** فحص فوري عند فتح التطبيق — يبدأ أيضاً السلسلة الذاتية */
        fun kick(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "fia-notifications-now",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<NotificationWorker>()
                        .setInputData(workDataOf(KEY_STEP to 0))
                        .setConstraints(constraints())
                        .build()
                )
            } catch (ignored: Throwable) {
            }
        }

        /** العمل الدوري — شبكة أمان إذا ماتت السلسلة أو الخدمة */
        fun ensurePeriodic(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "fia-notifications",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints())
                        .build()
                )
            } catch (ignored: Throwable) {
            }
        }
    }

    override suspend fun doWork(): Result {
        try {
            FiaPoller.pollOnce(applicationContext)
        } catch (ignored: Throwable) {
            // أي فشل (شبكة/توقف خدمة) يُترك للدورة القادمة بصمت — لا انهيارات في الخلفية
        }
        scheduleNext()
        return Result.success()
    }

    private fun scheduleNext() {
        try {
            val step = inputData.getInt(KEY_STEP, 0)
            val nextName = if (step % 2 == 0) CHAIN_B else CHAIN_A
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                nextName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NotificationWorker>()
                    .setInputData(workDataOf(KEY_STEP to (step + 1)))
                    .setInitialDelay(INTERVAL_SECONDS, TimeUnit.SECONDS)
                    .setConstraints(constraints())
                    .build()
            )
        } catch (ignored: Throwable) {
        }
    }
}
