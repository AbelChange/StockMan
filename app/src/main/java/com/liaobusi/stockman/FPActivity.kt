package com.liaobusi.stockman
import com.liaobusi.stockman5.R

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast

import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman5.databinding.ActivityFpactivityBinding
import com.liaobusi.stockman5.databinding.ActivityHomeBinding
import com.liaobusi.stockman5.databinding.ItemBkBinding
import com.liaobusi.stockman5.databinding.ItemStockBinding
import com.liaobusi.stockman5.databinding.LayoutPopupWindow2Binding
import com.liaobusi.stockman5.databinding.LayoutPopupWindowBinding
import com.liaobusi.stockman5.databinding.LayoutStockPopupWindowBinding
import com.liaobusi.stockman.db.Follow
import com.liaobusi.stockman.db.strongLinkCodesCsvForStrategy
import com.liaobusi.stockman.db.Hide
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.db.ZTReplayBean
import com.liaobusi.stockman.db.color
import com.liaobusi.stockman.db.isBJStockExchange
import com.liaobusi.stockman.db.isChiNext
import com.liaobusi.stockman.db.isMainBoard
import com.liaobusi.stockman.db.isST
import com.liaobusi.stockman.db.isSTARMarket
import com.liaobusi.stockman.db.isYiZIBan
import com.liaobusi.stockman.db.openDragonTigerRank
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman.repo.BKResult
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman.repo.StockResult
import com.liaobusi.stockman.repo.Strategy4Param
import com.liaobusi.stockman.repo.Strategy7Param
import com.liaobusi.stockman.repo.StrategyResult
import com.liaobusi.stockman.repo.toFormatText

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

class FPActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFpactivityBinding

    private val bkHost = object : BKResultAdapter.Host {
        override val lifecycleOwner = this@FPActivity
        override val coroutineScope = lifecycleScope

        override fun isZtMode(): Boolean = binding.ztModeCb.isChecked
        override fun isZtsSort(): Boolean = binding.ztsCb.isChecked

        override fun endTimeYmdText(): String = binding.endTimeTv.text.toString()

        override fun onBkSelected(code: String) {
            selectBK(code)
        }
    }

    private val groupedStockHost = object : GroupedStockResultAdapter.Host {
        override val context = this@FPActivity
        override val lifecycleOwner = this@FPActivity
        override val coroutineScope = lifecycleScope
        override val fragmentManager = supportFragmentManager

        override fun endTimeYmdText(): String =
            binding.endTimeTv.editableText?.toString().orEmpty()

        // 复盘页本身没有额外定时刷新逻辑，交给 adapter 做可见行刷新
        override val enableAdapterAutoRefresh: Boolean = true
        override val enableAdapterBatchAutoRefresh: Boolean = false
        override val showMarkerColorPalette: Boolean = false

        // FPActivity 中隐藏 item_stock 的部分控件
        override val hideStockRowLabel: Boolean = true
        override val hideDragonTigerTags: Boolean = true
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


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.page_menu, menu)
        return true
    }

    private suspend fun refresh() {
        val endTime = binding.endTimeTv.editableText.toString().toIntOrNull() ?: today()
        StockRepo.refreshData()
        StockRepo.fetchZTReplay2(date = endTime)
        StockRepo.getExpectHot()
        Injector.refreshPopularityRanking()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refresh -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    refresh()
                    launch(Dispatchers.Main) {
                        binding.chooseStockBtn.callOnClick()
                    }
                }
                return true
            }

        }
        return super.onOptionsItemSelected(item)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFpactivityBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "复盘"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.endTimeTv.setText(SimpleDateFormat("yyyyMMdd").format(Date(System.currentTimeMillis())))

        binding.chooseStockBtn.setOnClickListener {
            binding.root.requestFocus()
            val endTime =
                binding.endTimeTv.editableText.toString().toIntOrNull()
            if (endTime == null) {
                Toast.makeText(this, "截止时间不合法", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val param = Strategy7Param(
                range = 5,
                endTime = endTime,
                averageDay = 5,
                allowBelowCount = 5,
                divergeRate = 0.0 / 100,
            )
            outputResultBK(param)
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

        binding.bksRV.layoutManager = LinearLayoutManager(this)
        binding.stockRv.layoutManager = LinearLayoutManager(this)
        binding.stockRv.adapter = GroupedStockResultAdapter(groupedStockHost)
        binding.bksRV.adapter = BKResultAdapter(bkHost)

        binding.chooseStockBtn.callOnClick()
        lifecycleScope.launch(Dispatchers.IO) {
            refresh()
        }
    }

    fun selectBK(bk: String) {
        binding.root.requestFocus()
        val endTime =
            binding.endTimeTv.editableText.toString().toIntOrNull()
        if (endTime == null) {
            Toast.makeText(this, "截止时间不合法", Toast.LENGTH_LONG).show()
            return
        }


        val param = Strategy4Param(
            startMarketTime = 19900101,
            endMarketTime = today(),
            lowMarketValue = 0.0,
            highMarketValue = 100000000000000.0,
            range = 5,
            endTime = endTime,
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
        lifecycleScope.launch(Dispatchers.IO) {
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
        val list = strategyResult.stockResults

        lifecycleScope.launch(Dispatchers.Main) {


            //活跃度
            binding.activityLevelCb.setOnCheckedChangeListener { compoundButton, b ->
                if (b) {
                    binding.zfSortCb.isChecked = false
                    binding.popularitySortCb.isChecked = false
                    binding.thsPopularitySortCb.isChecked = false
                    binding.dzhPopularitySortCb.isChecked = false
                    binding.tgbPopularitySortCb.isChecked = false
                }
                outputStock(strategyResult)
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
                outputStock(strategyResult)
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
                outputStock(strategyResult)
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
                outputStock(strategyResult)
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
                outputStock(strategyResult)
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
                outputStock(strategyResult)
            }


            binding.ztPromotionCb.setOnCheckedChangeListener { buttonView, isChecked ->
                outputStock(strategyResult)
            }


            var r = list
            r = mutableListOf<StockResult>().apply { addAll(r) }

            if (binding.activityLevelCb.isChecked) {
                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }.forEach {
                        newList.addAll(it.second.sortedByDescending { it.activeRate })
                    }
                    r = newList
                } else {
                    Collections.sort(r, kotlin.Comparator { v0, v1 ->
                        return@Comparator v1.activeRate.compareTo(v0.activeRate)
                    })
                }
            }


            if (binding.zfSortCb.isChecked) {

                if (binding.ztPromotionCb.isChecked) {
                    val newList = mutableListOf<StockResult>()
                    r.groupBy { it.lianbanCount }.toList().sortedByDescending { it.first }
                        .forEach {
                            newList.addAll(it.second.sortedByDescending { it.stock.chg })
                        }
                    r = newList
                } else {
                    Collections.sort(r, kotlin.Comparator { v0, v1 ->
                        return@Comparator v1.currentDayHistory!!.chg.compareTo(v0.currentDayHistory!!.chg)
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
            }

            (binding.stockRv.adapter as GroupedStockResultAdapter).setData(
                toGroupedItems(r),
                if (binding.popularitySortCb.isChecked) 1 else if (binding.thsPopularitySortCb.isChecked) 2 else if (binding.tgbPopularitySortCb.isChecked) 3 else if (binding.dzhPopularitySortCb.isChecked) 4 else 0,
            )
        }
    }

    var job: Job? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun outputResultBK(strictParam: Strategy7Param) {
        job?.cancel()
        job = lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.Main) {

            binding.ztModeCb.setOnCheckedChangeListener { buttonView, isChecked ->
                outputBk(list)
            }


            binding.conceptCb.setOnCheckedChangeListener { compoundButton, b ->
                outputBk(list)
            }

            binding.tradeCb.setOnCheckedChangeListener { compoundButton, b ->
                outputBk(list)
            }


            binding.expectHotCb.setOnCheckedChangeListener { compoundButton, b ->
                outputBk(list)
            }





            binding.zfBkCb.setOnCheckedChangeListener { compoundButton, b ->
                if (b) {
                    binding.activeRateBkCb.isChecked = false
                    binding.zgbCb.isChecked = false
                    binding.ztsCb.isChecked = false
                }
                outputBk(list)
            }

            binding.activeRateBkCb.setOnCheckedChangeListener { compoundButton, b ->
                if (b) {
                    binding.zfBkCb.isChecked = false
                    binding.zgbCb.isChecked = false
                    binding.ztsCb.isChecked = false
                }
                outputBk(list)
            }



            binding.zgbCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activeRateBkCb.isChecked = false
                    binding.zfBkCb.isChecked = false
                    binding.ztsCb.isChecked = false
                }
                outputBk(list)
            }

            binding.ztsCb.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    binding.activeRateBkCb.isChecked = false
                    binding.zfBkCb.isChecked = false
                    binding.zgbCb.isChecked = false
                }
                outputBk(list)
            }

            var r = list
            r = mutableListOf<BKResult>().apply { addAll(r) }


            if (binding.expectHotCb.isChecked) {
                r = r.filter { it.expectHotList?.isNotEmpty() == true }
            }

            if (!binding.conceptCb.isChecked) {
                r = r.filter { it.bk.type < 1 }
            }

            if (!binding.tradeCb.isChecked) {
                r = r.filter { it.bk.type != 0 }
            }




            if (binding.zfBkCb.isChecked) {
                Collections.sort(r, kotlin.Comparator { v0, v1 ->
                    return@Comparator (v1.currentDayHistory?.chg
                        ?: -1000f).compareTo((v0.currentDayHistory?.chg ?: -1000f))
                })
            }

            if (binding.ztsCb.isChecked) {
                Collections.sort(r, kotlin.Comparator { v0, v1 ->
                    return@Comparator v1.ztCount.compareTo(v0.ztCount)
                })
            }

            if (binding.zgbCb.isChecked) {
                Collections.sort(r, kotlin.Comparator { v0, v1 ->
                    return@Comparator v1.highestLianBanCount.compareTo(v0.highestLianBanCount)
                })
            }

            (binding.bksRV.adapter as BKResultAdapter).setData(r)
        }
    }

}