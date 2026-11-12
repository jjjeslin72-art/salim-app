package com.juya.salim

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.WindowInsets
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayOutputStream
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val VOICE_REQ = 7
    private val PHOTO_REQ = 8
    private val SOUND_REQ = 9
    private var camUri: Uri? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFFFBF6EE.toInt())
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // 화면 위(상태바)·아래(버튼바)·키보드에 글자가 가리지 않게
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        // 화면 파일을 https 주소로 열기 (서버 연결이 막히지 않게)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = WebChromeClient()
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        // 알림 허락 받기 (안드로이드 13 이상)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Notifier.ensureChannel(this)
        Notifier.scheduleNext(this)

        // 뒤로 가기: 열린 창이 있으면 닫고, 없으면 앱 닫기
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    private fun handleBack() {
        web.evaluateJavascript("(window.onBack&&window.onBack())?'y':'n'") { r ->
            if (r == null || !r.contains("y")) finish()
        }
    }

    @Deprecated("old devices")
    override fun onBackPressed() { handleBack() }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 날짜가 바뀌었을 수 있으니 화면 새로 그리기
        if (::web.isInitialized) web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null)
    }

    @Deprecated("simple result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SOUND_REQ) {
            if (resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val u: Uri? = data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                if (u != null) {
                    Notifier.prefs(this).edit().putString("soundUri", u.toString()).apply()
                    val title = Notifier.soundTitle(this)
                    web.evaluateJavascript("window.onSoundPicked&&window.onSoundPicked(${JSONObject.quote(title)})", null)
                }
            }
            return
        }
        if (requestCode == PHOTO_REQ) {
            val uri = if (resultCode == RESULT_OK) (data?.data ?: camUri) else null
            val temp = camUri
            camUri = null
            if (uri == null) { temp?.let { try { contentResolver.delete(it, null, null) } catch (e: Exception) {} }; return }
            Thread {
                val b64 = try { encodePhoto(uri) } catch (e: Exception) { null }
                // 찍은 영수증 사진은 갤러리에 남기지 않음
                if (temp != null && uri == temp) try { contentResolver.delete(temp, null, null) } catch (e: Exception) {}
                runOnUiThread {
                    if (b64 == null) Toast.makeText(this, "사진을 열 수 없어요 / Não abriu a foto", Toast.LENGTH_LONG).show()
                    else web.evaluateJavascript("window.onReceiptImage&&window.onReceiptImage('$b64')", null)
                }
            }.start()
            return
        }
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            web.evaluateJavascript("window.onVoice&&window.onVoice(${JSONObject.quote(text)})", null)
        }
    }

    /** 사진을 1600px 이하 JPEG로 줄이고 바로 세워서 base64로 */
    private fun encodePhoto(uri: Uri): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp0 = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val rot = try {
            contentResolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (e: Exception) { 0f }
        val scale = minOf(1f, 1600f / maxOf(bmp0.width, bmp0.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
        val bmp: Bitmap = Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    inner class Bridge {
        @JavascriptInterface
        fun loadState(): String = Notifier.prefs(this@MainActivity).getString("state", "") ?: ""

        /** 화면에서 저장할 때마다: 전체 데이터 + 알림 목록 + 알림 시간 */
        @JavascriptInterface
        fun saveState(json: String, sched: String, hour: Int, lang: String) {
            Notifier.prefs(this@MainActivity).edit()
                .putString("lang", lang)
                .putString("state", json)
                .putString("sched", sched)
                .putInt("hour", hour.coerceIn(0, 23))
                .apply()
            Notifier.scheduleNext(this@MainActivity)
        }

        @JavascriptInterface
        fun startVoice() {
            runOnUiThread {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    val pt = Notifier.prefs(this@MainActivity).getString("lang", "ko") == "pt"
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (pt) "pt-BR" else "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, if (pt) "Fale o que comprou ou cozinhou" else "산 물건이나 해먹은 음식을 말해 주세요")
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, VOICE_REQ)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "이 핸드폰에서는 음성 입력을 쓸 수 없어요", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 영수증 사진: "camera" 또는 "gallery" */
        @JavascriptInterface
        fun pickReceipt(mode: String) {
            runOnUiThread {
                try {
                    if (mode == "camera" && Build.VERSION.SDK_INT >= 29) {
                        val v = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt_${System.currentTimeMillis()}.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SalimAlimi")
                        }
                        camUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(MediaStore.EXTRA_OUTPUT, camUri)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        @Suppress("DEPRECATION")
                        startActivityForResult(i, PHOTO_REQ)
                    } else {
                        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent.createChooser(i, "영수증 / Cupom"), PHOTO_REQ)
                    }
                } catch (e: Exception) {
                    camUri = null
                    Toast.makeText(this@MainActivity, "카메라를 열 수 없어요 / Não abriu a câmera", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 장보기 목록을 왓츠앱·문자 등으로 보내기 */
        @JavascriptInterface
        fun share(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "📤"))
            }
        }

        /** 지금 바로 알림 한 번 보여주기 (시험용) */
        @JavascriptInterface
        fun testNotify() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    Toast.makeText(this@MainActivity, "알림 허용 / Permita avisos", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!Notifier.fire(this@MainActivity, true)) {
                    Toast.makeText(this@MainActivity, if (Notifier.pt(this@MainActivity)) "Nada para avisar agora 😊" else "지금은 알릴 게 없어요 😊", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** 알람 소리 설정 저장 (방식·볼륨·방해금지 시간·방해금지 모드) */
        @JavascriptInterface
        fun setAlarmPrefs(json: String) { Notifier.prefs(this@MainActivity).edit().putString("alarm", json).apply() }

        @JavascriptInterface
        fun soundTitle(): String = Notifier.soundTitle(this@MainActivity)

        /** 핸드폰에 있는 알람음 중에서 고르기 */
        @JavascriptInterface
        fun pickSound() {
            runOnUiThread {
                val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                        android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Notifier.soundUri(this@MainActivity))
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, SOUND_REQ)
                } catch (e: Exception) { Toast.makeText(this@MainActivity, "알람음 목록을 열 수 없어요", Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface
        fun previewSound() { runOnUiThread { Notifier.ring(this@MainActivity, true) } }

        /** 대답을 소리로 읽어 주기 */
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                val pt = Notifier.pt(this@MainActivity)
                val go = {
                    tts?.language = if (pt) Locale("pt", "BR") else Locale.KOREAN
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ans")
                }
                if (tts == null) {
                    tts = TextToSpeech(this@MainActivity) { st -> ttsReady = st == TextToSpeech.SUCCESS; if (ttsReady) go() }
                } else if (ttsReady) go()
            }
        }

        @JavascriptInterface
        fun isApp(): Boolean = true
    }
}
