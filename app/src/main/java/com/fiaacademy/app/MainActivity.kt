package com.fiaacademy.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * أكاديمية المستقبل الدولية — غلاف WebView لموقع fiaacademy.vercel.app
 *
 * الجلسات محفوظة (Cookies + localStorage)، رفع الصور من المعرض والكاميرا مدعوم،
 * زر الرجوع يتنقل في سجل الموقع ولا يخرج إلا من الرئيسية، والروابط الخارجية
 * تفتح خارج التطبيق. عند انقطاع الاتصال تظهر شاشة عربية أنيقة مع إعادة المحاولة.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_HOST = "fiaacademy.vercel.app"
        private const val START_URL = "https://fiaacademy.vercel.app/"
        const val EXTRA_OPEN_URL = "fia_open_url"

        /** تحديث تلقائي للمحتوى بعد غياب أطول من هذا — بلا خروج ودخول */
        private const val AUTO_REFRESH_AFTER_MS = 2 * 60_000L

        /**
         * واجهة Notification كاملة تُحقن في كل صفحة قبل تفاعل المستخدم:
         * تعتمد الإذن محلياً وتحوّل إشعارات الموقع إلى إشعارات أندرويد حقيقية عبر جسر FiaApp.
         */
        private const val SHIM_JS = """
                (function(){
                  if (window.__fiaNotifShim) return; window.__fiaNotifShim = true;
                  var PK = 'fia-shim-notification-permission';
                  function currentPerm(){
                    try { var v = localStorage.getItem(PK); if (v === 'granted' || v === 'denied') return v; } catch(e) {}
                    return 'default';
                  }
                  function FiaNotification(title, opts){
                    opts = opts || {};
                    this.title = title == null ? '' : String(title);
                    this.body  = opts.body == null ? '' : String(opts.body);
                    this.icon  = opts.icon == null ? '' : String(opts.icon);
                    this.tag   = opts.tag == null ? ('fia_' + Date.now()) : String(opts.tag);
                    this.data  = opts.data || null;
                    this.dir   = opts.dir || 'rtl';
                    this.lang  = opts.lang || 'ar';
                    this.silent = !!opts.silent;
                    this.requireInteraction = false;
                    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
                    var self = this;
                    this.close = function(){
                      try { self.onclose && self.onclose(); } catch(e) {}
                      try { window.FiaApp && window.FiaApp.closeNotification && window.FiaApp.closeNotification(self.tag); } catch(e) {}
                    };
                    try {
                      var link = (opts.link != null && String(opts.link)) || (this.data && this.data.link) || '/';
                      window.FiaApp && window.FiaApp.showNotification && window.FiaApp.showNotification(this.title, this.body, String(link), this.tag);
                    } catch(e) {}
                  }
                  FiaNotification.prototype.close = function(){};
                  try {
                    Object.defineProperty(FiaNotification, 'permission', { get: function(){ return currentPerm(); }, configurable: true });
                  } catch(e) { try { FiaNotification.permission = 'granted'; } catch(e2) {} }
                  FiaNotification.maxActions = 4;
                  FiaNotification.requestPermission = function(cb){
                    try { localStorage.setItem(PK, 'granted'); } catch(e) {}
                    try { if (typeof cb === 'function') setTimeout(function(){ try { cb('granted'); } catch(e) {} }, 0); } catch(e) {}
                    return Promise.resolve('granted');
                  };
                  window.Notification = FiaNotification;
                  // مراقب استجابات فحص الموقع نفسه (كل 15 ثانية وهو مفتوح) —
                  // يحول كل إشعار جديد غير مقروء إلى إشعار أندرويد حقيقي دون أي طلبات إضافية
                  var SK = 'fia-shim-seen-ids';
                  var SD = 'fia-shim-seeded';
                  function loadSeen(){ try { return JSON.parse(localStorage.getItem(SK) || '[]') || []; } catch(e) { return []; } }
                  function saveSeen(a){ try { localStorage.setItem(SK, JSON.stringify(a.slice(-200))); } catch(e) {} }
                  var origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function(input, init){
                      var p = origFetch.apply(this, arguments);
                      try {
                        var u = (typeof input === 'string') ? input : ((input && input.url) || '');
                        var m = init && init.method ? String(init.method).toUpperCase() : 'GET';
                        if (u.indexOf('/api/notifications?user_id=') !== -1 && m === 'GET') {
                          p.then(function(r){
                            try {
                              if (!r || !r.ok) return;
                              r.clone().json().then(function(data){
                                try {
                                  if (!Array.isArray(data)) return;
                                  var seen = loadSeen();
                                  var has = {};
                                  seen.forEach(function(x){ has[x] = 1; });
                                  var seeded = false;
                                  try { seeded = localStorage.getItem(SD) === '1'; } catch(e) {}
                                  var added = false;
                                  data.forEach(function(n){
                                    if (!n || !n.id || has[n.id]) return;
                                    seen.push(String(n.id)); has[n.id] = 1; added = true;
                                    if (seeded && !n.read_at && window.FiaApp && window.FiaApp.showNotification) {
                                      window.FiaApp.showNotification(String(n.title || ''), String(n.body || ''), String(n.link || '/'), String(n.id));
                                    }
                                  });
                                  if (added) {
                                    saveSeen(seen);
                                    if (!seeded) { try { localStorage.setItem(SD, '1'); } catch(e) {} }
                                  }
                                } catch(e) {}
                              }).catch(function(){});
                            } catch(e) {}
                          }).catch(function(){});
                        }
                      } catch(e) {}
                      return p;
                    };
                  }
                })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var retryButton: Button

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var loadFailed = false
    private var pendingOpenUrl: String? = null

    /** حارس إزعاج لجسر إشعارات الموقع: 6 كحدّ أقصى في الدقيقة */
    private val bridgeTimes = ArrayDeque<Long>()

    private var bgAt = 0L
    private var skipNextReload = false

    /** آخر عطل مسجّل من التشغيل السابق — يُعرض بدل الانهيار الصامت */
    private var lastCrashText: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> launchFileChooser(withCamera = granted) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* إشعارات الخلفية تبدأ فور المنح — لا شيء إضافي مطلوب */ }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        when {
            parsed != null && parsed.isNotEmpty() -> callback.onReceiveValue(parsed)
            result.resultCode == RESULT_OK && pendingCameraUri != null ->
                callback.onReceiveValue(arrayOf(pendingCameraUri!!))
            else -> callback.onReceiveValue(null)
        }
        pendingCameraUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            lastCrashText = FiaApplication.readLastCrash(this)
            if (lastCrashText != null) {
                // عطل من التشغيل السابق — نعرضه نصاً بدل التوقف الصامت
                showCrashReport()
                return
            }
            startNormal(savedInstanceState)
        } catch (t: Throwable) {
            showFatalError(t)
        }
    }

    /** الإقلاع الطبيعي الكامل — أي فشل هنا يظهر شاشة التشخيص بدل الانهيار */
    private fun startNormal(savedInstanceState: Bundle?) {
        pendingOpenUrl = extractInternalUrl(intent)
        setContentView(R.layout.activity_main)
        initViews(savedInstanceState)
        askNotificationPermission()
        NotificationWorker.ensurePeriodic(this)
        NotificationWorker.kick(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractInternalUrl(intent)?.let { url ->
            skipNextReload = true // فتح إشعار الآن — لا تُعِد تحميل الصفحة في onResume
            if (::webView.isInitialized) webView.loadUrl(url) else pendingOpenUrl = url
        }
    }

    /** منطق الإقلاع معزول هنا — أي فشل يُعرض على الشاشة بدل انهيار صامت */
    private fun initViews(savedInstanceState: Bundle?) {
        webView = findViewById(R.id.web_view)
        errorView = findViewById(R.id.error_view)
        retryButton = findViewById(R.id.retry_button)

        setupWebView()

        retryButton.setOnClickListener {
            if (isOnline()) {
                loadFailed = false
                hideError()
                webView.reload()
            } else {
                Toast.makeText(this, R.string.offline_message, Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // التطبيق يبقى حياً في الخلفية — العودة إليه فورية بلمح البصر
                    // بدون إعادة تحميل أو خروج من الجلسة، مثل التطبيقات المستقلة تماماً
                    moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            if (isOnline()) hideError() else showError()
        } else if (isOnline()) {
            webView.loadUrl(pendingOpenUrl ?: START_URL)
        } else {
            showError()
        }
    }

    /** شاشة تشخيص تُعرض بدل انهيار التطبيق — تبين سبب الخطأ نصاً للمستخدم */
    private fun showFatalError(t: Throwable) {
        try {
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            fun message(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
                TextView(this).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    gravity = Gravity.CENTER
                    setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                }

            val spacing = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(20))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            root.addView(ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            })
            root.addView(message("تعذر فتح التطبيق", 20f, 0xFF334264.toInt(), true), spacing)
            root.addView(
                message("سبب الخطأ مكتوب بالأسفل — أرسل لقطة شاشة له لمن بنى لك التطبيق", 14f, 0xFF5B6472.toInt()),
                spacing
            )
            root.addView(
                message(t.javaClass.name + "\n" + (t.message ?: "—"), 12f, 0xFFB3261E.toInt()),
                spacing
            )
            val frames = t.stackTrace.take(4).joinToString("\n") {
                "• " + it.className.substringAfterLast('.') + "." + it.methodName + " (سطر " + it.lineNumber + ")"
            }
            root.addView(message(frames, 10f, 0xFF5B6472.toInt()), spacing)
            root.addView(
                message("إن ذُكر WebView في الخطأ: حدّث «نظام أندرويد WebView» و«Chrome» من متجر Play ثم أعد تشغيل الهاتف", 12f, 0xFF334264.toInt()),
                spacing
            )
            root.addView(Button(this).apply {
                text = "إغلاق التطبيق"
                setOnClickListener { finishAffinity() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20) }
            })

            setContentView(root)
        } catch (ignored: Throwable) {
        }
    }

    /** شاشة تقرير آخر عطل — قابلة للتصوير، والمتابعة إلى التطبيق ممكنة بزر */
    private fun showCrashReport() {
        try {
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            fun message(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
                TextView(this).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                }

            val spacing = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }

            val report = TextView(this).apply {
                text = lastCrashText
                textSize = 11f
                setTextColor(0xFF8A1C1C.toInt())
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(0xFFF6F6F8.toInt())
            }

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(20), dp(16), dp(16))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            root.addView(ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
            root.addView(
                message(getString(R.string.crash_title), 18f, 0xFF334264.toInt(), true),
                spacing
            )
            root.addView(
                message(getString(R.string.crash_hint), 13f, 0xFF5B6472.toInt()),
                spacing
            )
            root.addView(
                ScrollView(this).apply {
                    addView(report)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
                    ).apply { topMargin = dp(12) }
                }
            )
            root.addView(Button(this).apply {
                text = getString(R.string.crash_continue)
                setOnClickListener {
                    FiaApplication.clearLastCrash(this@MainActivity)
                    lastCrashText = null
                    try {
                        startNormal(null)
                    } catch (t: Throwable) {
                        showFatalError(t)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            })
            root.addView(Button(this).apply {
                text = getString(R.string.crash_close)
                setOnClickListener { finishAffinity() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })

            setContentView(ScrollView(this).apply { addView(root) })
        } catch (t: Throwable) {
            // حتى شاشة التقرير فشلت — لا ننهار أبداً: نتخطى التقرير ونعمل
            lastCrashText = null
            try {
                startNormal(null)
            } catch (t2: Throwable) {
                showFatalError(t2)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            // أصوات إشعارات الموقع تعمل فور وصولها دون لمسة (مثل تطبيق حقيقي)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString FIAAcademyApp/2.4"
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // بلا تأثير مطاطي/توهج عند نهاية التمرير — سلوك تطبيقي لا سلوك متصفح
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // جسر الموقع: إشعارات «new Notification» داخل الموقع تظهر كإشعارات أندرويد حقيقية
        webView.addJavascriptInterface(FiaBridge(), "FiaApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            /** التقاط user_id من استعلامات الموقع نفسه لتغذية فاحص الخلفية */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                try {
                    val u = request.url
                    if (u.host == HOME_HOST && u.path == "/api/notifications") {
                        val uid = u.getQueryParameter("user_id")
                        if (!uid.isNullOrBlank() &&
                            FiaNotifications.saveUserId(this@MainActivity, uid)
                        ) {
                            // أول تسجيل دخول أو تبديل حساب — تشغيل خدمة الاستلام الفوري
                            RealtimeService.start(this@MainActivity)
                        }
                    }
                } catch (ignored: Throwable) {
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loadFailed) hideError()
                injectNotifShim(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    loadFailed = true
                    showError()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                pendingCameraUri = null
                return if (hasCameraPermission()) {
                    launchFileChooser(withCamera = true)
                    true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    true
                }
            }
        }
    }

    /** الروابط الداخلية تبقى داخل التطبيق — كل ما عداه يفتح بالخارج */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        return when (scheme) {
            "http", "https" -> {
                val host = uri.host ?: return false
                if (host == HOME_HOST || host.endsWith(".$HOME_HOST")) {
                    false
                } else {
                    openExternal(uri)
                    true
                }
            }
            "tel", "mailto", "sms", "geo", "whatsapp" -> {
                openExternal(uri)
                true
            }
            "intent" -> {
                openExternal(uri)
                true
            }
            else -> true
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.offline_message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchFileChooser(withCamera: Boolean) {
        val callback = filePathCallback ?: return
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }

        var cameraIntent: Intent? = null
        if (withCamera) {
            cameraIntent = try {
                val dir = File(cacheDir, "uploads").apply { mkdirs() }
                val tempFile = File.createTempFile("fia_upload_", ".jpg", dir)
                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", tempFile
                )
                pendingCameraUri = uri
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            } catch (e: IOException) {
                pendingCameraUri = null
                null
            }
        }

        val chooser = Intent.createChooser(galleryIntent, getString(R.string.app_name))
        if (cameraIntent != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
        }
        fileChooserLauncher.launch(chooser)
    }

    private fun cancelFileChooser() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingCameraUri = null
    }

    /**
     * فحص الاتصال محصّن بالكامل: أي فشل غير متوقع (صلاحية/تقييد نظام) يعامَل
     * كأن الجهاز متصل — WebView نفسه سيعرض شاشة الانقطاع إن لم يستطع التحميل فعلاً.
     */
    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            true // لا نمنع التحميل أبداً بسبب فشل الفحص نفسه
        }
    }

    /** استخراج رابط داخلي صالح من إشعار — أي رابط خارج النطاق يُتجاهل */
    private fun extractInternalUrl(inlineIntent: Intent?): String? {
        val raw = inlineIntent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        return try {
            val uri = Uri.parse(raw)
            val host = uri.host ?: return null
            if ((host == HOME_HOST || host.endsWith(".$HOME_HOST")) &&
                (uri.scheme == "https" || uri.scheme == "http")
            ) raw else null
        } catch (t: Throwable) {
            null
        }
    }

    /** إذن الإشعارات — مطلوب من أندرويد 13 فما فوق */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * توافق الإشعارات المنبثقة: WebView لا يملك واجهة Notification الخاصة بالمتصفحات،
     * فكان الموقع يرفض التفعيل برسالة «المتصفح لا يدعم الإشعارات المنبثقة».
     *
     * الحل: نزوّد الواجهة كاملة داخل الصفحة:
     *  • Notification.permission و requestPermission() يعملان ويُحفظان محلياً —
     *    فسير زر التفعيل في الموقع يكتمل بنجاح تماماً كما في كروم.
     *  • «new Notification(...)» من الموقع يتحول عبر جسر FiaApp إلى إشعار أندرويد
     *    حقيقي منبثق بصوت خارج التطبيق.
     */
    private fun injectNotifShim(view: WebView?) {
        val web = view ?: return
        try {
            web.evaluateJavascript(SHIM_JS, null)
        } catch (ignored: Throwable) {
        }
    }

    private inner class FiaBridge {

        @JavascriptInterface
        fun showNotification(title: String?, body: String?, link: String?, tag: String?) {
            // حارس إزعاج: 6 إشعارات جسر كحدّ أقصى في الدقيقة
            val now = System.currentTimeMillis()
            synchronized(bridgeTimes) {
                val it = bridgeTimes.iterator()
                while (it.hasNext()) if (now - it.next() > 60_000L) it.remove()
                if (bridgeTimes.size >= 6) return
                bridgeTimes.addLast(now)
            }
            val safeTag = if (tag.isNullOrBlank()) "fia_$now" else tag.take(80)
            // منع التكرار الذرّي مع الخدمة والعامل — إن سبق عرض الإشعار فلا شيء
            if (!FiaNotifications.checkAndMarkSeen(this@MainActivity, safeTag)) return
            FiaNotifications.show(
                this@MainActivity,
                safeTag,
                1,
                if (title.isNullOrBlank()) getString(R.string.notif_fallback_title) else title.take(200),
                if (body.isNullOrBlank()) getString(R.string.notif_fallback_body) else body.take(400),
                (link ?: "/").take(300)
            )
        }

        @JavascriptInterface
        fun closeNotification(tag: String?) {
            if (tag.isNullOrBlank()) return
            FiaNotifications.cancel(this@MainActivity, tag.take(80), 1)
        }
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
    }

    private fun hideError() {
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        bgAt = System.currentTimeMillis()
        if (::webView.isInitialized) webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            // محتوى الموقع محدّث بلمح البصر بعد كل عودة — بلا خروج ودخول
            if (!skipNextReload && bgAt > 0 &&
                System.currentTimeMillis() - bgAt >= AUTO_REFRESH_AFTER_MS
            ) {
                try {
                    webView.reload()
                } catch (ignored: Throwable) {
                }
            }
            skipNextReload = false
            // ضمان خدمة الاستلام الفوري بعد جاهزية الواجهة تماماً —
            // بعيداً عن ثقل الإقلاع، وبلا أي خطر على فتح التطبيق مهما كانت سرعة الجهاز
            uiHandler.postDelayed({ RealtimeService.start(this@MainActivity) }, 2500L)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
