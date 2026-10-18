package com.fiaacademy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * بعد إقلاع الهاتف: إعادة تشغيل فحص الإشعارات فوراً عبر WorkManager فقط —
 * **بلا أي خدمة أمامية عند الإقلاع**: بدء FGS من مستقبل الإقلاع كان يمهد
 * لانتهاء مهلة الـ 5 ثوانٍ على الأجهزة البطيئة (عقد لا يمكن التقاط استثناءه).
 * سلسلة 60 ثانية تكفي هنا، وخدمة الفحص السريع تعود تلقائياً عند أول فتح للتطبيق.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                if (FiaNotifications.getUserId(context) != null) {
                    NotificationWorker.kick(context)
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}
