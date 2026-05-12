package com.liaobusi.stockman

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman.repo.StockResult
import com.liaobusi.stockman5.databinding.FragmentUnusualActionListBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 异动/涨停池列表。
 *
 * - type=5: 涨停池（来自 StockRepo.getLimitUpPool 写入 UnusualActionHistory(type=5)）
 * - type=0: 异动（聚合 type in 1/2/3/4）
 * - 其他：按 type 精确筛选
 */
class UnusualActionListFragment : Fragment() {

    private var _binding: FragmentUnusualActionListBinding? = null
    private val binding: FragmentUnusualActionListBinding get() = _binding!!

    private val type: Int by lazy { arguments?.getInt(ARG_TYPE) ?: 0 }

    private lateinit var adapter: GroupedStockResultAdapter
    private var refreshJob: Job? = null
    private var hiddenClsWebView: CustomWebView? = null
    private var lastClsUpPoolPayloadKey: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentUnusualActionListBinding.inflate(inflater, container, false)

        binding.rv.layoutManager = LinearLayoutManager(context)
        adapter = GroupedStockResultAdapter(
            object : GroupedStockResultAdapter.Host {
                override val context = requireContext()
                override val lifecycleOwner = viewLifecycleOwner
                override val coroutineScope: CoroutineScope = viewLifecycleOwner.lifecycleScope
                override val fragmentManager = parentFragmentManager
                override fun endTimeYmdText(): String {
                    val a = activity
                    return if (a is Strategy4Activity) a.watchEndTimeYmdText() else today().toString()
                }
            }
        )
        binding.rv.adapter = adapter
        setupFab()

        if (type == TYPE_LIMIT_UP_POOL) {
            ensureHiddenClsWebView()
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            refreshWhenSelected()
        }
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        hiddenClsWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("JsonBridge")
            destroy()
        }
        hiddenClsWebView = null
        _binding = null
        super.onDestroyView()
    }

    fun refreshWhenSelected() {
        if (_binding == null) return
        if (type == TYPE_LIMIT_UP_POOL) {
            loadClsUpPoolWithHiddenWebView()
        }
        refreshJob?.cancel()
        refreshJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val items =
                    withContext(Dispatchers.IO) {
                        val endYmd =
                            (activity as? Strategy4Activity)?.watchEndTimeYmdText()?.toIntOrNull()
                                ?: today()

                        // 切到该 tab 时才触发网络更新（不再定时刷新）
                        StockRepo.getKPLLive()
                        StockRepo.getCLSLive()
                        StockRepo.getLimitUpPool(endYmd)
                        StockRepo.getLimitDownPool(endYmd)
                        buildItems()
                    }

                renderItems(items)
            }
    }

    private suspend fun renderItemsFromDb() {
        val items = withContext(Dispatchers.IO) { buildItems() }
        renderItems(items)
    }

    private fun renderItems(items: List<GroupedStockListItem>) {
        if (!isAdded || _binding == null) return
        adapter.setData(items)
        val isEmpty = items.isEmpty()
        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
        refreshFabVisibility(items.size)
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            val itemCount = adapter.itemCount
            if (itemCount <= 0) return@setOnClickListener

            val firstVisiblePos =
                (binding.rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            if (firstVisiblePos > itemCount / 2) {
                binding.rv.scrollToPosition(0)
                binding.fab.rotation = 0f
            } else {
                binding.rv.scrollToPosition(itemCount - 1)
                binding.fab.rotation = 180f
            }
        }

        binding.rv.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    refreshFabRotation()
                }
            }
        )
    }

    private fun refreshFabVisibility(resultSize: Int) {
        if (resultSize < FAB_VISIBLE_THRESHOLD) {
            binding.fab.visibility = View.GONE
        } else {
            binding.fab.visibility = View.VISIBLE
            refreshFabRotation()
        }
    }

    private fun refreshFabRotation() {
        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            binding.fab.rotation = 0f
            return
        }
        val firstVisiblePos =
            (binding.rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        binding.fab.rotation = if (firstVisiblePos > itemCount / 2) 180f else 0f
    }

    @SuppressLint("JavascriptInterface")
    private fun ensureHiddenClsWebView(): CustomWebView {
        hiddenClsWebView?.let { return it }
        val webView =
            CustomWebView(requireContext()).apply {
                alpha = 0f
                layoutParams =
                    ConstraintLayout.LayoutParams(1, 1).apply {
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    }
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            WebViewJsonInterceptor.inject(view)
                        }
                    }
                addJavascriptInterface(ClsJsonBridge(), "JsonBridge")
            }
        binding.root.addView(webView)
        hiddenClsWebView = webView
        return webView
    }

    private fun loadClsUpPoolWithHiddenWebView() {
        val webView = ensureHiddenClsWebView()
        WebViewJsonInterceptor.inject(webView)
        if (webView.url == CLS_FINANCE_URL) {
            webView.reload()
        } else {
            webView.loadUrl(CLS_FINANCE_URL)
        }
    }

    private inner class ClsJsonBridge {
        @JavascriptInterface
        fun onJsonBase64(url: String, b64: String) {
            if (!url.startsWith(CLS_UP_POOL_API_PREFIX)) return
            val type = runCatching {
                android.net.Uri.parse(url).getQueryParameter("type")
            }.getOrNull()
            if (type != "up_pool") return
            val json = WebViewJsonInterceptor.decodeBase64Json(b64) ?: return
            val payloadKey = "$url:${json.hashCode()}"
            if (payloadKey == lastClsUpPoolPayloadKey) return
            lastClsUpPoolPayloadKey = payloadKey

            hiddenClsWebView?.post {
                if (_binding == null) return@post
                viewLifecycleOwner.lifecycleScope.launch {
                    val inserted =
                        withContext(Dispatchers.IO) {
                            runCatching { StockRepo.saveClsUpPoolJson(json) }
                                .getOrElse { e ->
                                    Log.e(TAG, "CLS up_pool hidden WebView parse failed", e)
                                    0
                                }
                        }
                    if (inserted > 0 && _binding != null) {
                        renderItemsFromDb()
                    }
                }
            }
        }
    }

    private suspend fun buildItems(): List<GroupedStockListItem> {
        // 只看今天的数据；没有则显示“暂无异动”
        val endYmd = today()
        val (startSec, endSec) =
            com.liaobusi.stockman.db.unusualActionHistoryEpochRange(endYmd, 0)
        val dao = Injector.appDatabase.unusualActionHistoryDao()
        val histories =
            when (type) {
                0 -> dao.getHistories(startSec, endSec).filter { it.type in 1..4 }
                else -> dao.getHistories(startSec, endSec, type)
            }
        if (histories.isEmpty()) return emptyList()

        val followMap =
            Injector.appDatabase.followDao()
                .getFollows()
                .associateBy { it.code }

        // 注意：这里不能用 Injector.getSnapshot()，它依赖 Strategy/刷新链路；
        // “异动列表”场景下经常为空，导致股票行不显示。直接从 Room 拿 stock。
        val allCodes =
            histories.flatMap { it.stocks.split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val stockList =
            if (allCodes.isEmpty()) emptyList()
            else Injector.appDatabase.stockDao().getStockByCodes(allCodes)
        val stockMap = stockList.associateBy { it.code }

        // 保留原来的展示逻辑：如果没有拿到 enriched，就至少能显示基础 stock 信息。
        // （不再循环刷；只在 tab 进入时刷一次）
        val enrichedMap: Map<String, StockResult> =
            if (stockList.isNotEmpty()) {
                StockRepo.strategy4(
                    startMarketTime = 0,
                    endMarketTime = 99999999,
                    lowMarketValue = 0.0,
                    highMarketValue = Double.MAX_VALUE,
                    endTime = today(),
                    allowBelowCount = Int.MAX_VALUE,
                    divergeRate = 1.0,
                    bkList = null,
                    stockList = stockList,
                ).stockResults
                    .filter { !it.isGroupHeader }
                    .associateBy { it.stock.code }
            } else {
                emptyMap()
            }

        val r = mutableListOf<GroupedStockListItem>()
        histories.forEachIndexed { index, yd ->
            r.add(
                GroupedStockListItem.Header(
                    groupColor = colors[index % colors.size],
                    ydDetails = yd,
                )
            )
            yd.stocks.split(",")
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { code ->
                    val stock = stockMap[code] ?: return@forEach
                    val base = enrichedMap[code] ?: StockResult(stock = stock)
                    r.add(
                        GroupedStockListItem.Row(
                            base.copy(
                                follow = followMap[code],
                                groupColor = android.graphics.Color.BLACK,
                                isGroupHeader = false,
                                ydDetails = null,
                            )
                        )
                    )
                }
        }
        return r
    }

    companion object {
        private const val TAG = "UnusualActionList"
        private const val ARG_TYPE = "type"
        private const val TYPE_LIMIT_UP_POOL = 5
        private const val FAB_VISIBLE_THRESHOLD = 10
        private const val CLS_FINANCE_URL = "https://www.cls.cn/finance"
        private const val CLS_UP_POOL_API_PREFIX = "https://x-quote.cls.cn/quote/index/up_down_analysis"

        fun newInstance(type: Int): UnusualActionListFragment {
            return UnusualActionListFragment().apply {
                arguments = Bundle().apply { putInt(ARG_TYPE, type) }
            }
        }
    }
}

