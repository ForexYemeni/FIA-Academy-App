package com.fiaacademy.app

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * فحص واحد لإشعارات المنصة — منطق مشترك بين RealtimeService (الفحص السريع)
 * و NotificationWorker (سلسلة 60 ثانية + الدوري 15 دقيقة).
 *
 * آلية العمل (مصحّحة في v2.1 — كانت الدعوات الأقدم تنتهي بخطأ 400 بلا نتائج):
 *  • الموقع نفسه يستدعي  /api/notifications?user_id=<معرّف>  ويعيد مصفوفة JSON مباشرة
 *    (لا يحتاج جلسة ولا كوكيز — المعرف يكفي، وunread=1 يقلّل الحجم إن دعمه الخادم).
 *  • المعرّف يُلتقط تلقائياً من طلبات الموقع نفسه (shouldInterceptRequest) ويُخزَّن محلياً.
 *  • الفحص يرشّح غير المقروء (read_at فارغ)، ويمنع التكرار بمنع ذرّي مشترك،
 *    ويعرض إشعارات أندرويد حقيقية بصوت وظهور منبثق.
 */
object FiaPoller {

    private const val MAX_SHOWN = 3

    /** @return true إذا عُرض إشعار واحد على الأقل */
    fun pollOnce(context: Context): Boolean {
        val userId = FiaNotifications.getUserId(context) ?: return false // لم يُلتقط معرّف بعد

        val cookie = try {
            CookieManager.getInstance().getCookie(FiaNotifications.BASE)
        } catch (t: Throwable) {
            null
        }

        val (code, resp) = httpGet(
            "${FiaNotifications.BASE}/api/notifications?user_id=$userId&unread=1",
            cookie
        )
        if (code != 200) return false // خطأ شبكة/خادم — الدورة القادمة

        val arr = try {
            JSONArray(resp) // الموقع يعيد مصفوفة مباشرة — ليس غلافاً بمفتاح notifications
        } catch (t: Throwable) {
            return false
        }

        val allIds = ArrayList<String>(arr.length())
        val unread = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val id = n.optString("id")
            if (id.isEmpty()) continue
            allIds.add(id)
            if (n.isNull("read_at") || n.optString("read_at").isBlank()) unread.add(n)
        }

        // أول فحص لحساب المستخدم: تسجيل الموجود حالياً بصمت دون إشعارات قديمة
        if (FiaNotifications.seedIfNeeded(context, userId, allIds)) return false

        var shownAny = false
        var shown = 0
        var freshSeen = 0
        for (n in unread) {
            val id = n.optString("id")
            // منع تكرار ذرّي: من يصل أولاً (خدمة/عامل/جسر) يعرض، والبقية تتجاوز
            if (!FiaNotifications.checkAndMarkSeen(context, id)) continue
            freshSeen++
            if (shown >= MAX_SHOWN) continue // المسجّل يكفي لمنع التكرار، ولا إغراق بالإشعارات

            FiaNotifications.show(
                context, null, id.hashCode(),
                n.optString("title").ifBlank { context.getString(R.string.notif_fallback_title) },
                n.optString("body").ifBlank { context.getString(R.string.notif_fallback_body) },
                n.optString("link")
            )
            shown++
            shownAny = true
        }

        // إشعار ملخص للباقي بدل إغراق الشاشة
        val remaining = freshSeen - shown
        if (remaining > 0) {
            FiaNotifications.show(
                context, null, ("fia_summary_$remaining").hashCode(),
                context.getString(R.string.notif_summary_title),
                context.getString(R.string.notif_summary_title) + " (" + remaining + ")",
                "/"
            )
            shownAny = true
        }

        return shownAny
    }

    private fun httpGet(url: String, cookie: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "FIAAcademyApp/2.2 (notifications)")
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.responseCode to text
        } finally {
            conn.disconnect()
        }
    }
}
