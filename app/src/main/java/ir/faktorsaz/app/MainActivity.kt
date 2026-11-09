package ir.faktorsaz.app

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : FragmentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader
    private lateinit var patch: PatchStore
    private val main = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var driveAction: ((String) -> Unit)? = null

    // ---- App lock (fingerprint / device PIN) ----
    private val prefs by lazy { getSharedPreferences("faktorsaz", MODE_PRIVATE) }
    private var lockView: View? = null
    private var prompting = false
    private var stoppedAt = 0L
    private val authTypes =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun lockEnabled() = prefs.getBoolean("lock", false)

    private fun appName(): String =
        prefs.getString("app_name", null)?.takeIf { it.isNotBlank() } ?: "دفترچه هوشمند"

    private fun canAuth() =
        BiometricManager.from(this).canAuthenticate(authTypes) == BiometricManager.BIOMETRIC_SUCCESS

    /** Fingerprint or face enrolled on the phone (no PIN fallback). */
    private fun canBio() =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun hasAppPin() = PinStore.has(prefs)

    /** The lock can work if the phone has a screen lock / biometrics, or the app has its own 4-digit code. */
    private fun canLock() = hasAppPin() || canAuth()

    private fun promptAuth(onSuccess: () -> Unit, onError: () -> Unit = {}) {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    onError()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appName())
            .setSubtitle("برای ورود، اثر انگشت یا قفل گوشی را تأیید کنید")
            .setAllowedAuthenticators(authTypes)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    /** Fingerprint-only prompt (no PIN fallback) used by the hidden section. Silent if no fingerprint is enrolled. */
    private fun promptBio(onSuccess: () -> Unit) {
        if (prompting) return
        val bio = BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(bio) != BiometricManager.BIOMETRIC_SUCCESS) return
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appName())
            .setSubtitle("تأیید اثر انگشت")
            .setAllowedAuthenticators(bio)
            .setNegativeButtonText("انصراف")
            .build()
        prompt.authenticate(info)
    }

    private var setupView: View? = null

    /** Fingerprint / face prompt shown over the lock screen. Cancelling it just leaves the number pad. */
    private fun promptLockBio() {
        if (prompting || !canBio()) return
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    prefs.edit().putInt("pin_fail", 0).apply()
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appName())
            .setSubtitle("اثر انگشت را بگذارید یا به صفحه نگاه کنید")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("کد برنامه")
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun showLock() {
        if (!hasAppPin()) {
            // No app code: same as before (system prompt: fingerprint / face / phone code).
            if (lockView == null) {
                val v = FrameLayout(this)
                v.setBackgroundResource(R.color.app_bg)
                v.isClickable = true
                val b = Button(this).apply {
                    text = "باز کردن قفل"
                    setOnClickListener { promptAuth(onSuccess = { unlock() }) }
                }
                v.addView(
                    b,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    )
                )
                root.addView(
                    v,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )
                lockView = v
            }
            promptAuth(onSuccess = { unlock() })
            return
        }

        if (lockView == null) {
            val pv = PinView(this)
            val entered = StringBuilder()
            val hasBio = canBio()
            fun refresh() = pv.setDots(PinStore.LEN, entered.length)
            refresh()
            pv.setMessage(
                if (hasBio) "با اثر انگشت یا چهره وارد شوید\nیا کد برنامه را بزنید" else "کد برنامه را بزنید"
            )
            if (canAuth()) {
                pv.setAlt("قفل گوشی")
                pv.onAlt = { promptAuth(onSuccess = { unlock() }) }
            }
            if (hasBio) {
                pv.setBio("اثر انگشت / چهره")
                pv.onBio = { promptLockBio() }
            }
            pv.onBack = {
                if (entered.isNotEmpty()) {
                    entered.deleteCharAt(entered.length - 1)
                    refresh()
                }
            }
            pv.onDigit = { d ->
                val until = prefs.getLong("pin_until", 0L)
                if (System.currentTimeMillis() < until) {
                    val sec = ((until - System.currentTimeMillis()) / 1000 + 1)
                    pv.setMessage("چند بار اشتباه زدید؛ $sec ثانیه صبر کنید", true)
                } else if (entered.length < PinStore.LEN) {
                    entered.append(d)
                    refresh()
                    // Opens by itself on the 4th digit; there is no confirm button.
                    if (entered.length == PinStore.LEN) {
                        val code = entered.toString()
                        if (PinStore.check(prefs, code)) {
                            prefs.edit().putInt("pin_fail", 0).putLong("pin_until", 0L).apply()
                            unlock()
                        } else {
                            val fails = prefs.getInt("pin_fail", 0) + 1
                            val e = prefs.edit().putInt("pin_fail", fails)
                            if (fails % 5 == 0) {
                                val wait = 30L * (fails / 5)
                                e.putLong("pin_until", System.currentTimeMillis() + wait * 1000)
                                pv.setMessage("۵ بار اشتباه؛ $wait ثانیه صبر کنید", true)
                            } else {
                                pv.setMessage("کد اشتباه است", true)
                            }
                            e.apply()
                            pv.shake()
                            main.postDelayed({ entered.clear(); refresh() }, 250)
                        }
                    }
                }
            }
            root.addView(
                pv,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            lockView = pv
        }
        promptLockBio()
    }

    /** Full-screen screen for choosing the 4-digit app code: type it, then type it again (no buttons). */
    private fun startPinSetup() {
        if (setupView != null) return
        val pv = PinView(this)
        val entered = StringBuilder()
        var first: String? = null
        fun refresh() = pv.setDots(PinStore.LEN, entered.length)
        fun closeSetup(msg: String?) {
            root.removeView(pv)
            setupView = null
            js("window.__secRefresh&&window.__secRefresh(${JSONObject.quote(msg ?: "")})")
        }
        refresh()
        pv.setTitle("کد برنامه")
        pv.setMessage("یک کد ۴ رقمی انتخاب کنید")
        pv.setExtra("انصراف")
        pv.onExtra = { closeSetup(null) }
        pv.onBack = {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                refresh()
            }
        }
        pv.onDigit = { d ->
            if (entered.length < PinStore.LEN) {
                entered.append(d)
                refresh()
                if (entered.length == PinStore.LEN) {
                    val code = entered.toString()
                    val f = first
                    if (f == null) {
                        first = code
                        main.postDelayed({
                            entered.clear(); refresh(); pv.setMessage("کد را یک بار دیگر بزنید")
                        }, 200)
                    } else if (f == code) {
                        PinStore.set(prefs, code)
                        prefs.edit().putBoolean("lock", true).putInt("pin_fail", 0).putLong("pin_until", 0L).apply()
                        closeSetup("کد برنامه ذخیره شد و قفل روشن است")
                    } else {
                        first = null
                        pv.shake()
                        pv.setMessage("دو کد یکی نبود؛ دوباره یک کد انتخاب کنید", true)
                        main.postDelayed({ entered.clear(); refresh() }, 250)
                    }
                }
            }
        }
        root.addView(
            pv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setupView = pv
    }

    private fun unlock() {
        lockView?.let { root.removeView(it) }
        lockView = null
    }

    // ---- Edit lock for the «لیست کار» page: its own 4-digit code (default 1375), view-only while locked ----
    private var editView: View? = null

    private fun wkJs(call: String) = js("try{document.getElementById('wf').contentWindow.$call}catch(e){}")

    private fun ensureEditPin() {
        if (!PinStore.has(prefs, "epin")) PinStore.set(prefs, "1375", "epin")
    }

    private val wkTabs = listOf("sheet", "meas", "rec", "debt")

    private fun wkTabOn(t: String): Boolean =
        prefs.getBoolean("edit_t_$t", if (t == "sheet") prefs.getBoolean("edit_on", true) else true)

    private fun wkTabsJson(): String {
        ensureEditPin()
        val o = JSONObject()
        wkTabs.forEach { o.put(it, if (wkTabOn(it)) 1 else 0) }
        return o.toString()
    }

    private fun wkSync(msg: String) {
        wkJs("__wkRefresh()")
        js("window.__secRefresh&&window.__secRefresh(${JSONObject.quote(msg)})")
    }

    private fun closeEditView() {
        editView?.let { root.removeView(it) }
        editView = null
    }

    /** Fingerprint / face prompt over the edit pad. Cancelling it just leaves the number pad. */
    private fun promptEditBio(onOk: () -> Unit) {
        if (prompting || !canBio()) return
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onOk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appName())
            .setSubtitle("برای ویرایش، اثر انگشت را بگذارید")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("کد ویرایش")
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    /** Asks for the edit code (and optionally fingerprint). onOk runs only after a correct code. */
    private fun askEditCode(title: String, withBio: Boolean, onOk: () -> Unit) {
        if (editView != null) return
        ensureEditPin()
        val pv = PinView(this)
        val entered = StringBuilder()
        val bio = withBio && canBio()
        fun refresh() = pv.setDots(PinStore.LEN, entered.length)
        fun ok() {
            closeEditView()
            prefs.edit().putInt("epin_fail", 0).putLong("epin_until", 0L).apply()
            onOk()
        }
        refresh()
        pv.setTitle(title)
        pv.setMessage(if (bio) "کد ویرایش را بزنید\nیا اثر انگشت بگذارید" else "کد ویرایش (۴ رقمی) را بزنید")
        pv.setExtra("انصراف")
        pv.onExtra = { closeEditView() }
        if (bio) {
            pv.setBio("اثر انگشت / چهره")
            pv.onBio = { promptEditBio { ok() } }
        }
        pv.onBack = {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                refresh()
            }
        }
        pv.onDigit = { d ->
            val until = prefs.getLong("epin_until", 0L)
            if (System.currentTimeMillis() < until) {
                val sec = ((until - System.currentTimeMillis()) / 1000 + 1)
                pv.setMessage("چند بار اشتباه زدید؛ $sec ثانیه صبر کنید", true)
            } else if (entered.length < PinStore.LEN) {
                entered.append(d)
                refresh()
                if (entered.length == PinStore.LEN) {
                    val code = entered.toString()
                    if (PinStore.check(prefs, code, "epin")) {
                        ok()
                    } else {
                        val fails = prefs.getInt("epin_fail", 0) + 1
                        val e = prefs.edit().putInt("epin_fail", fails)
                        if (fails % 5 == 0) {
                            val wait = 30L * (fails / 5)
                            e.putLong("epin_until", System.currentTimeMillis() + wait * 1000)
                            pv.setMessage("۵ بار اشتباه؛ $wait ثانیه صبر کنید", true)
                        } else {
                            pv.setMessage("کد اشتباه است", true)
                        }
                        e.apply()
                        pv.shake()
                        main.postDelayed({ entered.clear(); refresh() }, 250)
                    }
                }
            }
        }
        root.addView(
            pv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        editView = pv
        if (bio) promptEditBio { ok() }
    }

    /** Choose a new edit code: type it, then type it again. */
    private fun startEditSetup() {
        if (editView != null) return
        val pv = PinView(this)
        val entered = StringBuilder()
        var first: String? = null
        fun refresh() = pv.setDots(PinStore.LEN, entered.length)
        refresh()
        pv.setTitle("کد ویرایش")
        pv.setMessage("کد ۴ رقمی تازه را بزنید")
        pv.setExtra("انصراف")
        pv.onExtra = { closeEditView() }
        pv.onBack = {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                refresh()
            }
        }
        pv.onDigit = { d ->
            if (entered.length < PinStore.LEN) {
                entered.append(d)
                refresh()
                if (entered.length == PinStore.LEN) {
                    val code = entered.toString()
                    val f = first
                    if (f == null) {
                        first = code
                        main.postDelayed({
                            entered.clear(); refresh(); pv.setMessage("کد را یک بار دیگر بزنید")
                        }, 200)
                    } else if (f == code) {
                        PinStore.set(prefs, code, "epin")
                        prefs.edit().putInt("epin_fail", 0).putLong("epin_until", 0L).apply()
                        closeEditView()
                        wkSync("کد ویرایش تغییر کرد")
                    } else {
                        first = null
                        pv.shake()
                        pv.setMessage("دو کد یکی نبود؛ دوباره یک کد بزنید", true)
                        main.postDelayed({ entered.clear(); refresh() }, 250)
                    }
                }
            }
        }
        root.addView(
            pv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        editView = pv
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        try { web.evaluateJavascript("try{document.getElementById('wf').contentWindow.__secretClose()}catch(e){}", null) } catch (_: Exception) {}
        try { web.evaluateJavascript("try{document.getElementById('wf').contentWindow.__wkLock()}catch(e){}", null) } catch (_: Exception) {}
        closeEditView()
        // Leaving the app (home button, app switcher): push any pending auto-backup right now.
        try { web.evaluateJavascript("window.__flushBackup&&window.__flushBackup()", null) } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        try { web.evaluateJavascript("window.__smsPull&&window.__smsPull()", null) } catch (_: Exception) {}
        // Lock again only if the app was away for more than a minute (share sheets etc. don't count).
        if (stoppedAt > 0 && lockView == null && lockEnabled() && canLock() &&
            SystemClock.elapsedRealtime() - stoppedAt > 60_000
        ) showLock()
    }

    // ---- Text shared from the SMS app -> «دریافتی‌ها» ----
    private var sharedText: String? = null
    private fun grabShared(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            sharedText = i.getStringExtra(Intent.EXTRA_TEXT)
            i.action = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        grabShared(intent)
        try { web.evaluateJavascript("window.__shareIn&&window.__shareIn()", null) } catch (_: Exception) {}
    }

    // ---- Camera permission (the manifest declares CAMERA, so camera intents need it granted) ----
    private var camPending: (() -> Unit)? = null
    private var camDenied: (() -> Unit)? = null
    private val camPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val go = camPending; val no = camDenied
            camPending = null; camDenied = null
            if (ok) go?.invoke() else no?.invoke()
        }

    private fun withCamera(onDenied: () -> Unit, go: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) { go(); return }
        camPending = go; camDenied = onDenied
        camPerm.launch(android.Manifest.permission.CAMERA)
    }

    // ---- Barcode scan (offline, no Google Play services needed) ----
    private val barcodeLauncher =
        registerForActivityResult(ScanContract()) { res ->
            val v = res.contents ?: return@registerForActivityResult // cancelled: stay silent
            js("window.__nativeBarcode&&window.__nativeBarcode(${JSONObject.quote(v)})")
        }

    // ---- (unused now) Deposit SMS ----
    private val smsPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            prefs.edit().putBoolean("sms_on", ok).apply()
            js("window.__smsState&&window.__smsState($ok)")
        }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val result = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }

    private var camUri: Uri? = null

    private val camChooser =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val u = camUri
            fileCallback?.onReceiveValue(if (ok && u != null) arrayOf(u) else null)
            fileCallback = null
        }

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) {
                js("window.__nativeSpeech&&window.__nativeSpeech(${JSONObject.quote(text)})")
            } else {
                done(false, "گفتاری دریافت نشد")
            }
        }

    private val backupPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (text.isNullOrBlank()) {
                    done(false, "فایل خالی است")
                } else {
                    js("window.__nativeRestore&&window.__nativeRestore(${JSONObject.quote(text)})")
                }
            } catch (_: Exception) {
                done(false, "خواندن فایل ممکن نشد")
            }
        }


    // ---- Receipt scan (camera / gallery -> OCR -> rows) ----
    private var receiptFile: File? = null

    private val receiptCamera =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = receiptFile
            if (ok && f != null && f.exists()) runReceiptOcr(f) else js("window.__receiptFail&&window.__receiptFail('لغو شد')")
        }

    private val receiptGallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) { js("window.__receiptFail&&window.__receiptFail('لغو شد')"); return@registerForActivityResult }
            try {
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, "receipt_pick.jpg")
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runReceiptOcr(f)
            } catch (_: Exception) {
                js("window.__receiptFail&&window.__receiptFail('خواندن تصویر ممکن نشد')")
            }
        }

    private fun receiptProgress(msg: String) {
        js("window.__receiptProgress&&window.__receiptProgress(${JSONObject.quote(msg)})")
    }

    /** Makes sure fas/eng language data exists: bundled in assets/tessdata, or downloaded once (~5 MB). */
    private fun ensureTessData(): File {
        val base = File(filesDir, "tess")
        val dir = File(base, "tessdata").apply { mkdirs() }
        for (lang in listOf("fas", "eng")) {
            val out = File(dir, "$lang.traineddata")
            if (out.exists() && out.length() > 100_000) continue
            val bundled = try { assets.open("tessdata/$lang.traineddata") } catch (_: Exception) { null }
            if (bundled != null) {
                bundled.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            } else {
                receiptProgress("دانلود یک‌باره‌ی داده‌ی تشخیص متن ($lang)…")
                val tmp = File(dir, "$lang.part")
                val c = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/$lang.traineddata")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 60000
                try {
                    if (c.responseCode !in 200..299) throw IllegalStateException("http ${c.responseCode}")
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                } finally {
                    c.disconnect()
                }
                if (tmp.length() < 100_000) { tmp.delete(); throw IllegalStateException("bad data") }
                tmp.renameTo(out)
            }
        }
        return base
    }

    private fun runReceiptOcr(file: File) {
        receiptProgress("در حال خواندن رسید…")
        Thread {
            if (prefs.getString("ai_key", "").isNullOrBlank().not()) {
                try {
                    receiptProgress("در حال تحلیل رسید با هوش مصنوعی…")
                    val res = aiReadReceipt(file)
                    if (res.getJSONArray("items").length() > 0) {
                        loadScaled(file, 240)?.let { b ->
                            val o = java.io.ByteArrayOutputStream()
                            b.compress(Bitmap.CompressFormat.JPEG, 70, o); b.recycle()
                            res.put("thumb", android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP))
                        }
                        js("window.__nativeReceiptItems&&window.__nativeReceiptItems(${JSONObject.quote(res.toString())})")
                        return@Thread
                    }
                    receiptProgress("هوش مصنوعی قلمی پیدا نکرد؛ تلاش با روش آفلاین…")
                } catch (e: Exception) {
                    receiptProgress("اتصال به هوش مصنوعی ناموفق بود (${e.message?.take(80) ?: ""})؛ تلاش با روش آفلاین…")
                }
            }
            try {
                val bmp = loadScaled(file, 2200) ?: throw IllegalStateException("img")
                val base = ensureTessData()
                receiptProgress("در حال تشخیص متن…")
                val tess = TessBaseAPI()
                try {
                    if (!tess.init(base.absolutePath, "fas+eng")) throw IllegalStateException("init")
                    tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                    val clean = prepareForOcr(bmp)
                    tess.setImage(clean)
                    tess.getUTF8Text() // runs recognition
                    val arr = JSONArray()
                    val it = tess.resultIterator
                    if (it != null) {
                        it.begin()
                        do {
                            val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                            if (!t.isNullOrEmpty()) {
                                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                arr.put(
                                    JSONObject().put("t", t).put("x", r.left).put("y", r.top)
                                        .put("w", r.width()).put("h", r.height())
                                )
                            }
                        } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                        it.delete()
                    }
                    val out = JSONObject().put("words", arr).put("w", bmp.width).put("h", bmp.height)
                    js("window.__nativeReceipt&&window.__nativeReceipt(${JSONObject.quote(out.toString())})")
                } finally {
                    tess.recycle()
                    bmp.recycle()
                }
            } catch (e: Exception) {
                val m = if (e.message?.startsWith("http") == true || e is java.io.IOException)
                    "برای بار اول اینترنت لازم است تا داده‌ی تشخیص متن دانلود شود"
                else "تشخیص متن ناموفق بود؛ دوباره و با نور بهتر عکس بگیرید"
                js("window.__receiptFail&&window.__receiptFail(${JSONObject.quote(m)})")
            }
        }.start()
    }


    // ---- AI vision receipt reader (Gemini / Claude / OpenAI-compatible) ----
    private val aiPrompt = "این تصویر یک رسید، لیست خرید یا فاکتور (احتمالاً فارسی، چاپی یا دست‌نویس، چندقلمی) است. " +
        "همه‌ی ردیف‌های کالا را دقیق بخوان و فقط یک JSON معتبر برگردان، بدون هیچ متن دیگر و بدون ```. قالب: " +
        "{\"currency\":\"rial|toman|unknown\",\"grand_total\":جمع کل مبلغ چاپ‌شده روی رسید یا 0,\"item_count\":عدد «جمع اقلام/تعداد اقلام» چاپ‌شده یا 0,\"items\":[{\"name\":\"نام کالا\",\"qty\":عدد,\"unit\":\"واحد مثل عدد/کیلوگرم یا خالی\",\"total\":قیمت کل همان ردیف به عدد}]}. " +
        "قواعد: ارقام فارسی را به انگلیسی تبدیل کن. اعداد را بدون جداکننده بنویس. سطرهای جمع کل، مالیات، تخفیف و سرتیتر را وارد نکن. " +
        "اگر قیمت واحد (فی) و تعداد هست ولی قیمت کل نیست، total = qty × فی. اگر چیزی نخواندی آن را حدس نزن؛ اگر کل ردیف ناخوانا بود حذفش کن. " +
        "ترتیب ردیف‌ها همان ترتیب روی رسید باشد."

    private fun aiReadOnce(file: File, hint: String): JSONObject {
        val bmp = loadScaled(file, 1800) ?: throw IllegalStateException("img")
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        val prov = prefs.getString("ai_prov", "gemini") ?: "gemini"
        val key = prefs.getString("ai_key", "") ?: ""
        val model = prefs.getString("ai_model", "")?.trim().orEmpty()
        var base = prefs.getString("ai_base", "")?.trim().orEmpty().trimEnd('/')
        val url: String
        val body: JSONObject
        val headers = HashMap<String, String>()
        when (prov) {
            "anthropic" -> {
                url = (if (base.isEmpty()) "https://api.anthropic.com" else base) + "/v1/messages"
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
                body = JSONObject().put("model", model.ifEmpty { "claude-sonnet-4-6" }).put("max_tokens", 4000)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                        .put(JSONObject().put("type", "text").put("text", aiPrompt + hint)))))
            }
            "openai" -> {
                url = (if (base.isEmpty()) "https://api.openai.com/v1" else base) + "/chat/completions"
                headers["Authorization"] = "Bearer $key"
                body = JSONObject().put("model", model.ifEmpty { "gpt-4o" }).put("temperature", 0)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", aiPrompt + hint))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))))))
            }
            else -> {
                val m = model.ifEmpty { "gemini-2.5-flash" }
                url = (if (base.isEmpty()) "https://generativelanguage.googleapis.com" else base) + "/v1beta/models/$m:generateContent"
                headers["x-goog-api-key"] = key
                body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", aiPrompt + hint))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                    .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
            }
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val txt: String
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("http $code")
            val j = JSONObject(resp)
            txt = when (prov) {
                "anthropic" -> j.getJSONArray("content").let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }
                "openai" -> j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                else -> j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }
            }
        } finally {
            c.disconnect()
        }
        val a = txt.indexOf('{')
        val b = txt.lastIndexOf('}')
        if (a < 0 || b <= a) throw IllegalStateException("bad json")
        val o = JSONObject(txt.substring(a, b + 1))
        val items = o.optJSONArray("items") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val row = items.getJSONObject(i)
            val name = row.optString("name").trim()
            val total = row.optDouble("total", 0.0)
            if (name.isEmpty() && total <= 0) continue
            out.put(JSONObject().put("name", name).put("qty", row.optDouble("qty", 1.0).let { q -> if (q > 0) q else 1.0 })
                .put("unit", row.optString("unit")).put("total", total).put("cur", o.optString("currency", "unknown")))
        }
        val grand = o.optDouble("grand_total", 0.0)
        val cnt = o.optInt("item_count", 0)
        var sum = 0.0
        for (i in 0 until out.length()) sum += out.getJSONObject(i).optDouble("total", 0.0)
        val sumOk = grand <= 0 || kotlin.math.abs(sum - grand) <= 1.0
        val cntOk = cnt <= 0 || cnt == out.length()
        return JSONObject().put("items", out).put("grand", grand).put("count", cnt)
            .put("sum", sum).put("ok", sumOk && cntOk)
    }

    /** Reads the receipt; if the row sum / item count disagrees with the printed totals, retries once with a hint. */
    private fun aiReadReceipt(file: File): JSONObject {
        val first = aiReadOnce(file, "")
        if (first.optBoolean("ok") || first.getJSONArray("items").length() == 0) return first
        receiptProgress("جمع ردیف‌ها با جمع رسید نمی‌خواند؛ بررسی دوباره…")
        val second = try {
            aiReadOnce(file, " توجه: در خواندن قبلی جمع ردیف‌ها (${first.optDouble("sum").toLong()}) با جمع چاپ‌شده روی رسید (${first.optDouble("grand").toLong()}) " +
                "یا تعداد اقلام (${first.optInt("count")}) نمی‌خواند. هر ردیف و هر رقم را دوباره با دقت بخوان و ردیف جامانده یا رقم اشتباه را اصلاح کن.")
        } catch (_: Exception) { return first }
        if (second.optBoolean("ok")) return second
        val d1 = kotlin.math.abs(first.optDouble("sum") - first.optDouble("grand"))
        val d2 = kotlin.math.abs(second.optDouble("sum") - second.optDouble("grand"))
        return if (d2 < d1) second else first
    }

    /**
     * Cleans a receipt photo for OCR: local-contrast black/white conversion, then removes the long
     * horizontal/vertical table border lines that otherwise stop Tesseract from reading the rows.
     * The output has the same size as the input, so word positions stay valid.
     */
    private fun prepareForOcr(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        val iw = w + 1
        val integ = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += g[y * w + x]
                integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row
            }
        }
        val r = maxOf(15, w / 80)
        val black = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r)
            val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r)
                val x1 = minOf(w - 1, x + r)
                val sum = integ[(y1 + 1) * iw + x1 + 1] - integ[y0 * iw + x1 + 1] -
                    integ[(y1 + 1) * iw + x0] + integ[y0 * iw + x0]
                val n = (x1 - x0 + 1) * (y1 - y0 + 1)
                black[y * w + x] = g[y * w + x] * n * 100 < sum * 92
            }
        }
        val kill = BooleanArray(w * h)
        val minH = (w * 0.08).toInt()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!black[y * w + x]) { x++; continue }
                var e = x
                while (e < w && black[y * w + e]) e++
                if (e - x >= minH) {
                    for (yy in maxOf(0, y - 2)..minOf(h - 1, y + 2))
                        for (xx in maxOf(0, x - 2)..minOf(w - 1, e + 1)) kill[yy * w + xx] = true
                }
                x = e
            }
        }
        val minV = (h * 0.04).toInt()
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!black[y * w + x]) { y++; continue }
                var e = y
                while (e < h && black[e * w + x]) e++
                if (e - y >= minV) {
                    for (xx in maxOf(0, x - 2)..minOf(w - 1, x + 2))
                        for (yy in maxOf(0, y - 2)..minOf(h - 1, e + 1)) kill[yy * w + xx] = true
                }
                y = e
            }
        }
        for (i in px.indices) px[i] = if (black[i] && !kill[i]) Color.BLACK else Color.WHITE
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Decodes a photo, applies its EXIF rotation and limits the longest side. */
    private fun loadScaled(f: File, maxSide: Int): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxSide * 2) s *= 2
        val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        var b = android.graphics.BitmapFactory.decodeFile(f.absolutePath, o2) ?: return null
        val deg = when (androidx.exifinterface.media.ExifInterface(f.absolutePath)
            .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val sc = maxSide.toFloat() / maxOf(b.width, b.height)
        val m = android.graphics.Matrix()
        if (sc < 1f) m.postScale(sc, sc)
        if (deg != 0f) m.postRotate(deg)
        if (!m.isIdentity) {
            val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (nb !== b) b.recycle()
            b = nb
        }
        return b
    }

    /** Lets the user choose (or add) the Google account used for Drive backup, without clearing app data. */
    private val accountPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val email = r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (r.resultCode == RESULT_OK && !email.isNullOrBlank()) {
                prefs.edit().putString("driveAcct", email).putLong("lastPin", 0L).apply()
                js("window.__driveAccount&&window.__driveAccount(${JSONObject.quote(email)})")
                done(true, "حساب Google برای پشتیبان تغییر کرد: $email")
            }
        }

    /** The Drive authorization request, bound to the chosen account when there is one. */
    private fun driveRequest(): AuthorizationRequest {
        val b = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
        val email = prefs.getString("driveAcct", null)
        if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            try {
                val res = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data!!)
                val token = res.accessToken
                if (token != null) driveAction?.invoke(token) else done(false, "ورود به Google انجام نشد")
            } catch (_: Exception) {
                done(false, "ورود به Google لغو شد یا انجام نشد")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        grabShared(intent)
        patch = PatchStore(this)
        // Safe mode: if the previous launch with patches never got going, disable them.
        if (patch.pending) {
            patch.enabled = false
            patch.pending = false
            android.widget.Toast.makeText(this, "وصله‌ها به‌صورت امن غیرفعال شد", android.widget.Toast.LENGTH_LONG).show()
        } else if (patch.enabled && patch.hasAny()) {
            patch.pending = true
        }
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", PatchHandler(this, patch))
            .build()

        root = FrameLayout(this)
        web = WebView(this)
        try { web.settings.textZoom = prefs.getInt("text_zoom", 100) } catch (e: Exception) {}
        configure(web, withBridge = true)
        root.addView(
            web,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        updateBarIcons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                editView?.let {
                    closeEditView()
                    return
                }
                setupView?.let {
                    root.removeView(it)
                    setupView = null
                    js("window.__secRefresh&&window.__secRefresh('')")
                    return
                }
                web.evaluateJavascript("(window.__onBack?window.__onBack():false)") { r ->
                    if (r != "true") finish()
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        if (lockEnabled() && canLock()) showLock()
        autoFetchPatch()
    }

    private fun autoFetchPatch() {
        val u = patch.url
        if (!patch.enabled || !patch.autoFetch || u.isBlank()) return
        Thread {
            try {
                if (patch.applyJson(patch.fetchRemote(u))) main.post { web.reload() }
            } catch (_: Exception) {
            }
        }.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBarIcons()
    }

    private fun updateBarIcons() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val c = WindowCompat.getInsetsController(window, root)
        c.isAppearanceLightStatusBars = !night
        c.isAppearanceLightNavigationBars = !night
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView, withBridge: Boolean) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        w.setBackgroundColor(Color.TRANSPARENT)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }
        w.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (withBridge) main.postDelayed({ patch.pending = false }, 3000)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }
        }
        if (withBridge) {
            w.webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    return try {
                        if (fileChooserParams?.isCaptureEnabled == true) {
                            val dir = File(cacheDir, "exports").apply { mkdirs() }
                            val f = File(dir, "img_cam.jpg")
                            if (f.exists()) f.delete()
                            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                            camUri = uri
                            withCamera({ fileCallback?.onReceiveValue(null); fileCallback = null; done(false, "دسترسی دوربین داده نشد") }) { camChooser.launch(uri) }
                        } else {
                            fileChooser.launch(fileChooserParams!!.createIntent())
                        }
                        true
                    } catch (_: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
            w.addJavascriptInterface(Bridge(), "Android")
        }
    }

    private fun js(code: String) {
        main.post { web.evaluateJavascript(code, null) }
    }

    private fun done(ok: Boolean, msg: String) {
        js("window.__nativeDone&&window.__nativeDone($ok,${JSONObject.quote(msg)})")
    }

    /** Methods below are called from JavaScript as window.Android.* on a background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun getAppName(): String = appName()

        @JavascriptInterface
        fun editAppName() {
            main.post {
                val et = android.widget.EditText(this@MainActivity)
                et.setText(appName())
                et.setSelectAllOnFocus(true)
                et.layoutDirection = View.LAYOUT_DIRECTION_RTL
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("نام برنامه")
                    .setView(et)
                    .setPositiveButton("ذخیره") { _, _ ->
                        val v = et.text.toString().trim().take(30)
                        if (v.isNotEmpty()) {
                            prefs.edit().putString("app_name", v).apply()
                            js("window.__secRefresh&&window.__secRefresh(${JSONObject.quote("نام برنامه تغییر کرد")})")
                        }
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun setTextZoom(pct: Int) {
            val z = pct.coerceIn(70, 170)
            prefs.edit().putInt("text_zoom", z).apply()
            main.post { try { web.settings.textZoom = z } catch (e: Exception) {} }
        }

        @JavascriptInterface
        fun getTextZoom(): Int = prefs.getInt("text_zoom", 100)

        @JavascriptInterface
        fun takeSharedText(): String { val t = sharedText ?: ""; sharedText = null; return t }

        @JavascriptInterface
        fun smsEnabled(): Boolean = prefs.getBoolean("sms_on", false) &&
            checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun setSms(on: Boolean) {
            main.post {
                if (!on) {
                    prefs.edit().putBoolean("sms_on", false).apply()
                    js("window.__smsState&&window.__smsState(false)")
                } else if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("sms_on", true).apply()
                    js("window.__smsState&&window.__smsState(true)")
                } else smsPerm.launch(android.Manifest.permission.RECEIVE_SMS)
            }
        }

        /** Returns the queued deposits as a JSON array and clears the queue. */
        @JavascriptInterface
        fun takePendingSms(): String = synchronized(SmsReceiver.LOCK) {
            val v = prefs.getString("sms_pending", "[]") ?: "[]"
            if (v != "[]") prefs.edit().putString("sms_pending", "[]").apply()
            v
        }

        @JavascriptInterface
        fun openPatchPanel() {
            main.post { PatchPanel(this@MainActivity, patch) {
                    patch.pending = patch.enabled && patch.hasAny()
                    web.reload()
                }.show() }
        }

        /** Hidden section: ask for a fingerprint; only on success the page is told to open it. */
        /** Coding assistant: streams the model's answer into the "دستیار" page. */
        @JavascriptInterface
        fun aiChat(json: String) {
            Thread { runAiChat(json) }.start()
        }

        @JavascriptInterface
        fun aiStop() {
            aiCancel = true
            try { aiConn?.disconnect() } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun secretAuth() {
            main.post {
                promptBio {
                    js("try{document.getElementById('wf').contentWindow.__secretOk()}catch(e){}")
                }
            }
        }

        /** Hidden section open -> hide the screen from screenshots and the recent-apps preview. */
        /** Hidden section: its own Drive backup, a separate file in the app-data folder. */
        @JavascriptInterface
        fun secretDriveSave(json: String) {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token, SECRET_FILE) ?: createBackupFile(token, SECRET_FILE)
                            val r = http(
                                "POST", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
                                token, json.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", "PATCH"
                            )
                            secretDone(r.first in 200..299, if (r.first in 200..299) "ذخیره شد" else "ذخیره ناموفق بود (کد ${r.first})")
                        } catch (_: Exception) {
                            secretDone(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }

        /** Silent auto-backup of the hidden section: never opens a screen, reports back quietly. */
        @JavascriptInterface
        fun secretDriveAuto(json: String) {
            main.post {
                Identity.getAuthorizationClient(this@MainActivity).authorize(driveRequest())
                    .addOnSuccessListener { res ->
                        val token = if (res.hasResolution()) null else res.accessToken
                        if (token == null) {
                            secretAuto("auth")
                        } else {
                            Thread {
                                try {
                                    val id = findBackupId(token, SECRET_FILE) ?: createBackupFile(token, SECRET_FILE)
                                    val r = http(
                                        "POST", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media",
                                        token, json.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", "PATCH"
                                    )
                                    secretAuto(if (r.first in 200..299) "ok" else "fail")
                                } catch (_: Exception) {
                                    secretAuto("fail")
                                }
                            }.start()
                        }
                    }
                    .addOnFailureListener { secretAuto("fail") }
            }
        }

        @JavascriptInterface
        fun secretDriveRestore() {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token, SECRET_FILE)
                            if (id == null) {
                                secretDone(false, "نسخه‌ای پیدا نشد")
                            } else {
                                val r = http("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", token)
                                if (r.first in 200..299) js("try{document.getElementById('wf').contentWindow.__secretRestore(${JSONObject.quote(r.second)})}catch(e){}")
                                else secretDone(false, "دریافت ناموفق بود (کد ${r.first})")
                            }
                        } catch (_: Exception) {
                            secretDone(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }

        @JavascriptInterface
        fun secure(on: Boolean) {
            main.post {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        @JavascriptInterface
        fun isLockEnabled(): Boolean = lockEnabled()

        @JavascriptInterface
        fun setLock(on: Boolean) {
            main.post {
                val report = { v: Boolean -> js("window.__lockState&&window.__lockState($v)") }
                if (on && !canLock()) {
                    done(false, "اثر انگشت، چهره یا قفل صفحه روی گوشی تنظیم نشده است؛ کد برنامه را بسازید")
                    report(lockEnabled())
                    return@post
                }
                val commit = {
                    prefs.edit().putBoolean("lock", on).apply()
                    report(on)
                }
                // Changing the lock always needs a fresh authentication (when the phone has one).
                if (canAuth()) promptAuth(onSuccess = commit, onError = { report(lockEnabled()) }) else commit()
            }
        }

        @JavascriptInterface
        fun hasPin(): Boolean = hasAppPin()

        /** {"ready":bool,"finger":bool,"face":bool}: ready = a fingerprint or face is enrolled on the phone. */
        @JavascriptInterface
        fun bioInfo(): String {
            val pm = packageManager
            return JSONObject()
                .put("ready", canBio())
                .put("finger", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
                .put("face", pm.hasSystemFeature("android.hardware.biometrics.face"))
                .toString()
        }

        @JavascriptInterface
        fun editTabs(): String = wkTabsJson()

        @JavascriptInterface
        fun askEditPin() {
            main.post { askEditCode("لیست کار", true) { wkJs("__wkUnlock()") } }
        }

        @JavascriptInterface
        fun changeEditPin() {
            main.post { askEditCode("کد فعلی", false) { startEditSetup() } }
        }

        @JavascriptInterface
        fun setEditTab(tab: String, on: Boolean) {
            if (tab !in wkTabs) return
            main.post {
                if (on) {
                    prefs.edit().putBoolean("edit_t_$tab", true).apply()
                    wkSync("قفل روشن شد")
                } else {
                    askEditCode("خاموش کردن قفل", true) {
                        prefs.edit().putBoolean("edit_t_$tab", false).apply()
                        wkSync("قفل خاموش شد")
                    }
                }
            }
        }

        @JavascriptInterface
        fun setPin() {
            main.post {
                if (canAuth()) promptAuth(onSuccess = { startPinSetup() }) else startPinSetup()
            }
        }

        @JavascriptInterface
        fun clearPin() {
            main.post {
                val doClear = {
                    PinStore.clear(prefs)
                    // Without an app code and without a phone lock there is nothing left to ask for.
                    if (!canAuth()) prefs.edit().putBoolean("lock", false).apply()
                    js("window.__secRefresh&&window.__secRefresh('کد برنامه حذف شد')")
                }
                if (canAuth()) promptAuth(onSuccess = doClear) else doClear()
            }
        }

        @JavascriptInterface
        fun listen() {
            main.post {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "اقلام را بگویید")
                }
                try {
                    speech.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    done(false, "تشخیص گفتار روی این دستگاه نصب نیست؛ متن را تایپ کنید")
                }
            }
        }

        @JavascriptInterface
        fun scan() {
            main.post {
                try {
                    val o = ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                        .setPrompt("بارکد را مقابل دوربین بگیرید")
                        .setBeepEnabled(true)
                        .setOrientationLocked(false)
                    withCamera({ done(false, "دسترسی دوربین داده نشد؛ بارکد را تایپ کنید") }) { barcodeLauncher.launch(o) }
                } catch (e: Exception) {
                    done(false, "باز کردن دوربین ممکن نشد؛ دسترسی دوربین را بررسی کنید یا بارکد را تایپ کنید")
                }
            }
        }

        @JavascriptInterface
        fun getAiCfg(): String = JSONObject().put("prov", prefs.getString("ai_prov", "gemini"))
            .put("key", prefs.getString("ai_key", "")).put("model", prefs.getString("ai_model", ""))
            .put("base", prefs.getString("ai_base", "")).toString()

        @JavascriptInterface
        fun setAiCfg(json: String) {
            try {
                val o = JSONObject(json)
                prefs.edit().putString("ai_prov", o.optString("prov", "gemini")).putString("ai_key", o.optString("key").trim())
                    .putString("ai_model", o.optString("model").trim()).putString("ai_base", o.optString("base").trim()).apply()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun scanReceipt(fromGallery: Boolean) {
            main.post {
                try {
                    if (fromGallery) {
                        receiptGallery.launch("image/*")
                    } else {
                        val dir = File(cacheDir, "exports").apply { mkdirs() }
                        val f = File(dir, "receipt_cam.jpg")
                        if (f.exists()) f.delete()
                        receiptFile = f
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                        withCamera({ js("window.__receiptFail&&window.__receiptFail('دسترسی دوربین داده نشد')") }) { receiptCamera.launch(uri) }
                    }
                } catch (_: Exception) {
                    js("window.__receiptFail&&window.__receiptFail('باز کردن دوربین ممکن نشد')")
                }
            }
        }

        /** Renders the invoice as PNG/PDF and uploads it to the Telegram chat (kind = "img" or "pdf"). */
        @JavascriptInterface
        fun exportInvoiceTg(html: String, kind: String, name: String, token: String, chat: String) {
            main.post { tgCap = "فاکتور"; exportDoc(html, kind, name, Pair(token, chat)) }
        }

        /** Same as exportInvoiceTg but with a custom caption (work-list reports). kind = "img", "pdf" or "pdfp". */
        @JavascriptInterface
        fun exportTg(html: String, kind: String, name: String, token: String, chat: String, caption: String) {
            main.post { tgCap = caption.ifBlank { "گزارش" }.take(200); exportDoc(html, kind, name, Pair(token, chat)) }
        }

        /** Compact data snapshot for the background Telegram worker (daily report / reminders). */
        @JavascriptInterface
        fun tgSnap(json: String) {
            try {
                val o = JSONObject(json)
                prefs.edit().putString("tg_snap", json).apply()
                val on = o.optString("token").isNotEmpty() && o.optString("chat").isNotEmpty() &&
                    (o.optBoolean("rep") || o.optBoolean("rem"))
                TgWorker.schedule(applicationContext, on)
            } catch (_: Exception) {
            }
        }

        /** Shared "already sent today" marks, so the page and the worker never send the same message twice. */
        @JavascriptInterface
        fun tgDone(kind: String, key: String): Boolean = prefs.getString("tg_last_$kind", "") == key

        @JavascriptInterface
        fun tgMark(kind: String, key: String) {
            prefs.edit().putString("tg_last_$kind", key).apply()
        }

        @JavascriptInterface
        fun exportInvoice(html: String, kind: String, name: String) {
            main.post { if (kind == "print") printHtml(html, name) else exportDoc(html, kind, name) }
        }

        /** Backup, option 1: write the JSON to a file and open the share sheet (Gmail, Drive, Telegram...). */
        @JavascriptInterface
        fun shareBackup(json: String, rawName: String) {
            main.post {
                try {
                    val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    val dir = File(cacheDir, "exports").apply { mkdirs() }
                    val f = File(dir, "$name.json")
                    f.writeText(json, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "پشتیبان " + appName())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "ارسال پشتیبان"))
                } catch (_: Exception) {
                    done(false, "ساخت فایل پشتیبان ناموفق بود")
                }
            }
        }

        /** Work-list report: write a .txt file and open the share sheet. */
        @JavascriptInterface
        fun shareReport(text: String, rawName: String) {
            main.post {
                try {
                    val f = reportFile(text, rawName)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "گزارش کار")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "ارسال گزارش"))
                } catch (_: Exception) {
                    done(false, "ساخت فایل گزارش ناموفق بود")
                }
            }
        }

        /** Work-list report: save the .txt file into Downloads/FaktorSaz on the phone. */
        @JavascriptInterface
        fun saveReport(text: String, rawName: String) {
            main.post {
                try {
                    saveToDownloads(reportFile(text, rawName), "text/plain")
                    done(true, "گزارش در پوشهٔ Download/FaktorSaz ذخیره شد")
                } catch (_: Exception) {
                    done(false, "ذخیره گزارش ناموفق بود")
                }
            }
        }

        @JavascriptInterface
        fun pickBackup() {
            main.post {
                try {
                    backupPicker.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    done(false, "باز کردن انتخاب فایل ممکن نشد")
                }
            }
        }

        /** Backup, option 2: Google Drive app-data folder (hidden from the user's normal Drive files). */
        @JavascriptInterface
        fun driveBackup(json: String) {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token) ?: createBackupFile(token)
                            val r = http(
                                "POST", uploadUrl(id),
                                token, json.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", "PATCH"
                            )
                            if (r.first in 200..299) done(true, "پشتیبان در Google Drive ذخیره شد")
                            else done(false, "ذخیره در Drive ناموفق بود (کد ${r.first})")
                        } catch (_: Exception) {
                            done(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }

        /** The Google account chosen for Drive backup ("" = the phone's default). */
        @JavascriptInterface
        fun driveAccount(): String = prefs.getString("driveAcct", "") ?: ""

        /** Opens the Google account chooser (with "Add account"); the choice is remembered. */
        @JavascriptInterface
        fun pickDriveAccount() {
            main.post {
                try {
                    val opts = AccountPicker.AccountChooserOptions.Builder()
                        .setAllowableAccountsTypes(listOf("com.google"))
                        .setAlwaysShowAccountPicker(true)
                        .build()
                    accountPicker.launch(AccountPicker.newChooseAccountIntent(opts))
                } catch (_: Exception) {
                    done(false, "باز کردن انتخاب حساب Google ممکن نشد")
                }
            }
        }

        /** Silent auto-backup: never opens any screen; reports the result back to the page. */
        @JavascriptInterface
        fun driveAutoBackup(json: String) {
            main.post {
                val request = driveRequest()
                Identity.getAuthorizationClient(this@MainActivity).authorize(request)
                    .addOnSuccessListener { res ->
                        val token = if (res.hasResolution()) null else res.accessToken
                        if (token == null) {
                            js("window.__autoState&&window.__autoState('auth')")
                        } else {
                            Thread {
                                try {
                                    val id = findBackupId(token) ?: createBackupFile(token)
                                    val r = http(
                                        "POST", uploadUrl(id),
                                        token, json.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8", "PATCH"
                                    )
                                    val st = if (r.first in 200..299) "ok" else "fail"
                                    js("window.__autoState&&window.__autoState('$st')")
                                } catch (_: Exception) {
                                    js("window.__autoState&&window.__autoState('fail')")
                                }
                            }.start()
                        }
                    }
                    .addOnFailureListener { js("window.__autoState&&window.__autoState('fail')") }
            }
        }

        /** Lists the saved versions (Drive revisions) of the backup so the user can pick an older one. */
        @JavascriptInterface
        fun driveVersions() {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token)
                            if (id == null) {
                                done(false, "پشتیبانی در Drive پیدا نشد")
                            } else {
                                val all = JSONArray()
                                var page: String? = null
                                var guard = 0
                                do {
                                    var url = "https://www.googleapis.com/drive/v3/files/$id/revisions" +
                                        "?pageSize=200&fields=nextPageToken,revisions(id,modifiedTime,size)"
                                    if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
                                    val r = http("GET", url, token)
                                    if (r.first !in 200..299) {
                                        done(false, "دریافت نسخه‌های Drive ناموفق بود (کد ${r.first})")
                                        return@Thread
                                    }
                                    val o = JSONObject(r.second)
                                    val a = o.optJSONArray("revisions")
                                    if (a != null) for (i in 0 until a.length()) all.put(a.getJSONObject(i))
                                    page = if (o.has("nextPageToken")) o.getString("nextPageToken") else null
                                    guard++
                                } while (page != null && guard < 20)
                                js("window.__nativeVersions&&window.__nativeVersions(${JSONObject.quote(all.toString())})")
                            }
                        } catch (_: Exception) {
                            done(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }

        @JavascriptInterface
        fun driveRestoreVersion(rev: String) {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token)
                            if (id == null) {
                                done(false, "پشتیبانی در Drive پیدا نشد")
                            } else {
                                val r = http(
                                    "GET",
                                    "https://www.googleapis.com/drive/v3/files/$id/revisions/" +
                                        URLEncoder.encode(rev, "UTF-8") + "?alt=media",
                                    token
                                )
                                if (r.first in 200..299) js("window.__nativeRestore&&window.__nativeRestore(${JSONObject.quote(r.second)})")
                                else done(false, "دریافت این نسخه ناموفق بود (کد ${r.first})")
                            }
                        } catch (_: Exception) {
                            done(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }

        @JavascriptInterface
        fun driveRestore() {
            main.post {
                withDriveToken { token ->
                    Thread {
                        try {
                            val id = findBackupId(token)
                            if (id == null) {
                                done(false, "پشتیبانی در Drive پیدا نشد")
                            } else {
                                val r = http("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", token)
                                if (r.first in 200..299) js("window.__nativeRestore&&window.__nativeRestore(${JSONObject.quote(r.second)})")
                                else done(false, "دریافت از Drive ناموفق بود (کد ${r.first})")
                            }
                        } catch (_: Exception) {
                            done(false, "ارتباط با Google برقرار نشد؛ اینترنت را بررسی کنید")
                        }
                    }.start()
                }
            }
        }
    }

    // ---- Coding assistant (streaming chat with the saved AI key: Claude / OpenAI-compatible / Gemini) ----
    @Volatile private var aiCancel = false
    @Volatile private var aiConn: HttpURLConnection? = null

    private fun aiSend(code: String) {
        js("try{document.getElementById('af').contentWindow.$code}catch(e){}")
    }

    private fun aiEnd(id: Int, ok: Boolean, msg: String) = aiSend("__aiEnd($id,$ok,${JSONObject.quote(msg)})")

    private fun runAiChat(json: String) {
        val req = JSONObject(json)
        val id = req.getInt("id")
        val key = prefs.getString("ai_key", "")?.trim().orEmpty()
        if (key.isEmpty()) { aiEnd(id, false, "nokey"); return }
        aiCancel = false
        val prov = prefs.getString("ai_prov", "gemini") ?: "gemini"
        val model = prefs.getString("ai_model", "")?.trim().orEmpty()
        val base = prefs.getString("ai_base", "")?.trim().orEmpty().trimEnd('/')
        val system = req.optString("system")
        val msgs = req.getJSONArray("messages")
        val url: String
        val body = JSONObject()
        val headers = HashMap<String, String>()
        when (prov) {
            "anthropic" -> {
                url = (if (base.isEmpty()) "https://api.anthropic.com" else base) + "/v1/messages"
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
                body.put("model", model.ifEmpty { "claude-sonnet-5-5" }).put("max_tokens", 8000)
                    .put("stream", true).put("system", system).put("messages", msgs)
            }
            "openai" -> {
                url = (if (base.isEmpty()) "https://api.openai.com/v1" else base) + "/chat/completions"
                headers["Authorization"] = "Bearer $key"
                val all = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                for (i in 0 until msgs.length()) all.put(msgs.getJSONObject(i))
                body.put("model", model.ifEmpty { "gpt-4o" }).put("stream", true).put("messages", all)
            }
            else -> {
                val m = model.ifEmpty { "gemini-2.5-flash" }
                url = (if (base.isEmpty()) "https://generativelanguage.googleapis.com" else base) +
                    "/v1beta/models/$m:streamGenerateContent?alt=sse"
                headers["x-goog-api-key"] = key
                val contents = JSONArray()
                for (i in 0 until msgs.length()) {
                    val o = msgs.getJSONObject(i)
                    contents.put(
                        JSONObject().put("role", if (o.optString("role") == "assistant") "model" else "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", o.optString("content"))))
                    )
                }
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
            }
        }
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            aiConn = c
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val detail = try {
                    val e = JSONObject(err).opt("error")
                    (if (e is JSONObject) e.optString("message") else e?.toString()) ?: ""
                } catch (_: Exception) { "" }
                val hint = when (code) {
                    401, 403 -> "کلید API نادرست است یا دسترسی ندارد"
                    404 -> "نام مدل نادرست است"
                    429 -> "سقف مصرف یا اعتبار تمام شده است"
                    else -> ""
                }
                aiEnd(id, false, "خطا $code" + (if (hint.isNotEmpty()) ": $hint" else "") + (if (detail.isNotEmpty()) "\n" + detail.take(200) else ""))
                return
            }
            val sb = StringBuilder()
            var last = SystemClock.elapsedRealtime()
            fun flush() {
                if (sb.isNotEmpty()) { aiSend("__aiChunk($id,${JSONObject.quote(sb.toString())})"); sb.setLength(0) }
                last = SystemClock.elapsedRealtime()
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    if (aiCancel) break
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = try { JSONObject(data) } catch (_: Exception) { continue }
                    var piece = ""
                    when (prov) {
                        "anthropic" -> {
                            if (o.optString("type") == "content_block_delta") {
                                val d = o.optJSONObject("delta")
                                if (d != null && d.optString("type") == "text_delta") piece = d.optString("text")
                            } else if (o.optString("type") == "error") {
                                flush()
                                aiEnd(id, false, o.optJSONObject("error")?.optString("message")?.take(200) ?: "خطا")
                                return
                            }
                        }
                        "openai" -> {
                            val ch = o.optJSONArray("choices")
                            if (ch != null && ch.length() > 0) piece = ch.getJSONObject(0).optJSONObject("delta")?.optString("content", "") ?: ""
                        }
                        else -> {
                            val cand = o.optJSONArray("candidates")
                            val parts = cand?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) for (i in 0 until parts.length()) piece += parts.getJSONObject(i).optString("text")
                        }
                    }
                    if (piece.isNotEmpty() && piece != "null") {
                        sb.append(piece)
                        if (SystemClock.elapsedRealtime() - last >= 60) flush()
                    }
                }
            }
            flush()
            aiEnd(id, true, "")
        } catch (_: Exception) {
            if (aiCancel) aiEnd(id, true, "") else aiEnd(id, false, "ارتباط برقرار نشد؛ اینترنت را بررسی کنید")
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
            if (aiConn === c) aiConn = null
        }
    }

    private val SECRET_FILE = "faktorsaz-cache.json"

    private fun secretAuto(st: String) {
        js("try{document.getElementById('wf').contentWindow.__secretAuto('$st')}catch(e){}")
    }

    private fun secretDone(ok: Boolean, msg: String) {
        js("try{document.getElementById('wf').contentWindow.__secretDone($ok,${JSONObject.quote(msg)})}catch(e){}")
    }

    private fun withDriveToken(action: (String) -> Unit) {
        driveAction = action
        val request = driveRequest()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { res ->
                if (res.hasResolution()) {
                    val pi = res.pendingIntent
                    if (pi != null) authLauncher.launch(IntentSenderRequest.Builder(pi).build())
                    else done(false, "ورود به Google ممکن نشد")
                } else {
                    val t = res.accessToken
                    if (t != null) action(t) else done(false, "دسترسی به Drive داده نشد")
                }
            }
            .addOnFailureListener {
                done(false, "اتصال به Google ممکن نشد؛ تنظیمات Google Cloud و Play Services را بررسی کنید")
            }
    }

    private fun http(
        method: String, url: String, token: String,
        body: ByteArray? = null, contentType: String? = null, override: String? = null
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (override != null) c.setRequestProperty("X-HTTP-Method-Override", override)
        if (body != null) {
            c.doOutput = true
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    /** Upload URL; about once a day the new version is pinned so Drive never auto-deletes it. */
    private fun uploadUrl(id: String): String {
        val now = System.currentTimeMillis()
        val pin = now - prefs.getLong("lastPin", 0L) > 24L * 3600L * 1000L
        if (pin) prefs.edit().putLong("lastPin", now).apply()
        return "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media" +
            if (pin) "&keepRevisionForever=true" else ""
    }

    private fun findBackupId(token: String, fileName: String = "faktorsaz-backup.json"): String? {
        val q = URLEncoder.encode("name='$fileName'", "UTF-8")
        val r = http(
            "GET",
            "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)",
            token
        )
        if (r.first !in 200..299) throw IllegalStateException("drive ${r.first}")
        val arr = JSONObject(r.second).optJSONArray("files")
        return if (arr != null && arr.length() > 0) arr.getJSONObject(0).getString("id") else null
    }

    private fun createBackupFile(token: String, fileName: String = "faktorsaz-backup.json"): String {
        val meta = """{"name":"$fileName","parents":["appDataFolder"]}"""
        val r = http(
            "POST", "https://www.googleapis.com/drive/v3/files",
            token, meta.toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8"
        )
        if (r.first !in 200..299) throw IllegalStateException("drive ${r.first}")
        return JSONObject(r.second).getString("id")
    }

    /** Renders the invoice HTML in an offscreen WebView, then saves it as PNG or PDF and opens the share sheet. */
    private var tgCap = "فاکتور"

    private fun exportDoc(html: String, kind: String, rawName: String, tg: Pair<String, String>? = null) {
        val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val widthPx = 840 // 420 CSS px at 2x
        val off = WebView(this)
        configure(off, withBridge = false)
        off.settings.useWideViewPort = true
        off.settings.loadWithOverviewMode = true
        off.translationX = -(widthPx + 200).toFloat()
        root.addView(off, FrameLayout.LayoutParams(widthPx, 200))

        off.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                main.postDelayed({
                    view.evaluateJavascript("(function(){return Math.ceil(document.documentElement.scrollHeight)})()") { r ->
                        val hCss = r?.toIntOrNull() ?: 900
                        val hPx = hCss * 2
                        val lp = view.layoutParams
                        lp.height = hPx
                        view.layoutParams = lp
                        main.postDelayed({ capture(view, widthPx, hPx, kind, name, tg) }, 450)
                    }
                }, 500)
            }
        }
        off.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/assets/", html, "text/html", "utf-8", null
        )
    }

    private var printWeb: WebView? = null

    /** Renders HTML in an offscreen WebView and opens the system print dialog (printer or "Save as PDF"). */
    private fun printHtml(html: String, rawName: String) {
        val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "print" }
        printWeb?.let {
            try {
                root.removeView(it)
                it.destroy()
            } catch (_: Exception) {
            }
        }
        val w = WebView(this)
        configure(w, withBridge = false)
        printWeb = w
        w.translationX = -3000f
        root.addView(w, FrameLayout.LayoutParams(840, 400))
        w.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                main.postDelayed({
                    try {
                        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                        pm.print(
                            name,
                            view.createPrintDocumentAdapter(name),
                            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
                        )
                    } catch (_: Exception) {
                        done(false, "باز کردن پرینت ممکن نشد")
                    }
                }, 500)
            }
        }
        w.loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", html, "text/html", "utf-8", null)
    }

    private fun capture(v: WebView, w: Int, h: Int, kind: String, name: String, tg: Pair<String, String>? = null) {
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            v.draw(c)
            root.removeView(v)
            v.destroy()

            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file: File
            val mime: String
            if (kind == "pdfp") {
                // multi-page A4 PDF (long tables): the bitmap is sliced into A4-shaped pages
                file = File(dir, "$name.pdf")
                mime = "application/pdf"
                val pdf = PdfDocument()
                val pw = 595
                val ph = 842
                val scale = pw.toFloat() / w
                val sliceH = (ph / scale).toInt()
                var y = 0
                var n = 1
                while (y < h) {
                    val hh = minOf(sliceH, h - y)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(
                        bmp, Rect(0, y, w, y + hh),
                        Rect(0, 0, pw, (hh * scale).toInt()), Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    pdf.finishPage(page)
                    y += sliceH
                    n++
                }
                file.outputStream().use { pdf.writeTo(it) }
                pdf.close()
            } else if (kind == "pdf") {
                file = File(dir, "$name.pdf")
                mime = "application/pdf"
                val pdf = PdfDocument()
                val pw = 595
                val ph = (h.toFloat() / w * pw).toInt()
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
                pdf.close()
            } else {
                file = File(dir, "$name.png")
                mime = "image/png"
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bmp.recycle()

            if (tg != null) {
                // Send straight to Telegram (no share sheet); the upload runs off the main thread.
                val f = file
                val m = mime
                Thread {
                    val ok = TgNet.sendFile(tg.first, tg.second, f, m, tgCap)
                    done(ok, if (ok) "به تلگرام ارسال شد ✓" else "ارسال به تلگرام ناموفق بود (اینترنت/VPN را بررسی کنید)")
                }.start()
                return
            }

            saveToDownloads(file, mime)
            share(file, mime)
            done(true, "ذخیره شد: پوشهٔ Download/FaktorSaz")
        } catch (e: Exception) {
            try {
                root.removeView(v)
            } catch (_: Exception) {
            }
            done(false, "ساخت خروجی ناموفق بود")
        }
    }

    private fun reportFile(text: String, rawName: String): File {
        val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "report" }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        return File(dir, "$name.txt").apply { writeText(text, Charsets.UTF_8) }
    }

    private fun saveToDownloads(f: File, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FaktorSaz")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    private fun share(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "ارسال فاکتور"))
    }
}
