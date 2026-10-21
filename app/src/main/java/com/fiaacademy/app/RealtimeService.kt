package com.fiaacademy.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * خدمة استلام الإشعارات الفورية — تعمل باستمرار حتى والتطبيق مغلق تماماً.
 *
 * • تفحص إشعارات المستخدم بفواصل قصيرة جداً:
 *     - كل 5 ثوانٍ والشاشة مضاءة (شبه لحظي — هذا ما يشعر به المستخدم)
 *     - كل 30 ثانية والشاشة مطفأة (توفيراً للبطارية والبيانات)
 * • عند وصول إشعار جديد: إشعار أندرويد حقيقي منبثق بصوت فوراً.
 * • لا تنفّذ أي إشعار مكرر: المنع ذرّي مشترك مع العامل والجسر عبر FiaNotifications.
 *
 * === درس v2.2 ===
 * كانت تبدأ بـ startForegroundService أثناء ثقل إقلاع الواجهة: إن تأخر
 * startForeground عن 5 ثوانٍ على الأجهزة البطيئة أوقف النظام التطبيق بنافذة
 * «توقف التطبيق» — استثناءً لا يمكن لأي try/catch التقاطه إطلاقاً.
 * الآن: startService عادي (يُستدعى والتطبيق في المقدمة فقط) — بلا أي عقد
 * زمني قاتل، وإن تعذّرت الترقية للمقدمة تتوقف الخدمة بهدوء وتبقى الشبكات
 * الأخرى (سلسلة 60 ثانية + الدوري) تعمل دون أي انهيار.
 */
class RealtimeService : Service() {

    companion object {
        private const val INTERVAL_SCREEN_ON_MS = 5_000L
        private const val INTERVAL_SCREEN_OFF_MS = 30_000L
        private const val FOREGROUND_ID = 42

        @Volatile
        var isRunning = false
            private set

        /** بدء/ضمان عمل الخدمة — آمن للاستدعاء المتكرر، ولا يوقف التطبيق أبداً */
        fun start(context: Context) {
            try {
                if (FiaNotifications.getUserId(context) == null) return // لا حساب بعد
                if (isRunning) return
                // startService العادي: بلا «عقد الـ 5 ثوانٍ» نهائياً.
                // يُستدعى والتطبيق في المقدمة — وإن فشل (خلفية مثلاً) يُتجاهل بهدوء
                // وينتظر الفرصة التالية عند فتح التطبيق من جديد.
                context.startService(Intent(context, RealtimeService::class.java))
            } catch (ignored: Throwable) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // أولاً وأولاً: القناة ثم الترقية للمقدمة — وفشلها لا ينهار بل يوقف الخدمة فقط
        try {
            FiaNotifications.ensureChannel(this)
        } catch (ignored: Throwable) {
        }
        if (!promoteForeground()) {
            isRunning = false
            try {
                stopSelf()
            } catch (ignored: Throwable) {
            }
            return
        }

        screenOn = try {
            (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        } catch (t: Throwable) {
            true
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
        } catch (ignored: Throwable) {
        }

        scope.launch {
            // حلقة الفحص السريع — أي استثناء يُبتلع وتستمر الحلقة
            while (isActive) {
                try {
                    FiaPoller.pollOnce(this@RealtimeService)
                } catch (ignored: Throwable) {
                }
                if (FiaNotifications.getUserId(this@RealtimeService) == null) {
                    // لا حساب (لم يسجل دخول بعد) — أعد المحاولة لاحقاً بشكل خفيف
                    delay(60_000L)
                    continue
                }
                delay(if (screenOn) INTERVAL_SCREEN_ON_MS else INTERVAL_SCREEN_OFF_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        return START_STICKY
    }

    /**
     * سحب التطبيق من المهام الأخيرة: أعد إعلان الخدمة في المقدمة فوراً —
     * الخدمة الأمامية تبقى حية على أندرويد الرسمي، وبعض الواجهات المخصصة
     * (شاومي/هواوي وغيرها) تحاول قتلها هنا فنعاكم على الفور.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            promoteForeground()
        } catch (ignored: Throwable) {
        }
        super.onTaskRemoved(rootIntent)
    }

    /** الترقية للمقدمة — تعيد true فقط عند نجاحها الفعلي */
    private fun promoteForeground(): Boolean {
        // درس من كارثة v2.2/v2.3 على أندرويد 15:
        // إشعار startForeground بقناة غير موجودة = CannotPostForegroundServiceNotificationException
        // يقتل العملية فوراً دون إمكانية الالتقاط — لذا نفحص وننشئ القناة هنا مباشرة،
        // وإن لم توجد رغم ذلك نتوقف بهدوء ولا نحاول النشر أبداً.
        try {
            FiaNotifications.ensureChannel(this)
        } catch (ignored: Throwable) {
        }
        if (!FiaNotifications.serviceChannelReady(this)) {
            return false
        }
        val notification = try {
            NotificationCompat.Builder(this, FiaNotifications.SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        } catch (t: Throwable) {
            null
        } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    FOREGROUND_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // محاولة أخيرة بالنسخة البسيطة (بلا نوع) قبل الاستسلام بهدوء
            try {
                startForeground(FOREGROUND_ID, notification)
                true
            } catch (t2: Throwable) {
                false
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Throwable) {
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
