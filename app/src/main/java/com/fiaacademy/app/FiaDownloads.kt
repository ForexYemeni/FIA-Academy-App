package com.fiaacademy.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.util.Base64

/**
 * تنزيل ملفات الموقع (الشهادات، الصور، المستندات) فورياً إلى مجلد
 * «Fia academy» داخل مجلد التنزيلات العام — مرئي لكل تطبيقات الملفات.
 *
 * المسارات الثلاثة المدعومة:
 *  1) روابط http/https: DownloadManager الرسمي — شريط تقدم + إشعار إتمام،
 *     مع تمرير كوكيز الجلسة فتُنزَّل الملفات المحمية بالمصادقة كما في المتصفح تماماً.
 *  2) روابط data: (ملفات مضمنة في الصفحة): فك ترميز وحفظ مباشر.
 *  3) روابط blob: (شهادات تولَّد داخل الصفحة): تجلبها الصفحة عبر fetch وتسلّمها
 *     للجسر FiaApp.saveBase64 — ثم تُحفظ عبر MediaStore (أندرويد 10+) بلا أذونات،
 *     أو ملفاً مباشراً (أندرويد 8-9) بعد إذن التخزين.
 *
 * الوجهة النهائية: /storage/emulated/0/Download/Fia academy/<الملف>
 */
object FiaDownloads {

    const val FOLDER_NAME = "Fia academy"

    /** مجلد الوجهة المعروض للمستخدم داخل شجرة التنزيلات العامة */
    val displayPath: String
        get() = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME

    /** نقطة الدخول الموحدة لأي عملية تنزيل من الموقع — لا ترمي أبداً */
    fun enqueue(context: Context, url: String, contentDisposition: String?, mimetype: String?) {
        try {
            when {
                url.startsWith("data:") -> {
                    val mime = url.substringAfter("data:", "")
                        .substringBefore(";base64,").ifBlank { "application/octet-stream" }
                    val ext = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mime) ?: "bin"
                    saveBase64(
                        context,
                        "fia-${System.currentTimeMillis()}.$ext",
                        mime,
                        url.substringAfter("base64,", "")
                    )
                }
                url.startsWith("blob:") -> Unit // تُعالج من MainActivity عبر جافاسكربت والجسر
                else -> enqueueDownloadManager(context, url, contentDisposition, mimetype)
            }
        } catch (t: Throwable) {
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * حفظ ملف قادم بترميز base64 (data: أو blob:) في مجلد Fia academy.
     * يقبل dataUrl كاملاً (data:mime;base64,xxxx) أو نص base64 مجرداً.
     */
    fun saveBase64(context: Context, fileName: String, mime: String?, encoded: String): Boolean {
        return try {
            val raw = encoded.substringAfterLast(',')
            val data = Base64.getDecoder().decode(raw)
            val name = sanitize(fileName)
            val saved = if (Build.VERSION.SDK_INT >= 29) {
                saveMediaStore(context, name, mime ?: "application/octet-stream", data)
            } else {
                saveLegacyFile(name, data)
            }
            Toast.makeText(
                context,
                if (saved) R.string.download_saved else R.string.download_failed,
                Toast.LENGTH_LONG
            ).show()
            saved
        } catch (t: Throwable) {
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    /** تنزيل رابط شبكي عبر DownloadManager — آمن للاستدعاء المتكرر */
    private fun enqueueDownloadManager(
        context: Context, url: String, contentDisposition: String?, mimetype: String?
    ) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription(FOLDER_NAME)
            if (!mimetype.isNullOrBlank()) setMimeType(mimetype)
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "$FOLDER_NAME/$name"
            )
            // كوكيز الجلسة — الملفات خلف تسجيل الدخول تُنزَّل بلا انقطاع
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
            } catch (ignored: Throwable) {
            }
        }
        dm.enqueue(req)
        Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    /** أندرويد 10+: MediaStore (بلا أي أذونات) — الملف يظهر في Download/Fia academy */
    private fun saveMediaStore(context: Context, name: String, mime: String, data: ByteArray): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: return false
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** أندرويد 8-9: ملف مباشر في Download/Fia academy (بعد إذن WRITE_EXTERNAL_STORAGE) */
    private fun saveLegacyFile(name: String, data: ByteArray): Boolean {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, FOLDER_NAME)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return false

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, "$base (${i++})$ext")

        target.outputStream().use { it.write(data) }
        return true
    }

    /** تنقية اسم الملف من محارف المسارات والممنوعات */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120)
            .ifBlank { "fia-${System.currentTimeMillis()}" }
}
