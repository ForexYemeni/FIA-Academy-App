# 📱 دليل تعديل وبناء تطبيق أكاديمية المستقبل الدولية

هذه الحزمة تحتوي **الكود المصدري الكامل** لتطبيق أندرويد (الإصدار v2.7.1 — versionCode 19) لتتمكن من تعديل أي شيء فيه وبناء نسخة APK جديدة بنفسك.

---

## 1) هيكل المشروع — ماذا يفعل كل ملف؟

```
FIA-Academy-App/
├── app/
│   ├── build.gradle                  ← إعدادات التطبيق: الإصدار، الحزمة، التوقيع
│   └── src/main/
│       ├── AndroidManifest.xml       ← الصلاحيات (10) وتعريف الخدمات والمستقبِلات
│       ├── java/com/fiaacademy/app/
│       │   ├── MainActivity.kt       ← النافذة الرئيسية (WebView) — ★ رابط الموقع هنا
│       │   ├── FiaApplication.kt     ← تهيئة التطبيق + مسجّل الأعطال العام
│       │   ├── RealtimeService.kt    ← خدمة الإشعارات الفورية (فحص كل 5ث مضاءة/30ث مطفأة)
│       │   ├── FiaPoller.kt          ← منطق فحص الإشعارات من الموقع (مشترك)
│       │   ├── FiaNotifications.kt   ← إنشاء القنوات وعرض الإشعارات + منع التكرار
│       │   ├── NotificationWorker.kt ← فحص احتياطي دوري (WorkManager)
│       │   └── BootReceiver.kt       ← إعادة تشغيل الفحص بعد إقلاع الهاتف
│       └── res/
│           ├── values/strings.xml    ← ★ كل نصوص التطبيق بالعربية (اسم التطبيق، الإشعارات...)
│           ├── values/colors.xml     ← ★ ألوان الهوية (كحلي #334264 + ذهبي #D8A346)
│           ├── mipmap-*/             ← ★ أيقونات التطبيق (بجميع الأحجام)
│           ├── layout/activity_main.xml ← تصميم النافذة الرئيسية
│           └── drawable/             ← خلفيات وأيقونات الرسم
├── build.gradle                      ← إعدادات البناء العامة
├── settings.gradle                   ← اسم المشروع والمستودعات
├── gradle.properties                 ← خصائص Gradle
└── signing-backup.tar.gz.enc         ← نسخة مشفّرة من مفتاح التوقيع القديم (كلمة التشفير ضائعة — احتفظ بالملف احتياطاً)
```

---

## 2) أشهر التعديلات وأماكنها بالضبط

### أ) تغيير رابط الموقع
الملف: `app/src/main/java/com/fiaacademy/app/MainActivity.kt` — السطران 54-55:
```kotlin
private const val HOME_HOST = "fiaacademy.vercel.app"
private const val START_URL = "https://fiaacademy.vercel.app/"
```
غيّر الاثنين معاً (احذر: تغيير الموقع يعني تغيير نظام الإشعارات المرتبط به أيضاً).

### ب) رقم واسم الإصدار
الملف: `app/build.gradle`:
```gradle
versionCode 19      ← زده +1 مع كل إصدار جديد (إلزامي للتحديث)
versionName '2.5'   ← النسخة الظاهرة للمستخدم
```

### ج) اسم التطبيق وأي نص ظاهر
الملف: `app/src/main/res/values/strings.xml`:
```xml
<string name="app_name">أكاديمية المستقبل الدولية</string>
```

### د) الألوان
الملف: `app/src/main/res/values/colors.xml` (كحلي `#334264`، ذهبي `#D8A346`).

### هـ) الأيقونة
استبدل ملفات `ic_launcher.png` و`ic_launcher_fg.png` و`ic_launcher_round.png` في كل مجلدات `res/mipmap-*` بنفس أسماء الملفات الجديدة (استخدم أداة **Image Asset** في Android Studio لسهولة توليد كل الأحجام).

### و) الصلاحيات
الملف: `app/src/main/AndroidManifest.xml` — 10 صلاحيات (إنترنت، إشعارات، إقلاع، إلخ). لا تحذف صلاحية إلا إذا فهمت أثرها.

---

## 3) طريقة البناء — خطوة بخطوة

### الطريق الأسهل: Android Studio (موصى به)
1. نزّل [Android Studio](https://developer.android.com/studio) (يشمل JDK 17 تلقائياً).
2. **File ← Open** واختر مجلد هذه الحزمة — انتظر مزامنة Gradle (يحتاج إنترنت أول مرة).
3. للتجربة السريعة: اضغط ▶️ Run (على هاتف موصول بـ USB وتفعيل USB Debugging).
4. لإنتاج APK نهائي: **Build ← Generate Signed Bundle / APK ← APK**:
   - أول مرة: **Create new...** لإنشاء مفتاح توقيع جديد:
     - Key store path: اختر مكاناً آمناً (مثلاً `fia-release-key.jks`)
     - كلمتا المرور: احفظهما في 3 أماكن مختلفة (هاتف + بريد + سحابة)!
     - Validity: 30 سنة على الأقل، Alias: `fiaacademy`
   - اختر **release** ثم Finish — ستجد APK في:
     `app/build/outputs/apk/release/app-release.apk`

### الطريق المتقدم: سطر الأوامر
يتطلب JDK 17 + Android SDK (API 34) + Gradle 8.10.2:
1. أنشئ ملفاً باسم `app/keystore.properties` (بجانب `app/build.gradle`) بهذا الشكل:
```properties
storeFile=/المسار/الكامل/إلى/fia-release-key.jks
storePassword=كلمة_مرور_المخزن
keyAlias=fiaacademy
keyPassword=كلمة_مرور_المفتاح
```
2. ثم:
```bash
gradle assembleRelease
# الناتج: app/build/outputs/apk/release/app-release.apk
```

---

## 4) ⚠️ قواعد التوقيع والإصدار الحرجة (اقرأها جيداً)

1. **مفتاح التوقيع القديم (v2.0–v2.4) ضاع** مع إعادة تهيئة بيئة التطوير. الموجود لديك `signing-backup.tar.gz.enc` مشفّر بكلمة مرور لم تعد معروفة — **احتفظ بالملف رغم ذلك**.
2. **أي APK جديد بمفتاح مختلف** لن يُثبَّت فوق النسخة القديمة — سيطلب من المستخدم حذف القديم أولاً (مرة واحدة أخيرة فقط، ثم استقر الأمر).
3. بعد إنشاء مفتاحك الجديد: **هذا الملف هو هوية تطبيقك للأبد** — فقده يعني حذف/تثبيت مجدداً لكل المستخدمين. احفظه في 3 أماكن على الأقل.
4. **لا تغيّر `applicationId`** (`com.fiaacademy.app`) أبداً إلا إن أردت تطبيقاً منفصلاً تماماً.
5. **versionCode يجب أن يزيد** مع كل إصدار وإلا رفض أندرويد التحديث.

---

## 5) ماذا بعد التعديل؟

بعد بناء APK بنجاح يمكنك:
- توزيعه مباشرة (إرسال الملف للطلاب أو رفعه على الموقع).
- أو نشره في صفحة [Releases](https://github.com/ForexYemeni/FIA-Academy-App/releases) بمستودع GitHub ليظل الرابط الدائم `releases/latest/download/FIA-Academy.apk` يعمل دائماً.

© أكاديمية المستقبل الدولية — Future International Academy
