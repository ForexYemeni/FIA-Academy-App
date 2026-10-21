package com.fiaacademy.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * شبكة الأمان المستقلة للإشعارات — سلسلة منبهات دقيقة (AlarmManager) تُوقظ
 * العملية وتجلب الإشعارات حتى لو قُتل التطبيق والخدمة معاً بواسطة النظام
 * أو واجهة مخصصة (شاومي/هواوي/أوبو وغيرها).
 *
 * لماذا هذه الشبكة؟
 * • START_STICKY + onTaskRemoved يحميان الخدمة، لكن بعض الواجهات تقتل العملية
 *   كلياً بعد دقائق من إغلاق التطبيق. المستقبل (receiver) المُعرَّف في
 *   AndroidManifest يستيقظ من الـ Alarm ويُحيي الفحص من الصفر — بلا خدمة،
 *   وبلا عملية سابقة، حتى بعد أيام من آخر فتح للتطبيق.
 * • تُشترط على السلسلة العمل إلا بثلاثة شروط مجتمعة:
 *   (حساب مسجّل الدخول) + (شاشة مطفأة) + (إعفاء بطارية ممنوح).
 *   أي شرط مفقود → تُلغى السلسلة فوراً (الخدمة أو شاشة مفتوحة تتكفل).
 *
 * • الدقة: setExactAndAllowWhileIdle تعمل حتى داخل Doze للتطبيقات
 *   المُعفاة من البطارية — وعند عدم توفر صلاحية المنبهات الدقيقة
 *   (أندرويد 12+ بعد رفض المستخدم) نتراجع لـ setWindow بهدوء تام.
 * • التكلفة: طلب HTTPS خفيف واحد كل 30 ثانية والشاشة مطفأة فقط،
 *   وهو نفس ما تفعله الخدمة أصلاً — لكنه مضمون حتى بعد قتل العملية.
 */
class FiaAlarmReceiver : BroadcastReceiver() {

    companion object {
        /** فاصل السلسلة — يطابق فحص الشاشة المطفأة في الخدمة */
        const val INTERVAL_MS = 30_000L

        private const val REQUEST_CODE = 4242
        private const val WAKE_TIMEOUT_MS = 25_000L

        /** هل التطبيق مُعفى من قتل البطارية (Doze)؟ */
        fun isExempted(context: Context): Boolean = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        } catch (t: Throwable) {
            false
        }

        /** تشغيل/تجديد السلسلة — آمن للاستدعاء المتكرر من أي مكان */
        fun ensureChain(context: Context) {
            try {
                if (FiaNotifications.getUserId(context) == null) return // لا حساب بعد
                if (!isExempted(context)) return // بلا إعفاء → لا إزعاج بالمنبهات
                scheduleNext(context, INTERVAL_MS)
            } catch (ignored: Throwable) {
            }
        }

        /** إلغاء السلسلة نهائياً — تُستدعى عند الشاشة المفتوحة أو نهاية الشروط */
        fun cancelChain(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = pendingIntent(context)
                am.cancel(pi)
            } catch (ignored: Throwable) {
            }
        }

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, FiaAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun scheduleNext(context: Context, delayMs: Long) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = pendingIntent(context)
                val triggerAt = SystemClock.elapsedRealtime() + delayMs
                try {
                    if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                        // المستخدم رفض المنبهات الدقيقة — منبه تقريبي يظل أفضل من لا شيء
                        am.setWindow(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt, INTERVAL_MS, pi
                        )
                    } else {
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                        )
                    }
                } catch (t: Throwable) {
                    // أي قيد غير متوقع — نتراجع بهدوء لمنبه تقريبي
                    am.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt, 2 * INTERVAL_MS, pi
                    )
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync() // نمنح أنفسنا وقتاً للطلب الشبكي خارج onReceive

        // قفل إيقاظ قصير — يضمن اكتمال الطلب قبل نوم المعالج مجدداً
        val wake = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FIA:AlarmPoll")
        } catch (t: Throwable) {
            null
        }
        try {
            wake?.acquire(WAKE_TIMEOUT_MS)
        } catch (ignored: Throwable) {
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val screenOn = try {
                    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isInteractive
                } catch (t: Throwable) {
                    true
                }

                // أي شرط مفقود → أوقف السلسلة بهدوء (الخدمة/الشاشة تتكفل بالفحص)
                if (screenOn || !isExempted(context) ||
                    FiaNotifications.getUserId(context) == null
                ) {
                    cancelChain(context)
                    return@launch
                }

                // جلب الإشعارات الجديدة — إزالة التكرار ذرّية داخل FiaNotifications
                try {
                    FiaPoller.pollOnce(context)
                } catch (ignored: Throwable) {
                }

                // النبضة التالية — وهكذا تعيش السلسلة إلى ما لا نهاية
                scheduleNext(context, INTERVAL_MS)
            } finally {
                try {
                    wake?.release()
                } catch (ignored: Throwable) {
                }
                result.finish()
            }
        }
    }
}
