package com.fiaacademy.app

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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
 * الوجهة النهائية:
 *  • الصور (شهادات PNG/JPG): /storage/emulated/0/Pictures/Fia academy/ — تظهر
 *    مباشرة في المعرض كألبوم باسم Fia academy (درس v2.6: مجلد Download لا تُفهرسه
 *    تطبيقات المعرض — فكانت الشهادات تصل «بشكل غير مرتب» أو غير مرئية).
 *  • ملفات PDF والمستندات: /storage/emulated/0/Download/Fia academy/
 *  • روابط الشبكة (صور عادية إلخ): تُنزّل عبر DownloadManager إلى Download/Fia academy.
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
     * حفظ ملف قادم بترميز base64 (data: أو blob:) — يوجّه تلقائياً:
     * الصور إلى المعرض (Pictures/Fia academy) وغيرها إلى التنزيلات.
     * يقبل dataUrl كاملاً (data:mime;base64,xxxx) أو نص base64 مجرداً.
     * @param silent إخفاء التوست المحلي (عندما يعرض الموقع توسته الخاص بالفعل)
     */
    fun saveBase64(
        context: Context,
        fileName: String,
        mime: String?,
        encoded: String,
        silent: Boolean = false
    ): Boolean {
        return try {
            val data = Base64.getDecoder().decode(encoded.substringAfterLast(','))
            saveBytes(context, fileName, mime ?: "application/octet-stream", data, silent)
        } catch (t: Throwable) {
            if (!silent) {
                Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    /**
     * حفظ بايتات جاهزة — نقطة الدخول الموحدة من الجسور:
     * الصور إلى المعرض (Pictures/Fia academy) وغيرها إلى التنزيلات (Download/Fia academy).
     */
    fun saveBytes(
        context: Context,
        fileName: String,
        mime: String,
        data: ByteArray,
        silent: Boolean = false
    ): Boolean {
        return try {
            val name = sanitize(fileName)
            val saved = if (Build.VERSION.SDK_INT >= 29) {
                saveMediaStore(context, name, mime, data)
            } else {
                saveLegacyFile(context, name, data)
            }
            if (!silent) {
                Toast.makeText(
                    context,
                    if (saved) {
                        if (mime.startsWith("image/")) R.string.gallery_saved else R.string.download_saved
                    } else R.string.download_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            saved
        } catch (t: Throwable) {
            if (!silent) {
                Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    /**
     * حفظ صورة في المعرض — المسار المفضل للشهادات من جسر FIANativeBridge.
     * أندرويد 10+: MediaStore.Images بمسار Pictures/Fia academy (ألبوم مرئي فوراً).
     * أندرويد 8-9: ملف مباشر في Pictures + فهرسة MediaScanner فورية.
     */
    fun saveImageToGallery(
        context: Context,
        fileName: String,
        mime: String?,
        data: ByteArray,
        silent: Boolean = false
    ): Boolean {
        return try {
            val name = sanitize(fileName)
            val saved = if (Build.VERSION.SDK_INT >= 29) {
                saveMediaStore(context, name, mime ?: "image/png", data, toGallery = true)
            } else {
                saveLegacyFile(context, name, data, toGallery = true)
            }
            if (!silent) {
                Toast.makeText(
                    context,
                    if (saved) R.string.gallery_saved else R.string.download_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            saved
        } catch (t: Throwable) {
            if (!silent) {
                Toast.makeText(context, R.string.download_failed, Toast.LENGTH_LONG).show()
            }
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

    /**
     * أندرويد 10+: MediaStore (بلا أي أذونات).
     * الصور → Pictures/Fia academy (تظهر في المعرض فوراً) — البقية → Download/Fia academy.
     */
    private fun saveMediaStore(
        context: Context,
        name: String,
        mime: String,
        data: ByteArray,
        toGallery: Boolean = false
    ): Boolean {
        val resolver = context.contentResolver
        val isImage = toGallery || mime.startsWith("image/")
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
                    + "/" + FOLDER_NAME
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
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

    /** أندرويد 8-9: ملف مباشر (الصور في Pictures + فهرسة فورية، البقية في Download) */
    private fun saveLegacyFile(
        context: Context,
        name: String,
        data: ByteArray,
        toGallery: Boolean = false
    ): Boolean {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(
            if (toGallery) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(root, FOLDER_NAME)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return false

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, "$base (${i++})$ext")

        target.outputStream().use { it.write(data) }
        // فهرسة فورية — تظهر الصورة في المعرض خلال ثوانٍ دون إعادة تشغيل
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("image/*")
            ) { _, _ -> }
        } catch (ignored: Throwable) {
        }
        return true
    }

    /** تنقية اسم الملف من محارف المسارات والممنوعات */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120)
            .ifBlank { "fia-${System.currentTimeMillis()}" }
}
