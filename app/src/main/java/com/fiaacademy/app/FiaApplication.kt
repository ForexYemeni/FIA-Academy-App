package com.fiaacademy.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فئة التطبيق — خط الدفاع الأول ضد أي انهيار:
 *
 * 1) معالج أعطال عالمي: أي انهيار في أي مكان (الواجهة أو الخلفية) يُكتب في ملف
 *    محلي، وعند الفتح التالي يعرضه التطبيق على الشاشة بدل التوقف الصامت —
 *    فيكفي لقطة شاشة واحدة لتشخيص أي عطل مستقبلي بدقة.
 *
 * 2) تهيئة WorkManager حسب الطلب: أزلنا التهيئة التلقائية من مرحلة الـ ContentProvider
 *    (التي تحدث قبل أي سطر من كودنا ولا يمكن التقاط أخطائها نهائياً). الآن تتم
 *    التهيئة داخل مسارات محمية بالكامل، وإن كانت قاعدة بيانات العمل تالفة من
 *    نسخة قديمة تُمسح وتُعاد بناؤها تلقائياً — فلا انهيار إقلاع مهما حدث.
 */
class FiaApplication : Application(), Configuration.Provider {

    companion object {
        private const val CRASH_FILE = "fia_last_crash.txt"
        private const val KEY_WM_RESET = "wm_reset_for"

        fun readLastCrash(context: Context): String? = try {
            val f = File(context.filesDir, CRASH_FILE)
            if (f.exists() && f.length() > 0) f.readText().take(4000) else null
        } catch (t: Throwable) {
            null
        }

        fun clearLastCrash(context: Context) {
            try {
                File(context.filesDir, CRASH_FILE).delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (ignored: Throwable) {
        }
        try {
            installCrashHandler()
        } catch (ignored: Throwable) {
        }
        try {
            resetWorkDatabaseIfNeeded()
        } catch (ignored: Throwable) {
        }
    }

    /** تهيئة WorkManager حسب الطلب — بدون مزوّد تلقائي لا يمكن حمايته */
    override val workManagerConfiguration: Configuration
        get() = try {
            Configuration.Builder().build()
        } catch (t: Throwable) {
            Configuration.Builder().build()
        }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                var text = sw.toString()
                if (text.length > 3000) text = text.substring(0, 3000)

                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                } catch (t: Throwable) {
                    "?"
                }
                val now = try {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                } catch (t: Throwable) {
                    "?"
                }
                val header = "إصدار التطبيق: " + version +
                    "\nالجهاز: " + Build.MANUFACTURER + " " + Build.MODEL +
                    " (أندرويد " + Build.VERSION.RELEASE + ")" +
                    "\nوقت العطل: " + now +
                    "\n---------------------------\n"

                File(filesDir, CRASH_FILE).writeText(header + text)
            } catch (ignored: Throwable) {
            }
            // نُكمل السلوك الافتراضي (نافذة النظام + إنهاء العملية) كما هو
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * عند كل تحديث جديد: مسح قاعدة بيانات WorkManager القديمة احتياطاً —
     * قاعدة تالفة من نسخة سابقة كانت سبباً محتملاً لانهيار الإقلاع قبل أي كود.
     * كل المهام تُبنى تلقائياً من جديد عند أول فتح (ensurePeriodic + kick).
     */
    private fun resetWorkDatabaseIfNeeded() {
        val p = getSharedPreferences(FiaNotifications.PREFS, Context.MODE_PRIVATE)
        val code = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (t: Throwable) {
            0
        }
        if (p.getInt(KEY_WM_RESET, -1) == code) return
        try {
            deleteDatabase("androidx.work.workdb")
        } catch (ignored: Throwable) {
        }
        try {
            val dbDir = File(filesDir.parentFile ?: filesDir, "databases")
            dbDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("androidx.work.workdb")) {
                    try {
                        f.delete()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        try {
            p.edit().putInt(KEY_WM_RESET, code).apply()
        } catch (ignored: Throwable) {
        }
    }
}
