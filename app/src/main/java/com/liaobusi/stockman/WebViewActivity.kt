package com.liaobusi.stockman
import com.liaobusi.stockman5.R

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman5.databinding.ActivityWebviewBinding
import com.liaobusi.stockman.db.FPResponse
import com.liaobusi.stockman.db.ZTReplayBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.removeSurrounding

/**
 * 内置 WebView，通过注入脚本拦截页面内 **XMLHttpRequest** 与 **fetch** 返回的 JSON 文本，
 * 在 [onJsonIntercepted] 中处理（默认打日志）。
 *
 * 启动：[start] 传入初始 URL；仅用于你信任的页面，避免向不可信站点暴露 JS Bridge。
 */
open class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_DESKTOP_UA = "desktop_ua"

        fun start(context: Context, url: String, desktopUa: Boolean = false) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_DESKTOP_UA, desktopUa),
            )
        }

        fun startDesktop(context: Context, url: String) = start(context, url, desktopUa = true)
    }

    private lateinit var binding: ActivityWebviewBinding

    private val jsonBridge = JsonBridge()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.webview_activity_title)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.customWebView.canGoBack()) {
                        binding.customWebView.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )

        binding.customWebView.apply {
            val desktopUa = intent.getBooleanExtra(EXTRA_DESKTOP_UA, false)
            if (desktopUa) {
                // Some sites serve "mobile app download" page to WebView/mobile UA.
                // Force desktop UA to request the web version.
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progressBar.progress = newProgress
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    injectJsonInterceptScript(view)
                    // 基础连通性探针：确保 evaluateJavascript 可执行
                    view.evaluateJavascript("(function(){return 'js_ok:' + String(!!window.JsonBridge);})()", null)
                }
            }
            addJavascriptInterface(jsonBridge, "JsonBridge")
        }

        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (url.isNotEmpty()) {
            binding.customWebView.loadUrl(url)
        } else {
            binding.customWebView.loadUrl("about:blank")
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.customWebView.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface("JsonBridge")
                destroy()
            }
        }
        super.onDestroy()
    }

    /**
     * 当页面脚本识别到合法 JSON 字符串（`{…}` / `[…]` 且可被 `JSON.parse`）时回调；运行在 UI 线程。
     */
    protected open fun onJsonIntercepted(url: String, json: String) {
        val shouldLog = url.startsWith("https://app.jiuyangongshe.com/jystock-app/api/v1/action/field") ||
            url.startsWith("https://x-quote.cls.cn/quote/index/up_down_analysis")
        if (shouldLog) {
            Log.d(TAG, "JSON url=$url len=${json.length} preview=${json.take(200)}")
        }

        if (url == "https://app.jiuyangongshe.com/jystock-app/api/v1/action/field") {
            lifecycleScope.launch(Dispatchers.IO) {
                val rsp = Gson().fromJson(json, FPResponse::class.java)
                val list = mutableListOf<ZTReplayBean>()
                rsp.data.forEach {
                    val groupName = it.name
                    val reason = it.reason ?: ""
                    val date = it.date!!.replace("-", "").toInt()
                    it.list?.forEach {
                        val code = it.code.removeSurrounding("\"", "\"").removePrefix("sz")
                            .removePrefix("sh")
                        val expound = it.article.action_info.expound
                        val time =
                            if (it.article.action_info.time?.contains(":") == true) it.article.action_info.time else "--:--:--"
                        val bean = Injector.appDatabase.ztReplayDao().getZTReplay(date, code)

                        val newBean = bean?.copy(
                            time = if (time == "--:--:--") bean.time else time,
                            groupName2 = groupName,
                            reason2 = reason,
                            expound2 = expound
                        ) ?: ZTReplayBean(
                            date,
                            code,
                            reason,
                            groupName,
                            expound,
                            time,
                            groupName2 = groupName,
                            reason2 = reason,
                            expound2 = expound
                        )


                        list.add(newBean)
                    }
                }
                Injector.appDatabase.ztReplayDao().insertAll(list)
                launch(Dispatchers.Main) {
                    Toast.makeText(this@WebViewActivity, "解析完成并保存", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 财联社：涨停池 up_pool（从页面 XHR/fetch 拦截 JSON）
        if (url.startsWith("https://x-quote.cls.cn/quote/index/up_down_analysis")) {
            val type = runCatching { Uri.parse(url).getQueryParameter("type") }.getOrNull()
            if (type == "up_pool") {
                lifecycleScope.launch(Dispatchers.IO) {
                    val inserted = runCatching {
                        StockRepo.saveClsUpPoolJson(json)
                    }.getOrElse { e ->
                        Log.e(TAG, "CLS up_pool parse failed", e)
                        0
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WebViewActivity, "财联社 up_pool 入库：$inserted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    }

    private fun injectJsonInterceptScript(wv: WebView) {
        WebViewJsonInterceptor.inject(wv)
    }

    @SuppressLint("JavascriptInterface")
    private inner class JsonBridge {
        @JavascriptInterface
        fun onJsonBase64(url: String, b64: String) {
            val json = WebViewJsonInterceptor.decodeBase64Json(b64) ?: return
            runOnUiThread { onJsonIntercepted(url, json) }
        }
    }
}
