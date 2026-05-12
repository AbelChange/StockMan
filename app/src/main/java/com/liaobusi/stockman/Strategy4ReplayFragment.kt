package com.liaobusi.stockman
import com.liaobusi.stockman5.R

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.gson.Gson
import com.liaobusi.stockman.Injector.sp
import com.liaobusi.stockman.Strategy4Activity.Companion.openJXQSStrategy
import com.liaobusi.stockman5.databinding.FragmentStrategy4ReplayBinding
import com.liaobusi.stockman.db.DIYBk
import com.liaobusi.stockman.db.color
import com.liaobusi.stockman.db.groupNameV
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.db.ZTReplayBean
import com.liaobusi.stockman.db.isBJStockExchange
import com.liaobusi.stockman.db.isChiNext
import com.liaobusi.stockman.db.isMainBoard
import com.liaobusi.stockman.db.isST
import com.liaobusi.stockman.db.isSTARMarket
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman.repo.StockResult
import com.liaobusi.stockman.repo.Strategy4Param
import com.liaobusi.stockman.repo.StrategyResult
import com.liaobusi.stockman.repo.signalCount
import com.liaobusi.stockman.repo.toFormatText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import kotlin.math.min
import androidx.core.content.edit
import android.widget.Toast

/**
 * 均线强势 — 复盘页：表单与结果列表逻辑（原 Strategy4Activity 主体）。
 */
class Strategy4ReplayFragment : Fragment() {

    private var _binding: FragmentStrategy4ReplayBinding? = null
    private val binding get() = _binding!!

    private var diyBk: DIYBk? = null

    /** 首页是否从板块/自选入口简化参数 */
    private var fromBKStrategyActivity: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_strategy4_replay, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStrategy4ReplayBinding.bind(view)
        binding.replayToolbarRefreshBtn.setOnClickListener { triggerToolbarRefresh() }
        binding.rv.layoutManager = LinearLayoutManager(requireContext())
        binding.rv.adapter = GroupedStockResultAdapter(
            object : GroupedStockResultAdapter.Host {
                override val context: Context get() = requireContext()
                override val lifecycleOwner: LifecycleOwner get() = viewLifecycleOwner
                override val coroutineScope: CoroutineScope get() = lifecycleScope
                override val fragmentManager: FragmentManager get() = requireActivity().supportFragmentManager
                override fun endTimeYmdText(): String = binding.endTimeTv.editableText.toString()
            },
        )
        lifecycleScope.launch(Dispatchers.IO) {
            prefetchReplayMarket()
        }

        if (requireActivity().intent.hasExtra("bk")) {
            val bk = requireActivity().intent.getStringExtra("bk")?.ifEmpty { "ALL" }
            binding.conceptAndBKTv.setText(bk)
            binding.lowMarketValue.setText("0.0")
            binding.highMarketValue.setText("1000000.0")
            binding.endMarketTime.setText(today().toString())
            fromBKStrategyActivity = true
        }

        if (requireActivity().intent.hasExtra("diyBk")) {
            val s = requireActivity().intent.getStringExtra("diyBk")?.ifEmpty { "ALL" }
            diyBk = Gson().fromJson<DIYBk>(s, DIYBk::class.java)
            binding.conceptAndBKTv.setText(diyBk?.bkCodes ?: "ALL")
            binding.lowMarketValue.setText("0.0")
            binding.highMarketValue.setText("1000000.0")
            binding.endMarketTime.setText(today().toString())
            fromBKStrategyActivity = true
        }

        if (requireActivity().intent.hasExtra("endTime")) {
            binding.endTimeTv.setText(requireActivity().intent.getStringExtra("endTime"))
        } else {
            binding.endTimeTv.setText(SimpleDateFormat("yyyyMMdd").format(Date(System.currentTimeMillis())))
//            lifecycleScope.launch(Dispatchers.IO) {
//                StockRepo.dpAnalysis(today())
//            }
        }



        binding.line5Btn.setOnClickListener {
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()

            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val bkList = checkBKInput() ?: return@setOnClickListener


            val param = Strategy4Param(
                startMarketTime = 19900101,
                endMarketTime = if (fromBKStrategyActivity) today() else today(),
                lowMarketValue = if (fromBKStrategyActivity) 0.0 else 100000000.0,
                highMarketValue = if (fromBKStrategyActivity) 100000000000000.0 else 100000000000000.0,
                range = 5,
                endTime = endTime,
                averageDay = 5,
                allowBelowCount = if (!binding.strictMACb.isChecked) 5 else 0,
                divergeRate = 0.00,
                abnormalRange = 5,
                abnormalRate = 2.0,
                bkList = bkList,
                stockList = Injector.getSnapshot()
            )
            updateUI(param)
            outputResult(param)
        }

        binding.line10Btn.setOnClickListener {
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val bkList = checkBKInput() ?: return@setOnClickListener

            val param = Strategy4Param(
                startMarketTime = 19900101,
                endMarketTime = if (fromBKStrategyActivity) today() else today(),
                lowMarketValue = if (fromBKStrategyActivity) 0.0 else 100000000.0,
                highMarketValue = if (fromBKStrategyActivity) 100000000000000.0 else 100000000000000.0,
                range = 10,
                endTime = endTime,
                averageDay = 10,
                allowBelowCount = if (!binding.strictMACb.isChecked) 10 else 0,
                divergeRate = 0.00,
                abnormalRange = 10,
                abnormalRate = 2.0,
                bkList = bkList,
                stockList = Injector.getSnapshot()

            )
            updateUI(param)
            outputResult(param)
        }
        binding.line20Btn.setOnClickListener {
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val bkList = checkBKInput() ?: return@setOnClickListener
            val param = Strategy4Param(
                startMarketTime = 19910101,
                endMarketTime = if (fromBKStrategyActivity) today() else today(),
                lowMarketValue = if (fromBKStrategyActivity) 0.0 else 100000000.0,
                highMarketValue = if (fromBKStrategyActivity) 100000000000000.0 else 100000000000000.0,
                range = 20,
                endTime = endTime,
                averageDay = 20,
                allowBelowCount = if (!binding.strictMACb.isChecked) 20 else 0,
                divergeRate = 0.00,
                abnormalRange = 15,
                abnormalRate = 2.0,
                bkList = bkList,
                stockList = Injector.getSnapshot()

            )
            updateUI(param)
            outputResult(param)
        }
        binding.line30Btn.setOnClickListener {
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val bkList = checkBKInput() ?: return@setOnClickListener
            val param = Strategy4Param(
                startMarketTime = 19910101,
                endMarketTime = if (fromBKStrategyActivity) today() else today(),
                lowMarketValue = if (fromBKStrategyActivity) 0.0 else 100000000.0,
                highMarketValue = if (fromBKStrategyActivity) 100000000000000.0 else 100000000000000.0,
                range = 30,
                endTime = endTime,
                averageDay = 30,
                allowBelowCount = if (!binding.strictMACb.isChecked) 30 else 0,
                divergeRate = 0.00,
                abnormalRange = 15,
                abnormalRate = 2.0,
                bkList = bkList,
                stockList = Injector.getSnapshot()

            )
            updateUI(param)
            outputResult(param)
        }
        binding.line60Btn.setOnClickListener {
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val bkList = checkBKInput() ?: return@setOnClickListener
            val param = Strategy4Param(
                startMarketTime = 19910101,
                endMarketTime = if (fromBKStrategyActivity) today() else today(),
                lowMarketValue = if (fromBKStrategyActivity) 0.0 else 100000000.0,
                highMarketValue = if (fromBKStrategyActivity) 100000000000000.0 else 100000000000000.0,
                range = 60,
                endTime = endTime,
                averageDay = 60,
                allowBelowCount = if (!binding.strictMACb.isChecked) 60 else 0,
                divergeRate = 0.00,
                abnormalRange = 20,
                abnormalRate = 2.0,
                bkList = bkList,
                stockList = Injector.getSnapshot()

            )
            updateUI(param)
            outputResult(param)
        }
        binding.preBtn.setOnClickListener {
            val c = binding.endTimeTv.editableText.toString()
            lifecycleScope.launch(Dispatchers.IO) {
                val pre = preTradingDay(c.toInt()).toString()
                launch(Dispatchers.Main) {
                    binding.endTimeTv.setText(pre)
                    binding.chooseStockBtn.callOnClick()
                }
            }
        }
        binding.postBtn.setOnClickListener {
            val c = binding.endTimeTv.editableText.toString()
            lifecycleScope.launch(Dispatchers.IO) {
                val next = nextTradingDay(c.toInt()).toString()
                launch(Dispatchers.Main) {
                    binding.endTimeTv.setText(next)
                    binding.chooseStockBtn.callOnClick()
                }
            }
        }

        var job: Job? = null

        binding.chooseStockBtn.setOnClickListener {
            job?.cancel()
            binding.root.requestFocus()
            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null || !endTime.toString().isAfter20220101()) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val startMarketTime = binding.startMarketTime.editableText.toString().toIntOrNull()
            if (startMarketTime == null) {
                Toast.makeText(requireContext(), "上市时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val endMarketTime = binding.endMarketTime.editableText.toString().toIntOrNull()
            if (endMarketTime == null) {
                Toast.makeText(requireContext(), "上市时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val lowMarketValue = binding.lowMarketValue.editableText.toString().toDoubleOrNull()

            val highMarketValue = binding.highMarketValue.editableText.toString().toDoubleOrNull()

            if (lowMarketValue == null || highMarketValue == null || lowMarketValue > highMarketValue) {
                Toast.makeText(requireContext(), "市值不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val timeRange = binding.timeRangeTv.editableText.toString().toIntOrNull()
            if (timeRange == null) {
                Toast.makeText(requireContext(), "查找区间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!binding.strictMACb.isChecked) {
                binding.divergeRateTv.setText("0.0")
                binding.allowBelowCountTv.setText(timeRange.toString())
            }

//            else {
//                binding.divergeRateTv.setText("0.0")
//                binding.allowBelowCountTv.setText("0")
//            }

            val allowBelowCount = binding.allowBelowCountTv.editableText.toString().toIntOrNull()
            if (allowBelowCount == null) {
                Toast.makeText(requireContext(), "允许均线下方运行次数不合法", Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val averageDay = binding.averageDayTv.editableText.toString().toIntOrNull()
            if (averageDay == null) {
                Toast.makeText(requireContext(), "均线取值不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val divergeRate = binding.divergeRateTv.editableText.toString().toDoubleOrNull()
            if (divergeRate == null) {
                Toast.makeText(requireContext(), "收盘价与均线偏差率取值不合法", Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val abnormalRange = binding.abnormalRangeTv.editableText.toString().toIntOrNull()
            if (abnormalRange == null) {
                Toast.makeText(requireContext(), "异常放量查找区间不合法", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val abnormalRate = binding.abnormalRateTv.editableText.toString().toDoubleOrNull()
            if (abnormalRate == null) {
                Toast.makeText(requireContext(), "异常放量倍数不合法", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val bkList = checkBKInput() ?: return@setOnClickListener
            job = lifecycleScope.launch(Dispatchers.IO) {
                var stockList = mutableListOf<Stock>()
                if (diyBk != null && diyBk?.stockCodes?.isNotEmpty() == true) {
                    val list = Injector.appDatabase.stockDao()
                        .getStockByCodes(diyBk!!.stockCodes.split(","))
                    stockList.addAll(list)
                }

                if (Injector.getSnapshot().isNotEmpty() == true) {
                    stockList.addAll(Injector.getSnapshot())
                }


                val list = StockRepo.strategy4(
                    startMarketTime = startMarketTime,
                    endMarketTime = endMarketTime,
                    lowMarketValue = lowMarketValue * 100000000,
                    highMarketValue = highMarketValue * 100000000,
                    range = timeRange,
                    endTime = endTime,
                    allowBelowCount = allowBelowCount,
                    averageDay = averageDay,
                    divergeRate = divergeRate / 100,
                    abnormalRange = abnormalRange,
                    abnormalRate = abnormalRate,
                    bkList = bkList,
                    stockList = stockList
                )
                if (!isActive) return@launch

                list.zz2000 =
                    Injector.appDatabase.historyBKDao().getHistoryByDate3("932000", date = endTime)
                list.a500 =
                    Injector.appDatabase.historyBKDao().getHistoryByDate3("000905", date = endTime)
                output(list)
            }

        }


        binding.strictMACb.setOnCheckedChangeListener { buttonView, isChecked ->
            binding.chooseStockBtn.callOnClick()
        }

        if (Injector.getSnapshot().isNotEmpty()) {
            binding.snapshotBtn.text = "取消快照"
        } else {
            binding.snapshotBtn.text = "保存当前快照"
        }

        binding.snapshotBtn.setOnClickListener {
            if (binding.snapshotBtn.text.toString() == "保存当前快照") {
                Injector.takeSnapshot(
                    (binding.rv.adapter as? GroupedStockResultAdapter)?.getStockList() ?: listOf()
                )
                binding.snapshotBtn.text = "取消快照"
            } else {
                Injector.deleteSnapshot()
                binding.snapshotBtn.text = "保存当前快照"
            }

        }

        binding.stCb.isChecked = isShowST(requireContext())

        binding.startBtn.setOnClickListener {

            binding.root.requestFocus()

            val endTime = binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null) {
                Toast.makeText(requireContext(), "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val abnormalDay = binding.abnormalDayTv.editableText.toString().toIntOrNull()
            if (abnormalDay == null) {
                Toast.makeText(requireContext(), "统计区间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val rankCount = binding.rankCountTv.editableText.toString().toIntOrNull()
            if (rankCount == null) {
                Toast.makeText(requireContext(), "数据值不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val startMarketTime = binding.startMarketTime.editableText.toString().toIntOrNull()
            if (startMarketTime == null) {
                Toast.makeText(requireContext(), "上市时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val endMarketTime = binding.endMarketTime.editableText.toString().toIntOrNull()
            if (endMarketTime == null) {
                Toast.makeText(requireContext(), "上市时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val lowMarketValue = binding.lowMarketValue.editableText.toString().toDoubleOrNull()

            val highMarketValue = binding.highMarketValue.editableText.toString().toDoubleOrNull()

            if (lowMarketValue == null || highMarketValue == null || lowMarketValue > highMarketValue) {
                Toast.makeText(requireContext(), "市值不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val timeRange = binding.timeRangeTv.editableText.toString().toIntOrNull()
            if (timeRange == null) {
                Toast.makeText(requireContext(), "查找区间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val allowBelowCount = binding.allowBelowCountTv.editableText.toString().toIntOrNull()
            if (allowBelowCount == null) {
                Toast.makeText(requireContext(), "允许均线下方运行次数不合法", Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val averageDay = binding.averageDayTv.editableText.toString().toIntOrNull()
            if (averageDay == null) {
                Toast.makeText(requireContext(), "均线取值不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val divergeRate = binding.divergeRateTv.editableText.toString().toDoubleOrNull()
            if (divergeRate == null) {
                Toast.makeText(requireContext(), "收盘价与均线偏差率取值不合法", Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val abnormalRange = binding.abnormalRangeTv.editableText.toString().toIntOrNull()
            if (abnormalRange == null) {
                Toast.makeText(requireContext(), "异常放量查找区间不合法", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val abnormalRate = binding.abnormalRateTv.editableText.toString().toDoubleOrNull()
            if (abnormalRate == null) {
                Toast.makeText(requireContext(), "异常放量倍数不合法", Toast.LENGTH_LONG)
                    .show()
                return@setOnClickListener
            }
            val bkList = checkBKInput() ?: return@setOnClickListener

            val endDay = SimpleDateFormat("yyyyMMdd").parse(endTime.toString())
            val jobList = mutableListOf<Deferred<List<StockResult>>>()
            lifecycleScope.launch(Dispatchers.IO) {
                val map = mutableMapOf<String, StockResult>()
                for (day in 0 until abnormalDay) {
                    val time = endDay.before(day)
                    val j = async {
                        val r = StockRepo.strategy4(
                            startMarketTime = startMarketTime,
                            endMarketTime = endMarketTime,
                            lowMarketValue = lowMarketValue * 100000000,
                            highMarketValue = highMarketValue * 100000000,
                            range = timeRange,
                            endTime = time,
                            allowBelowCount = allowBelowCount,
                            averageDay = averageDay,
                            divergeRate = divergeRate / 100,
                            abnormalRange = abnormalRange,
                            abnormalRate = abnormalRate,
                            bkList = bkList,
                            stockList = Injector.getSnapshot()
                        )
                        return@async mutableListOf<StockResult>().apply {
                            addAll(r.stockResults)
                        }
                    }
                    jobList.add(j)
                }
                val totalList = jobList.awaitAll()
                totalList.flatten().forEach {
                    if (map.containsKey(it.stock.code)) {
                        val item = map[it.stock.code]
                        item!!.activeCount += it.signalCount
                    } else {
                        it.activeCount = it.signalCount
                        map[it.stock.code] = it
                    }
                }
                val l = map.values.toList()
                Collections.sort(l, kotlin.Comparator { v0, v1 ->
                    return@Comparator v1.activeCount - v0.activeCount
                })

                val ll = l.subList(0, min(l.size, rankCount))
                output(StrategyResult(ll, -1))
            }


        }

        binding.followBkCb.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val followBKs = Injector.appDatabase.bkDao().getFollowedBKS()
                    val sb = StringBuilder()
                    followBKs.forEach {
                        sb.append(it.code + ",")
                    }
                    var s = sb.dropLastWhile { it == ',' }
                    if (s.isNullOrEmpty()) {
                        s = "ALL"
                    }
                    launch(Dispatchers.Main) {
                        binding.conceptAndBKTv.setText(s.toString())
                        binding.chooseStockBtn.callOnClick()
                    }
                }
            } else {
                binding.conceptAndBKTv.setText("ALL")
                binding.chooseStockBtn.callOnClick()
            }

        }


        binding.line5Btn.callOnClick()
        binding.fab.setOnClickListener {
            val firstVisiblePos =
                (binding.rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            if (firstVisiblePos > (binding.rv.adapter as GroupedStockResultAdapter).itemCount / 2) {
                binding.rv.scrollToPosition(0)
                binding.fab.rotation = 0f
            } else {
                binding.rv.scrollToPosition((binding.rv.adapter as GroupedStockResultAdapter).itemCount - 1)
                binding.fab.rotation = 180f
                scrollUp()
            }
        }

        binding.rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val firstVisiblePos =
                    (binding.rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (firstVisiblePos > (binding.rv.adapter as GroupedStockResultAdapter).itemCount / 2) {
                    binding.fab.rotation = 180f
                } else {
                    binding.fab.rotation = 0f
                }
            }
        })

        binding.ztSourceCb.isChecked = isFPSource(requireContext())
    }

    private suspend fun prefetchReplayMarket() {
        val b = binding
        StockRepo.refreshData()
        val endTime = b.endTimeTv.editableText.toString().toIntOrNull() ?: today()
        StockRepo.getRealTimeIndexByCode("2.932000")
        StockRepo.getRealTimeIndexByCode("1.000905")
        StockRepo.getKPLLive()
        StockRepo.getCLSLive()
        StockRepo.getLimitUpPool(endTime)
        StockRepo.getLimitDownPool(endTime)
        Injector.refreshPopularityRanking()
    }

    private fun refreshFabVisibilityForReplay(resultSize: Int) {
        val host = activity as? Strategy4Activity ?: return
        if (host.isStrategy4WatchPager()) {
            binding.fab.visibility = View.GONE
            return
        }
        if (resultSize < 10) {
            binding.fab.visibility = View.GONE
        } else {
            binding.fab.visibility = View.VISIBLE
            binding.fab.rotation = 0f
        }
    }

    private fun scrollUp() {
        val layoutParams = binding.appbar.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = layoutParams.behavior as AppBarLayout.Behavior
        behavior.onNestedPreScroll(
            binding.coordinatorLayout,
            binding.appbar,
            binding.coordinatorLayout,
            0,
            3000,
            intArrayOf(0, 0),
            ViewCompat.TYPE_NON_TOUCH,
        )
    }


    private fun updateUI(param: Strategy4Param) {
        binding.apply {
            this.endTimeTv.setText(param.endTime.toString())
            this.startMarketTime.setText(param.startMarketTime.toString())
            this.endMarketTime.setText(param.endMarketTime.toString())
            this.lowMarketValue.setText((param.lowMarketValue / 100000000).toString())
            this.highMarketValue.setText((param.highMarketValue / 100000000).toString())
            this.timeRangeTv.setText(param.range.toString())
            this.allowBelowCountTv.setText(param.allowBelowCount.toString())
            this.averageDayTv.setText(param.averageDay.toString())
            this.divergeRateTv.setText((param.divergeRate * 100).toString())
            this.abnormalRangeTv.setText(param.abnormalRange.toString())
            this.abnormalRateTv.setText(param.abnormalRate.toString())
        }
    }

    private fun outputResult(strictParam: Strategy4Param) {
        lifecycleScope.launch(Dispatchers.IO) {
            var stockList = mutableListOf<Stock>()
            if (diyBk != null && diyBk?.stockCodes?.isNotEmpty() == true) {
                val list =
                    Injector.appDatabase.stockDao().getStockByCodes(diyBk!!.stockCodes.split(","))
                stockList.addAll(list)
            }

            if (strictParam.stockList?.isNotEmpty() == true) {
                stockList.addAll(strictParam.stockList)
            }


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
                stockList = stockList
            )
            list.zz2000 = Injector.appDatabase.historyBKDao()
                .getHistoryByDate3("932000", date = strictParam.endTime)
            list.a500 = Injector.appDatabase.historyBKDao()
                .getHistoryByDate3("000905", date = strictParam.endTime)
            output(list)
        }
    }

    private fun checkBKInput(): List<String>? {
        val conceptAndBK = binding.conceptAndBKTv.editableText.toString()
        if (conceptAndBK == "ALL") {
            return listOf()
        }

        val l = conceptAndBK.split(",").map {
            it.trim()
        }

        var bkInputError = false
        run run@{
            l.forEach {
                if (!it.startsWith("BK")) {
                    bkInputError = true
                }
            }
        }
        if (bkInputError) {
            Toast.makeText(requireContext(), "板块不合法,BK开头,逗号分割", Toast.LENGTH_LONG)
                .show()
            return null

        }
        return l
    }

    private fun output(strategyResult: StrategyResult) {
        val list = strategyResult.stockResults

        lifecycleScope.launch(Dispatchers.Main) {

            binding.mainBoardCb.setOnCheckedChangeListener { compoundButton, b ->
                output(strategyResult)
            }

            binding.changyebanCb.setOnCheckedChangeListener { compoundButton, b ->
                output(strategyResult)
            }

            binding.bjsCb.setOnCheckedChangeListener { compoundButton, b ->
                output(strategyResult)
            }

            binding.starCb.setOnCheckedChangeListener { compoundButton, b ->
                output(strategyResult)
            }

            binding.stCb.setOnCheckedChangeListener { buttonView, isChecked ->
                output(strategyResult)
            }


            //活跃度
            binding.activityLevelCb.setOnCheckedChangeListener { compoundButton, b ->
                if (b) {
                    binding.zfSortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                }
                output(strategyResult)
            }

            //东热
            binding.popularitySortCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activityLevelCb.isChecked = false
                    binding.zfSortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                }
                output(strategyResult)
            }


            //大智慧热度
            binding.dzhPopularitySortCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activityLevelCb.isChecked = false
                    binding.zfSortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                }
                output(strategyResult)
            }

            //淘股吧热
            binding.tgbPopularitySortCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activityLevelCb.isChecked = false
                    binding.zfSortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                }
                output(strategyResult)
            }

            //同花顺人气
            binding.thsPopularitySortCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activityLevelCb.isChecked = false
                    binding.zfSortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                }
                output(strategyResult)
            }

            //涨幅
            binding.zfSortCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activityLevelCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                }
                output(strategyResult)
            }


            //仅涨停
            binding.onlyZTCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.onlyDTCb.isChecked = false
                }
                output(strategyResult)
            }

            //仅跌停
            binding.onlyDTCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.onlyZTCb.isChecked = false
                }
                output(strategyResult)
            }

            //龙虎榜
            binding.dragonTigerCb.setOnCheckedChangeListener { buttonView, isChecked ->
                output(strategyResult)
            }


            binding.zhongjunCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.xiaopiaoCb.isChecked = false
                }
                output(strategyResult)

            }

            binding.xiaopiaoCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.zhongjunCb.isChecked = false
                }
                output(strategyResult)
            }

            binding.ztPromotionCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.groupCb.isChecked = false
                    binding.ydModeCb.isChecked = false
                }
                output(strategyResult)
            }

            binding.groupCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.ydModeCb.isChecked = false
                    binding.ztPromotionCb.isChecked = false
                }
                output(strategyResult)
            }

            binding.ydModeCb.setOnCheckedChangeListener { v, checked ->
                if (checked) {
                    binding.groupCb.isChecked = false
                    binding.ztPromotionCb.isChecked = false
                }
                output(strategyResult)
            }


            binding.cowBackCb.setOnCheckedChangeListener { compoundButton, b ->
                output(strategyResult)
            }

            binding.gdrsCb.setOnCheckedChangeListener { buttonView, isChecked ->
                output(strategyResult)
            }

            binding.noZTInRangeCb.setOnCheckedChangeListener { buttonView, isChecked ->
                output(strategyResult)
            }

            binding.ztSourceCb.setOnCheckedChangeListener { buttonView, isChecked ->
                sp.edit { putBoolean("fp_source", isChecked) }
                output(strategyResult)
            }


            var r = list
            r = mutableListOf<StockResult>().apply { addAll(r) }


            //-----------股票市场过滤------------

            if (!binding.mainBoardCb.isChecked) {
                r = r.filter { return@filter !it.stock.isMainBoard() }.toMutableList()
            }


            if (!binding.changyebanCb.isChecked) {
                r = r.filter { return@filter !it.stock.isChiNext() }.toMutableList()
            }

            if (!binding.bjsCb.isChecked) {
                r = r.filter { return@filter !it.stock.isBJStockExchange() }.toMutableList()
            }

            if (!binding.starCb.isChecked) {
                r = r.filter { return@filter !it.stock.isSTARMarket() }.toMutableList()
            }

            if (!binding.stCb.isChecked) {
                r = r.filter {
                    return@filter !it.stock.isST()
                }
            }


            //-----------股票市场过滤------------

            if (binding.onlyZTCb.isChecked) {
                r = r.filter { return@filter it.zt }
            }

            if (binding.onlyDTCb.isChecked) {
                r = r.filter { return@filter it.dt }
            }

            if (binding.dragonTigerCb.isChecked) {
                r = r.filter { return@filter it.dargonTigerRank != null }
            }




            if (binding.cowBackCb.isChecked) {
                r = r.filter { return@filter it.cowBack }
            }

            if (binding.noZTInRangeCb.isChecked) {
                r = r.filter { return@filter it.ztCountInRange == 0 }
            }

            if (binding.gdrsCb.isChecked) {
                val c = binding.gdrsCountTv.text.toString().toIntOrNull() ?: 5
                r = StockRepo.filterStockByGDRS(r, c)
            }

            if (binding.ztPromotionCb.isChecked) {
                r = r.filter { it.zt }.sortedByDescending { it.lianbanCount }
            }

            if (binding.zhongjunCb.isChecked) {
                r = r.filter { it.stock.circulationMarketValue >= 20000000000 }
            }

            if (binding.xiaopiaoCb.isChecked) {
                r = r.filter { it.stock.circulationMarketValue < 8000000000 }
            }


            if (binding.activityLevelCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }.forEach {
                        newList.addAll(it.second.sortedByDescending { it.activeRate })
                    }
                    r = newList
                } else {
                    Collections.sort(r, Comparator { v0, v1 ->
                        return@Comparator v1.activeRate.compareTo(v0.activeRate)
                    })
                }
            }

            if (binding.zfSortCb.isChecked) {

                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }.forEach {
                        newList.addAll(it.second.sortedByDescending { it.stock.chg ?: -1000f })
                    }
                    r = newList
                } else {
                    Collections.sort(r, Comparator { v0, v1 ->
                        return@Comparator v1.currentDayHistory!!.chg.compareTo(v0.currentDayHistory!!.chg)
                    })
                }


                strategyResult.ydPairs?.forEach {
                    Collections.sort(it.second, Comparator { v0, v1 ->
                        return@Comparator v1.currentDayHistory!!.chg.compareTo(
                            v0.currentDayHistory!!.chg
                        )
                    })
                }


            }

            if (binding.popularitySortCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && it.popularity!!.rank > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.rank ?: 1000 })
                        }
                    r = newList
                } else {
                    r = r.filter { it.popularity != null && it.popularity!!.rank > 0 }
                        .sortedBy { it.popularity?.rank ?: 1000 }
                }

                strategyResult.ydPairs?.forEach {
                    Collections.sort(it.second, Comparator { v0, v1 ->

                        var v0Value = 10000
                        if (v0.popularity != null && v0.popularity!!.rank > 0) {
                            v0Value = v0.popularity!!.rank
                        }

                        var v1Value = 10000
                        if (v1.popularity != null && v1.popularity!!.rank > 0) {
                            v1Value = v1.popularity!!.rank
                        }

                        return@Comparator v0Value.compareTo(
                            v1Value
                        )
                    })
                }


            }

            if (binding.thsPopularitySortCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && it.popularity!!.thsRank > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.thsRank ?: 1000 })
                        }
                    r = newList
                } else {
                    r = r.filter { it.popularity != null && it.popularity!!.thsRank > 0 }
                        .sortedBy { it.popularity?.thsRank ?: 1000 }
                }


                strategyResult.ydPairs?.forEach {
                    Collections.sort(it.second, Comparator { v0, v1 ->
                        var v0Value = 10000
                        if (v0.popularity != null && v0.popularity!!.thsRank > 0) {
                            v0Value = v0.popularity!!.thsRank
                        }

                        var v1Value = 10000
                        if (v1.popularity != null && v1.popularity!!.thsRank > 0) {
                            v1Value = v1.popularity!!.thsRank
                        }

                        return@Comparator v0Value.compareTo(
                            v1Value
                        )
                    })
                }
            }

            if (binding.tgbPopularitySortCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && it.popularity!!.tgbRank > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.tgbRank ?: 1000 })
                        }
                    r = newList
                } else {
                    r = r.filter { it.popularity != null && it.popularity!!.tgbRank > 0 }
                        .sortedBy { it.popularity?.tgbRank ?: 1000 }
                }


                strategyResult.ydPairs?.forEach {
                    Collections.sort(it.second, Comparator { v0, v1 ->
                        var v0Value = 10000
                        if (v0.popularity != null && v0.popularity!!.tgbRank > 0) {
                            v0Value = v0.popularity!!.tgbRank
                        }

                        var v1Value = 10000
                        if (v1.popularity != null && v1.popularity!!.tgbRank > 0) {
                            v1Value = v1.popularity!!.tgbRank
                        }

                        return@Comparator v0Value.compareTo(
                            v1Value
                        )
                    })
                }
            }

            if (binding.dzhPopularitySortCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.filter { it.popularity != null && it.popularity!!.dzhRank > 0 }
                        .groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedBy { it.popularity?.dzhRank ?: 1000 })
                        }
                    r = newList
                } else {
                    r = r.filter { it.popularity != null && it.popularity!!.dzhRank > 0 }
                        .sortedBy { it.popularity?.dzhRank ?: 1000 }
                }

                strategyResult.ydPairs?.forEach {

                    Collections.sort(it.second, Comparator { v0, v1 ->
                        var v0Value = 10000
                        if (v0.popularity != null && v0.popularity!!.tgbRank > 0) {
                            v0Value = v0.popularity!!.dzhRank
                        }

                        var v1Value = 10000
                        if (v1.popularity != null && v1.popularity!!.dzhRank > 0) {
                            v1Value = v1.popularity!!.dzhRank
                        }
                        return@Comparator v0Value.compareTo(
                            v1Value
                        )
                    })
                }
            }



            if (binding.ydModeCb.isChecked) {
                val rList = mutableListOf<StockResult>()
                strategyResult.ydPairs?.forEach {
                    if (it.second.isEmpty() && binding.conceptAndBKTv.editableText.toString() != "ALL") return@forEach
                    rList.add(it.first)
                    it.second.forEach {
                        it.groupColor = Color.BLACK
                    }
                    rList.addAll(it.second)
                }
                r = rList
            }


            var groupHeaderCount = 0
            if (binding.groupCb.isChecked) {
                val ll = mutableListOf<StockResult>()
                r = r.filter { it.zt }
                var i = 0
                val listPair =
                    r.groupBy { it.ztReplay?.groupNameV ?: "" }.values.toMutableList().map {
                        var h = 1
                        it.forEach {
                            if (it.lianbanCount > h) {
                                h = it.lianbanCount
                            }
                        }
                        Pair(h * 100 + it.size, it)
                    }
                Collections.sort(listPair, Comparator { v0, v1 ->
                    return@Comparator v1.first.compareTo(
                        v0.first
                    )
                })


                listPair.forEach { pair ->

                    val value = pair.second
                    Collections.sort(value, Comparator { v0, v1 ->
                        val x1 =
                            if (binding.tgbPopularitySortCb.isChecked) (100f - (v1.popularity?.tgbRank
                                ?: 100))
                            else if (binding.popularitySortCb.isChecked) (300f - (v1.popularity?.rank
                                ?: 300))
                            else if (binding.dzhPopularitySortCb.isChecked) (100f - (v1.popularity?.dzhRank
                                ?: 100))
                            else if (binding.thsPopularitySortCb.isChecked) (100f - (v1.popularity?.thsRank
                                ?: 100))
                            else if (binding.activityLevelCb.isChecked) v1.activeRate
                            else if (binding.zfSortCb.isChecked) v1.stock.chg
                            else v1.ztTimeDigitization

                        val x0 =
                            if (binding.tgbPopularitySortCb.isChecked) (100f - (v0.popularity?.tgbRank
                                ?: 100))
                            else if (binding.popularitySortCb.isChecked) (300f - (v0.popularity?.rank
                                ?: 300))
                            else if (binding.dzhPopularitySortCb.isChecked) (100f - (v0.popularity?.dzhRank
                                ?: 100))
                            else if (binding.thsPopularitySortCb.isChecked) (100f - (v0.popularity?.thsRank
                                ?: 100))
                            else if (binding.activityLevelCb.isChecked) v0.activeRate
                            else if (binding.zfSortCb.isChecked) v0.stock.chg
                            else v0.ztTimeDigitization
                        return@Comparator (v1.lianbanCount * 1000 + x1).compareTo(
                            v0.lianbanCount * 1000 + x0
                        )
                    })
                    val color = colors[i % colors.size]
                    value.forEach {
                        it.groupColor = color
                    }
                    val fakeItem = value.first()
                    ll.add(
                        StockResult(
                            isGroupHeader = true,
                            groupColor = color,
                            stock = fakeItem.stock,
                            ztReplay = fakeItem.ztReplay ?: ZTReplayBean(
                                fakeItem.currentDayHistory!!.date,
                                fakeItem.stock.code,
                                "无",
                                "未分组",
                                "无",
                                "--:--:--"
                            )
                        )
                    )
                    groupHeaderCount++
                    ll.addAll(value)
                    i++
                }

                r = ll
            }

            if (!binding.ydModeCb.isChecked && !binding.groupCb.isChecked) {
                r.forEach {
                    it.groupColor = Color.BLACK
                }
            }

            if (!binding.ydModeCb.isChecked) {
                val newList = mutableListOf<StockResult>()
                r.forEach {
                    if (it.follow?.stickyOnTop == 1) {
                        newList.add(0, it)
                    } else {
                        newList.add(it)
                    }

                }
                r = newList
            }

            val strategyResult2 = StrategyResult(r, strategyResult.total)
            val resultText = if (binding.ydModeCb.isChecked) {
                "结果"
            } else {
                val s = if (strategyResult2.total > 0 && r.isNotEmpty()) {
                    "拟合度${DecimalFormat("#.0").format((r.size - groupHeaderCount) * 100f / strategyResult2.total)}%"
                } else ""
                SpannableStringBuilder().append("结果(${r.count { it.nextDayZT && !it.isGroupHeader }}/${r.size - groupHeaderCount}) ${s} ")
                    .append("涨跌停").append(
                        "" + r.count { it.zt && !it.isGroupHeader },
                        ForegroundColorSpan(Color.RED),
                        SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE
                    ).append(
                        ":",
                        ForegroundColorSpan(Color.BLACK),
                        SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE
                    ).append(
                        "" + r.count { it.dt && !it.isGroupHeader },
                        ForegroundColorSpan(STOCK_GREEN),
                        SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE
                    )
            }




            if (strategyResult.zz2000?.chg != null) {
                binding.zz2000Tv.apply {
                    visibility = View.VISIBLE
                    text = strategyResult.zz2000!!.chg.toString()
                    setTextColor(strategyResult.zz2000!!.color)
                    setOnClickListener {
                        val s = "dfcft://stock?market=2&code=932000"
                        val uri: Uri = Uri.parse(s)
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    }
                }
            } else {
                binding.zz2000Tv.apply {
                    visibility = View.GONE
                    text = ""
                }
            }


            if (strategyResult.a500?.chg != null) {
                binding.dotTv.visibility = View.VISIBLE
                binding.a500Tv.apply {
                    visibility = View.VISIBLE
                    text = strategyResult.a500!!.chg.toString()
                    setTextColor(strategyResult.a500!!.color)
                    setOnClickListener {
                        val s = "dfcft://stock?market=1&code=000905"
                        val uri: Uri = Uri.parse(s)
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    }
                }

            } else {
                binding.dotTv.visibility = View.GONE
                binding.a500Tv.apply {
                    visibility = View.GONE
                    text = ""
                }
            }

            binding.resultCount.text = resultText

            // 将 r 中的“假表头” StockResult 显式拆分为 GroupedStockListItem.Header / Row，
            // 让适配器拿到的是有类型区分的列表，而不是混在一起的 StockResult。
            val items: List<GroupedStockListItem> = strategyResult2.stockResults.map { sr ->
                if (sr.isGroupHeader) {
                    GroupedStockListItem.Header(
                        groupColor = sr.groupColor,
                        ztReplay = sr.ztReplay,
                        ydDetails = sr.ydDetails,
                    )
                } else {
                    GroupedStockListItem.Row(sr)
                }
            }

            (binding.rv.adapter as GroupedStockResultAdapter).setData(
                items,
                if (binding.popularitySortCb.isChecked) 1 else if (binding.thsPopularitySortCb.isChecked) 2 else if (binding.tgbPopularitySortCb.isChecked) 3 else if (binding.dzhPopularitySortCb.isChecked) 4 else 0
            )

            refreshFabVisibilityForReplay(strategyResult2.stockResults.size)

        }
    }
    /** 宿主 Toolbar「刷新」拉取快照后触发的二次选股（与原版一致） */
    fun triggerToolbarRefresh() {
        if (_binding == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            val endTime =
                binding.endTimeTv.editableText.toString().toIntOrNull() ?: today()
            StockRepo.fetchZTReplay2(endTime)
            StockRepo.fetchDragonTigerRank(endTime)
            prefetchReplayMarket()
            launch(Dispatchers.Main) {
                delay(2000)
                (_binding ?: return@launch).chooseStockBtn.callOnClick()
            }
        }
    }

    fun endTimeYmdTextForWatch(): String = binding.endTimeTv.editableText.toString()

    /** ViewPager 从盯盘滑回复盘后，由宿主刷新 FAB 显隐 */
    fun applyFabAfterPagerSwitchToReplay() {
        if (_binding == null) return
        refreshFabVisibilityForReplay(
            (binding.rv.adapter as? GroupedStockResultAdapter)?.itemCount ?: 0,
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
