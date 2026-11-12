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
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

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

    override fun onResume() {
        super.onResume()
        // 날짜가 바뀌었을 수 있으니 화면 새로 그리기
        if (::web.isInitialized) web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null)
    }

    @Deprecated("simple result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            web.evaluateJavascript("window.onVoice&&window.onVoice(${JSONObject.quote(text)})", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun loadState(): String = Notifier.prefs(this@MainActivity).getString("state", "") ?: ""

        /** 화면에서 저장할 때마다: 전체 데이터 + 알림 목록 + 알림 시간 */
        @JavascriptInterface
        fun saveState(json: String, sched: String, hour: Int) {
            Notifier.prefs(this@MainActivity).edit()
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
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "산 물건이나 해먹은 음식을 말해 주세요")
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, VOICE_REQ)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "이 핸드폰에서는 음성 입력을 쓸 수 없어요", Toast.LENGTH_LONG).show()
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
                startActivity(Intent.createChooser(send, "장보기 목록 보내기"))
            }
        }

        /** 지금 바로 알림 한 번 보여주기 (시험용) */
        @JavascriptInterface
        fun testNotify() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    Toast.makeText(this@MainActivity, "알림을 '허용'으로 바꿔 주세요", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!Notifier.fire(this@MainActivity, true)) {
                    Toast.makeText(this@MainActivity, "지금은 알릴 게 없어요 😊", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun isApp(): Boolean = true
    }
}
