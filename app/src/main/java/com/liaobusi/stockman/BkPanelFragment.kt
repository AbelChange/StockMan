package com.liaobusi.stockman

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman5.databinding.FragmentBkPanelFpBinding
import com.liaobusi.stockman.repo.BKResult
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman.repo.StockResult
import com.liaobusi.stockman.repo.Strategy4Param
import com.liaobusi.stockman.repo.Strategy7Param
import com.liaobusi.stockman.repo.StrategyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 盯盘里的「板块」页：去掉 DIY Tab/ViewPager 实现，直接复用 [FPActivity] 的板块 + 个股列表逻辑。
 *
 * 说明：
 * - 这里复用同一套 UI（`activity_fpactivity.xml`）与适配器（[BKResultAdapter] / [GroupedStockResultAdapter]）
 * - 内嵌模式下隐藏 toolbar
 */
class BkPanelFragment : Fragment() {

    private var _binding: FragmentBkPanelFpBinding? = null
    private val binding: FragmentBkPanelFpBinding get() = _binding!!

    private var refreshJob: Job? = null
    private var bkJob: Job? = null

    companion object {
        private const val ARG_EMBEDDED = "strategy4_watch_embedded"
        private const val FAB_VISIBLE_THRESHOLD = 10

        fun newEmbeddedInstance(): BkPanelFragment = BkPanelFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
        }
    }

    private lateinit var bkHost: BKResultAdapter.Host
    private lateinit var groupedStockHost: GroupedStockResultAdapter.Host

    private var lastBkResults: List<BKResult> = emptyList()
    private var lastStockResult: StrategyResult? = null

    private fun bindFilterListeners() {
        // BK 过滤/排序：任意勾选变化都基于同一批已加载数据重新渲染
        val refreshBk: () -> Unit = { outputBk(lastBkResults) }
        binding.tradeCb.setOnCheckedChangeListener { _, _ -> refreshBk() }
        binding.conceptCb.setOnCheckedChangeListener { _, _ -> refreshBk() }
        binding.ztModeCb.setOnCheckedChangeListener { _, _ -> refreshBk() }

        // 这三个排序 checkbox 互斥：涨幅 / 最高板 / 涨停数
        var syncingSortChecks = false
        fun syncSortChecks(changed: View, isChecked: Boolean) {
            if (syncingSortChecks) return
            if (!isChecked) {
                // 至少保留一个选中：如果全部被取消，回退到“涨幅”
                if (!binding.zfBkCb.isChecked && !binding.zgbCb.isChecked && !binding.ztsCb.isChecked) {
                    syncingSortChecks = true
                    binding.zfBkCb.isChecked = true
                    syncingSortChecks = false
                    refreshBk()
                }
                return
            }
            syncingSortChecks = true
            when (changed.id) {
                binding.zfBkCb.id -> {
                    binding.zgbCb.isChecked = false
                    binding.ztsCb.isChecked = false
                }
                binding.zgbCb.id -> {
                    binding.zfBkCb.isChecked = false
                    binding.ztsCb.isChecked = false
                }
                binding.ztsCb.id -> {
                    binding.zfBkCb.isChecked = false
                    binding.zgbCb.isChecked = false
                }
            }
            syncingSortChecks = false
            refreshBk()
        }
        binding.zfBkCb.setOnCheckedChangeListener { v, checked -> syncSortChecks(v, checked) }
        binding.zgbCb.setOnCheckedChangeListener { v, checked -> syncSortChecks(v, checked) }
        binding.ztsCb.setOnCheckedChangeListener { v, checked -> syncSortChecks(v, checked) }

        // Stock 排序/展示：基于最近一次 strategy4 结果重渲染
        val refreshStock: () -> Unit = { lastStockResult?.let { outputStock(it) } }
        binding.ztPromotionCb.setOnCheckedChangeListener { _, _ -> refreshStock() }

        // 这三个排序 checkbox 互斥：涨幅 / 东热 / 同热
        var syncingStockSortChecks = false
        fun syncStockSortChecks(changed: View, isChecked: Boolean) {
            if (syncingStockSortChecks) return
            if (!isChecked) {
                // 至少保留一个选中：如果全部被取消，回退到“东热”
                if (!binding.zfSortCb.isChecked
                    && !binding.popularitySortCb.isChecked
                    && !binding.thsPopularitySortCb.isChecked
                ) {
                    syncingStockSortChecks = true
                    binding.popularitySortCb.isChecked = true
                    syncingStockSortChecks = false
                    refreshStock()
                }
                return
            }
            syncingStockSortChecks = true
            when (changed.id) {
                binding.zfSortCb.id -> {
                    binding.popularitySortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                }
                binding.popularitySortCb.id -> {
                    binding.zfSortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                }
                binding.thsPopularitySortCb.id -> {
                    binding.zfSortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                }
            }
            syncingStockSortChecks = false
            refreshStock()
        }
        binding.zfSortCb.setOnCheckedChangeListener { v, checked -> syncStockSortChecks(v, checked) }
        binding.popularitySortCb.setOnCheckedChangeListener { v, checked -> syncStockSortChecks(v, checked) }
        binding.thsPopularitySortCb.setOnCheckedChangeListener { v, checked -> syncStockSortChecks(v, checked) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBkPanelFpBinding.inflate(inflater, container, false)

        // 注意：viewLifecycleOwner 只有在 onCreateView 之后才可用，Host 必须在这里初始化
        bkHost = object : BKResultAdapter.Host {
            override val lifecycleOwner = this@BkPanelFragment
            override val coroutineScope = viewLifecycleOwner.lifecycleScope

            override fun isZtMode(): Boolean = _binding?.ztModeCb?.isChecked == true
            override fun isZtsSort(): Boolean = _binding?.ztsCb?.isChecked == true

            override fun endTimeYmdText(): String = today().toString()

            override fun onBkSelected(code: String) {
                selectBK(code)
            }
        }

        groupedStockHost = object : GroupedStockResultAdapter.Host {
            override val context = requireContext()
            override val lifecycleOwner = viewLifecycleOwner
            override val coroutineScope = viewLifecycleOwner.lifecycleScope
            override val fragmentManager = parentFragmentManager

            override fun endTimeYmdText(): String = today().toString()

            override val enableAdapterAutoRefresh: Boolean = true
            override val enableAdapterBatchAutoRefresh: Boolean = false

            override val hideStockRowLabel: Boolean = true
            override val hideDragonTigerTags: Boolean = true
        }

      
        binding.bksRV.layoutManager = LinearLayoutManager(requireContext())
        binding.stockRv.layoutManager = LinearLayoutManager(requireContext())
        binding.bksRV.adapter = BKResultAdapter(bkHost)
        binding.stockRv.adapter = GroupedStockResultAdapter(groupedStockHost)
        setupBkFab()
        binding.appbar.post {
            binding.appbar.setExpanded(false, false)
        }
        bindFilterListeners()

        // 板块面板中去掉 endTime / 翻页 / 选股按钮：直接按“今天”加载一次即可
        outputResultBK(
            Strategy7Param(
                range = 5,
                endTime = today(),
                averageDay = 5,
                allowBelowCount = 5,
                divergeRate = 0.0 / 100,
            )
        )
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { refresh() }

        return binding.root
    }

    override fun onDestroyView() {
        refreshJob?.cancel()
        refreshJob = null
        bkJob?.cancel()
        bkJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun toGroupedItems(list: List<StockResult>): List<GroupedStockListItem> {
        return list.map { r ->
            if (r.isGroupHeader) {
                GroupedStockListItem.Header(
                    groupColor = r.groupColor,
                    ztReplay = r.ztReplay,
                    ydDetails = r.ydDetails,
                )
            } else {
                GroupedStockListItem.Row(r)
            }
        }
    }

    private suspend fun refresh() {
        StockRepo.getRealTimeBKs()
    }

    fun selectBK(bk: String) {
        val param = Strategy4Param(
            startMarketTime = 19900101,
            endMarketTime = today(),
            lowMarketValue = 0.0,
            highMarketValue = 100000000000000.0,
            range = 5,
            endTime = today(),
            averageDay = 5,
            allowBelowCount = 5,
            divergeRate = 0.00,
            abnormalRange = 5,
            abnormalRate = 2.0,
            bkList = listOf(bk),
            stockList = Injector.getSnapshot()
        )
        outputResultStock(param)
    }

    private fun outputResultStock(strictParam: Strategy4Param) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = StockRepo.strategy4(
                startMarketTime = strictParam.startMarketTime,
                endMarketTime = strictParam.endMarketTime,
                lowMarketValue = strictParam.lowMarketValue,
                highMarketValue = strictParam.highMarketValue,
                endTime = strictParam.endTime,
                range = strictParam.range,
                allowBelowCount = strictParam.allowBelowCount,
                averageDay = strictParam.averageDay,
                divergeRate = strictParam.divergeRate,
                abnormalRate = strictParam.abnormalRate,
                abnormalRange = strictParam.abnormalRange,
                bkList = strictParam.bkList,
                stockList = strictParam.stockList
            )
            outputStock(list)
        }
    }

    private fun outputStock(strategyResult: StrategyResult) {
        lastStockResult = strategyResult
        val list = strategyResult.stockResults
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (_binding == null) return@launch
            var r = list.toMutableList()

            // 复用 FPActivity 的 ztPromotionCb（连板分组）排序逻辑
            if (binding.zfSortCb.isChecked) {
                r = if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }.forEach {
                        newList.addAll(it.second.sortedByDescending { it.stock.chg })
                    }
                    newList
                } else {
                    r.sortedByDescending { it.currentDayHistory?.chg ?: -1000f }.toMutableList()
                }
            }

            if (binding.popularitySortCb.isChecked) {
                r = if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && (it.popularity?.rank ?: 0) > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.rank ?: 1000 })
                        }
                    newList
                } else {
                    r.filter { it.popularity != null && (it.popularity?.rank ?: 0) > 0 }
                        .sortedBy { it.popularity?.rank ?: 1000 }
                        .toMutableList()
                }
            }

            if (binding.thsPopularitySortCb.isChecked) {
                r = if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && (it.popularity?.thsRank ?: 0) > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.thsRank ?: 1000 })
                        }
                    newList
                } else {
                    r.filter { it.popularity != null && (it.popularity?.thsRank ?: 0) > 0 }
                        .sortedBy { it.popularity?.thsRank ?: 1000 }
                        .toMutableList()
                }
            }

            val popularitySort =
                if (binding.popularitySortCb.isChecked) 1
                else if (binding.thsPopularitySortCb.isChecked) 2
                else 0

            (binding.stockRv.adapter as GroupedStockResultAdapter).setData(
                toGroupedItems(r),
                popularitySort,
            )
        }
    }

    private fun setupBkFab() {
        binding.fab.setOnClickListener {
            val itemCount = binding.bksRV.adapter?.itemCount ?: 0
            if (itemCount <= 0) return@setOnClickListener

            val firstVisiblePos =
                (binding.bksRV.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            if (firstVisiblePos > itemCount / 2) {
                binding.bksRV.scrollToPosition(0)
                binding.fab.rotation = 0f
            } else {
                binding.bksRV.scrollToPosition(itemCount - 1)
                binding.fab.rotation = 180f
            }
        }

        binding.bksRV.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    refreshBkFabRotation()
                }
            }
        )
    }

    private fun refreshBkFabVisibility(resultSize: Int) {
        if (resultSize < FAB_VISIBLE_THRESHOLD) {
            binding.fab.visibility = View.GONE
        } else {
            binding.fab.visibility = View.VISIBLE
            refreshBkFabRotation()
        }
    }

    private fun refreshBkFabRotation() {
        val itemCount = binding.bksRV.adapter?.itemCount ?: 0
        if (itemCount <= 0) {
            binding.fab.rotation = 0f
            return
        }
        val firstVisiblePos =
            (binding.bksRV.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        binding.fab.rotation = if (firstVisiblePos > itemCount / 2) 180f else 0f
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun outputResultBK(strictParam: Strategy7Param) {
        bkJob?.cancel()
        bkJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = StockRepo.strategy7(
                endTime = strictParam.endTime,
                range = strictParam.range,
                allowBelowCount = strictParam.allowBelowCount,
                averageDay = strictParam.averageDay,
                divergeRate = strictParam.divergeRate,
            )
            outputBk(list)
        }
    }

    private fun outputBk(list: List<BKResult>) {
        lastBkResults = list
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            if (_binding == null) return@launch
            var r = list.toMutableList()

            if (!binding.conceptCb.isChecked) {
                r = r.filter { it.bk.type < 1 }.toMutableList()
            }
            if (!binding.tradeCb.isChecked) {
                r = r.filter { it.bk.type != 0 }.toMutableList()
            }
            if (binding.zfBkCb.isChecked) {
                r.sortByDescending { it.currentDayHistory?.chg ?: -1000f }
            }
            if (binding.ztsCb.isChecked) {
                r.sortByDescending { it.ztCount }
            }
            if (binding.zgbCb.isChecked) {
                r.sortByDescending { it.highestLianBanCount }
            }

            (binding.bksRV.adapter as BKResultAdapter).setData(r)
            refreshBkFabVisibility(r.size)
        }
    }
}
