package com.fiaacademy.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.SharedPreferences
import android.webkit.CookieManager

/**
 * مركز إشعارات التطبيق — قناة واحدة عالية الأهمية بصوت واهتزاز وظهور منبثق (heads-up).
 *
 * تستخدمه المسارات الثلاثة معاً (مع منع تكرار ذرّي مشترك عبر مجموعة المعرّفات):
 *  1) RealtimeService — فحص سريع في المقدمة (10 ث) والخلفية (45 ث) حتى والتطبيق مغلق.
 *  2) NotificationWorker — سلسلة كل 60 ثانية + دوري 15 دقيقة كشبكة أمان.
 *  3) جسر FiaApp — إشعارات الموقع نفسه (زر التفعيل / مراقب استجابات الموقع).
 */
object FiaNotifications {

    const val CHANNEL_ID = "fia_notifications"
    const val SERVICE_CHANNEL_ID = "fia_service"
    const val BASE = "https://fiaacademy.vercel.app"

    const val PREFS = "fia_notif"
    const val KEY_USER_ID = "user_id"
    const val KEY_SEEDED_FOR = "seeded_for"
    const val KEY_IDS = "notified_ids"

    /** قفل ذرّي مشترك — يمنع تكرار الإشعار الواحد بين الخدمة والعامل والجسر */
    val LOCK = Any()

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getUserId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    /**
     * حفظ معرّف المستخدم الملتقط من طلبات الموقع نفسه.
     * @return true إذا تغيّر المعرّف (حساب جديد → يلزم إعادة تشغيل الخدمة والتهيئة)
     */
    fun saveUserId(context: Context, userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        val p = prefs(context)
        if (p.getString(KEY_USER_ID, null) != userId) {
            p.edit().putString(KEY_USER_ID, userId).remove(KEY_SEEDED_FOR).apply()
            return true
        }
        return false
    }

    /** فحص وإضافة ذرّية — يعيد true فقط لأول من يلمس هذا المعرّف */
    fun checkAndMarkSeen(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        synchronized(LOCK) {
            val p = prefs(context)
            val seen = p.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (seen.contains(id)) return false
            seen.add(id)
            persistSeen(p, seen)
            return true
        }
    }

    /** أول فحص لحساب المستخدم: تسجيل الموجود حالياً بصمت دون إشعارات قديمة */
    fun seedIfNeeded(context: Context, userId: String, allIds: List<String>): Boolean {
        val p = prefs(context)
        if (p.getString(KEY_SEEDED_FOR, null) == userId) return false
        synchronized(LOCK) {
            if (p.getString(KEY_SEEDED_FOR, null) == userId) return false
            val seen = p.getStringSet(KEY_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            seen.addAll(allIds.filter { it.isNotBlank() })
            persistSeen(p, seen)
            p.edit().putString(KEY_SEEDED_FOR, userId).apply()
            return true
        }
    }

    private fun persistSeen(p: SharedPreferences, seen: MutableSet<String>) {
        val trimmed: Set<String> =
            if (seen.size > 200) seen.toList().takeLast(150).toSet() else seen
        p.edit().putStringSet(KEY_IDS, trimmed).apply()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // كل قناة تُنشأ باستقلال تام — ممنوع أي return مبكر قبل فحص القناتين:
        // (كان الخروج المبكر هنا يترك قناة الخدمة غير منشأة على الأجهزة
        //  المحدّثة من نسخ أقدم — فتقتل startForeground التطبيق على أندرويد 15)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                enableVibration(true)
                // صوت صريح على القناة — بعض الواجهات تتجاهل صوت المهمة العالية إن لم يُضبط
                val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(sound, attrs)
            }
            nm.createNotificationChannel(channel)
        }

        // قناة هادئة لخدمة الاستلام الدائمة (بلا صوت أو اهتزاز)
        if (nm.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.service_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(serviceChannel)
        }
    }

    /** هل قناة الخدمة موجودة فعلاً؟ فحص إلزامي قبل أي startForeground */
    fun serviceChannelReady(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < 26) true
        else try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(SERVICE_CHANNEL_ID) != null
        } catch (t: Throwable) {
            false
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * عرض إشعار أندرويد حقيقي بصوت وظهور منبثق.
     * @param tag معرّف نصي اختياري (معرّف إشعار الموقع — يُستخدم للمنع من التكرار)
     * @param id معرّف رقمي للإشعار
     * @param link رابط الصفحة المرتبطة — يُفتح داخل التطبيق عند النقر
     */
    fun show(context: Context, tag: String?, id: Int, title: String, body: String, link: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (link.isNotBlank()) {
                putExtra(MainActivity.EXTRA_OPEN_URL, absolutize(link))
            }
        }
        val pi = PendingIntent.getActivity(
            context,
            (tag.orEmpty() + ":" + id).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (ignored: SecurityException) {
        }
    }

    fun cancel(context: Context, tag: String, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(tag, id)
        } catch (ignored: Throwable) {
        }
    }

    /** الروابط النسبية من الموقع تُحوَّل إلى مطلقة على نطاق الأكاديمية */
    fun absolutize(link: String): String =
        if (link.startsWith("http://") || link.startsWith("https://")) link
        else BASE + (if (link.startsWith("/")) link else "/$link")
}
