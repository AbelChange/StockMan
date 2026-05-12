package com.liaobusi.stockman

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.children
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman5.R
import com.liaobusi.stockman5.databinding.ItemStockBinding
import com.liaobusi.stockman5.databinding.ItemStockGroupHeaderBinding
import com.liaobusi.stockman5.databinding.LayoutStockPopupWindowBinding
import com.liaobusi.stockman.db.color
import com.liaobusi.stockman.db.expoundV
import com.liaobusi.stockman.db.groupNameV
import com.liaobusi.stockman.db.isYiZIBan
import com.liaobusi.stockman.db.reasonV
import com.liaobusi.stockman.db.source
import com.liaobusi.stockman.db.Follow
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.db.UnusualActionHistory
import com.liaobusi.stockman.db.ZTReplayBean
import com.liaobusi.stockman.db.linkedColorFromRecentRelatedFollows
import com.liaobusi.stockman.db.openDragonTigerRank
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman.repo.StockResult
import com.liaobusi.stockman.repo.toFormatText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * 分组表头 + [item_stock] 行情行的通用列表模型。
 * 用于均线复盘结果、盯盘自选等与复盘行样式一致的列表。
 */
sealed class GroupedStockListItem {
    data class Header(
        val groupColor: Int = Color.BLACK,
        val ztReplay: ZTReplayBean? = null,
        val ydDetails: UnusualActionHistory? = null,
    ) : GroupedStockListItem()

    data class Row(val result: StockResult) : GroupedStockListItem()
}

/** 十二色标记（ARGB） */
val PALETTE_COLORS: IntArray = intArrayOf(
    "#FFD32F2F".toColorInt(),
    "#FFFB8C00".toColorInt(),
    "#FFFBC02D".toColorInt(),
    "#FF1976D2".toColorInt(),
    "#FF6A1B9A".toColorInt(),
    "#FF43A047".toColorInt(),
    "#FFE91E63".toColorInt(),
    "#FF00897B".toColorInt(),
    "#FF795548".toColorInt(),
    "#FF546E7A".toColorInt(),
    "#FF3949AB".toColorInt(),
    "#FF00ACC1".toColorInt(),
)

/**
 * 分组表头 + 股票行的 RecyclerView 适配器；通过 [Host] 绑定 Activity / Fragment 上下文。
 */
class GroupedStockResultAdapter(
    private val host: Host,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Host {
        val context: Context
        val lifecycleOwner: LifecycleOwner
        val coroutineScope: CoroutineScope
        val fragmentManager: FragmentManager
        fun endTimeYmdText(): String

        /**
         * Adapter 内置的“可见行定时从 Room 重新读一遍并 notify”开关。
         * 有些页面（如 FollowPanelFragment）本身有定时刷新逻辑，需关闭避免重复刷新。
         */
        val enableAdapterAutoRefresh: Boolean get() = true

        /**
         * 新增：批量自动刷新模式。
         * - true：每次刷新“一次性查询整个列表”的股票与历史数据，然后回填更新（适合列表不大但需要减少 DB 循环查询）。
         * - false：保持旧逻辑，仅刷新可见行且逐条查询。
         */
        val enableAdapterBatchAutoRefresh: Boolean get() = false

        /** 长按菜单中是否显示"标色"按钮和调色盘（FollowPanel 自选场景）。 */
        val showMarkerColorPalette: Boolean get() = false

        /** 是否隐藏行内的 `labelTv`（某些页面不需要显示 formatText）。 */
        val hideStockRowLabel: Boolean get() = false

        /** 是否隐藏龙虎榜买卖标签条 `dtTagsLL`。 */
        val hideDragonTigerTags: Boolean get() = false

        /** "置顶 / 置底"动作完成后回调，宿主可在此持久化外部排序（如 SharedPreferences）。 */
        fun onStockPinned(stock: Stock, toTop: Boolean) {}

        /** Follow 字段变化（关注 / 取关 / 标色）后回调，宿主可在此触发整页重载等。 */
        fun onStockFollowChanged(stock: Stock) {}
    }


    private val data = mutableListOf<GroupedStockListItem>()

    private var popularitySort: Int = 0

    fun setData(items: List<GroupedStockListItem>, popularitySort: Int = 1) {
        this.data.clear()
        this.data.addAll(items)
        this.popularitySort = popularitySort
        notifyDataSetChanged()
    }


    fun getStockList(): List<Stock> {
        return data.filterIsInstance<GroupedStockListItem.Row>().map { it.result.stock }
    }


    private var job: Job? = null

    override fun getItemViewType(position: Int): Int =
        if (data[position] is GroupedStockListItem.Header) 0 else 1

    private inner class HeaderVH(
        private val binding: ItemStockGroupHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: GroupedStockListItem.Header) {
            binding.root.setOnClickListener(null)
            binding.groupHeaderTv.setOnClickListener(null)
            binding.groupHeaderTv.setTextColor(header.groupColor)
            binding.groupHeaderTv2.setTextColor(header.groupColor)
            binding.ydHeaderTv.setTextColor(header.groupColor)

            val ztReplay = header.ztReplay
            if (ztReplay != null && ztReplay.groupNameV.isNotEmpty()) {
                binding.groupHeaderTv.text = ztReplay.groupNameV
                binding.groupHeaderTv.visibility = View.VISIBLE
            } else {
                binding.groupHeaderTv.visibility = View.GONE
            }
            if (ztReplay != null && ztReplay.reasonV.length > 1) {
                binding.groupHeaderTv2.visibility = View.VISIBLE
                binding.groupHeaderTv2.text = ztReplay.reasonV
            } else {
                binding.groupHeaderTv2.visibility = View.GONE
            }

            val yd = header.ydDetails
            if (yd != null) {
                binding.ydHeaderTv.visibility = View.VISIBLE
                binding.ydHeaderTv.text =
                    "${yd.time.toDateTimeString()}\n${yd.comment}  ${yd.source}"
                binding.ydHeaderTv.setOnLongClickListener(null)
            } else {
                binding.ydHeaderTv.visibility = View.GONE
                binding.ydHeaderTv.setOnLongClickListener(null)
            }

            binding.root.setOnLongClickListener { true }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        job?.cancel()
    }

    private suspend fun refreshVisibleRowsOneByOne(recyclerView: RecyclerView) {
        val lm = recyclerView.layoutManager as LinearLayoutManager
        val firstPos = lm.findFirstVisibleItemPosition()
        val lastPos = lm.findLastVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) {
            return
        }

        if (data.isNotEmpty()) {
            for (i in firstPos until lastPos + 1) {
                val row = data.getOrNull(i) as? GroupedStockListItem.Row ?: continue
                val result = row.result
                if (result.currentDayHistory == null) {
                    continue
                }

                val s = Injector.appDatabase.stockDao().getStockByCode(result.stock.code)
                val h = Injector.appDatabase.historyStockDao()
                    .getHistoryByDate3(result.stock.code, result.currentDayHistory!!.date)
                val n =
                    if (result.nextDayHistory != null) Injector.appDatabase.historyStockDao()
                        .getHistoryByDate3(
                            result.stock.code, result.nextDayHistory!!.date
                        ) else null
                if (h.chg != result.currentDayHistory!!.chg || n?.chg != result.nextDayHistory?.chg) {
                    val changeRate =
                        if (h.chg != result.currentDayHistory!!.chg) (h.chg - result.currentDayHistory!!.chg)
                        else (if (n == null || result.nextDayHistory == null) 0f
                        else n.chg - result.nextDayHistory!!.chg)
                    data[i] = GroupedStockListItem.Row(
                        result.copy(
                            stock = s,
                            currentDayHistory = h,
                            nextDayHistory = n,
                            changeRate = changeRate
                        )
                    )
                    withContext(Dispatchers.Main) { notifyItemChanged(i) }

                }

            }
        }
    }

    private suspend fun refreshWholeListInBatch() {
        if (data.isEmpty()) return

        // 只刷新有 currentDayHistory 的行（这些行才需要用 HistoryStock 对比变动）。
        val rowIndexes = mutableListOf<Int>()
        val rowResults = mutableListOf<StockResult>()
        for (i in data.indices) {
            val row = data[i] as? GroupedStockListItem.Row ?: continue
            val r = row.result
            if (r.currentDayHistory != null) {
                rowIndexes.add(i)
                rowResults.add(r)
            }
        }
        if (rowIndexes.isEmpty()) return

        val codes = rowResults.map { it.stock.code }.distinct()

        // 批量拉取 Stock 表最新快照
        val stockList = Injector.appDatabase.stockDao().getStockByCodes(codes)
        val stockMap = stockList.associateBy { it.code }

        // 批量拉取历史：取当前/次日两个 date 的 min..max 范围，然后按 (code,date) 回填
        val dates = buildList {
            for (r in rowResults) {
                add(r.currentDayHistory!!.date)
                r.nextDayHistory?.date?.let { add(it) }
            }
        }
        val startDate = dates.minOrNull() ?: return
        val endDate = dates.maxOrNull() ?: return

        // 注意 SQLite IN 绑定上限，codes 过长要分批
        val historyList = mutableListOf<com.liaobusi.stockman.db.HistoryStock>()
        val chunkSize = 800
        for (chunk in codes.chunked(chunkSize)) {
            historyList += Injector.appDatabase.historyStockDao()
                .getHistoryByDateForCodes(start = startDate, end = endDate, codes = chunk)
        }
        val historyMap = historyList.associateBy { "${it.code}:${it.date}" }

        val changedIndexes = mutableListOf<Int>()
        for (ix in rowIndexes.indices) {
            val pos = rowIndexes[ix]
            val old = rowResults[ix]
            val code = old.stock.code

            val newStock = stockMap[code] ?: old.stock
            val curDate = old.currentDayHistory!!.date
            val newCur = historyMap["${code}:${curDate}"] ?: old.currentDayHistory

            val nextDate = old.nextDayHistory?.date
            val newNext =
                if (nextDate != null) historyMap["${code}:${nextDate}"] else null

            val changed =
                (newCur != null && newCur.chg != old.currentDayHistory!!.chg) ||
                        (newNext?.chg != old.nextDayHistory?.chg) ||
                        (newStock.chg != old.stock.chg)

            if (!changed) continue

            val changeRate =
                if (newCur != null && newCur.chg != old.currentDayHistory!!.chg) (newCur.chg - old.currentDayHistory!!.chg)
                else (if (newNext == null || old.nextDayHistory == null) 0f
                else newNext.chg - old.nextDayHistory!!.chg)

            data[pos] = GroupedStockListItem.Row(
                old.copy(
                    stock = newStock,
                    currentDayHistory = newCur,
                    nextDayHistory = newNext,
                    changeRate = changeRate
                )
            )
            changedIndexes.add(pos)
        }

        if (changedIndexes.isEmpty()) return
        // 按需求：更新整个列表（减少主线程 notify 调用次数）
        withContext(Dispatchers.Main) { notifyItemRangeChanged(0, itemCount) }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (host.enableAdapterAutoRefresh && isTradingTime()) {
            job = host.coroutineScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(800)
                    // tab 场景下 Fragment 可能被 hide()，此时 view 仍 RESUMED，但不应后台刷新
                    if (!recyclerView.isShown) {
                        continue
                    }
                    if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE || !host.lifecycleOwner.lifecycle.currentState.isAtLeast(
                            Lifecycle.State.RESUMED
                        )
                    ) {
                        continue
                    }
                    if (host.enableAdapterBatchAutoRefresh) {
                        refreshWholeListInBatch()
                    } else {
                        refreshVisibleRowsOneByOne(recyclerView)
                    }
                }
            }
        }
    }


    private inner class StockVH(val binding: ItemStockBinding) :
        RecyclerView.ViewHolder(binding.root) {

        var anim: ValueAnimator? = null

        @SuppressLint("SetTextI18n")
        @RequiresApi(Build.VERSION_CODES.O)
        fun bind(result: StockResult, position: Int) {
            binding.contentLL.visibility = View.VISIBLE
            binding.stockName.setOnClickListener(null)
            binding.stockName.isClickable = false


            val stock = result.stock
            binding.apply {
                this.root.setBackgroundColor(0xffffffff.toInt())

                if (result.follow != null) {
                    this.followMarkView.visibility = View.VISIBLE
                    val markColor = if (result.follow!!.color != 0) {
                        result.follow!!.color
                    } else {
                        ContextCompat.getColor(this.root.context, R.color.gray_400)
                    }
                    val tri = ContextCompat.getDrawable(
                        this.root.context,
                        R.drawable.triangle_r_t
                    )?.mutate()
                    if (tri != null) {
                        DrawableCompat.setTint(tri, markColor)
                        this.followMarkView.background = tri
                    }
                } else {
                    this.followMarkView.visibility = View.GONE
                }

                if (result.changeRate != 0f) {
                    colorView.visibility = View.VISIBLE
                    if (colorView.tag != result.stock.code) {
                        anim?.cancel()
                    }

                    anim = ValueAnimator.ofFloat(0f, min(abs(result.changeRate), 0.9f), 0f).apply {
                            duration = 1500
                            startDelay = 1500 - (anim?.getCurrentPlayTime() ?: 1500)
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationStart(animation: Animator) {
                                    super.onAnimationStart(animation)
                                    colorView.alpha = 1f
                                    if (result.changeRate > 0) {
                                        colorView.setBackgroundColor(STOCK_RED)
                                    } else {
                                        colorView.setBackgroundColor(STOCK_GREEN)
                                    }
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    super.onAnimationEnd(animation)
                                    colorView.alpha = 0f
                                    colorView.setBackgroundColor(Color.TRANSPARENT)
                                    result.changeRate = 0f
                                }
                            })
                            this.addUpdateListener {
                                colorView.alpha = it.animatedValue as Float
                            }
                            start()
                        }
                    colorView.tag = stock.code
                } else {
                    colorView.visibility = View.GONE
                }

                if (result.ztReplay != null && result.expandReason) {
                    this.expoundTv.visibility = View.VISIBLE
                    this.expoundTv.text =
                        "${result.ztReplay!!.time}\n${result.ztReplay!!.expoundV}"
                } else {
                    this.expoundTv.visibility = View.GONE
                }

                //一字板
                if (result.ztReplay != null && result.ztReplay!!.isYiZIBan) {
                    binding.yizibanView.visibility = View.VISIBLE
                } else {
                    binding.yizibanView.visibility = View.GONE
                }

                binding.dtTagsLL.children.toList().forEach {
                    it.visibility = View.GONE
                }
                //龙虎榜游资机构买卖标签
                if (host.hideDragonTigerTags) {
                    binding.dtTagsLL.visibility = View.GONE
                } else if (result.dargonTigerRank?.tags?.isNotEmpty() == true) {
                    binding.dtTagsLL.visibility = View.VISIBLE
                    binding.tv0.visibility = View.GONE
                    val tags = result.dargonTigerRank?.tags
                    val tagList = tags!!.split(":")

                    tagList.forEachIndexed { index, item ->
                        if (index > 6) return@forEachIndexed
                        val child = binding.dtTagsLL.getChildAt(index) as TextView
                        child.text = item
                        if (item.contains("买")) {
                            child.setTextColor(Color.RED)
                        } else if (item.contains("卖")) {
                            child.setTextColor(STOCK_GREEN)
                        }
                        child.visibility = View.VISIBLE
                    }
                } else {
                    binding.dtTagsLL.visibility = View.GONE
                }


                if (result.expandPOPReason && result.popularity != null) {
                    this.popReasonTv.visibility = View.VISIBLE
                    this.popReasonTv.text = "${result.popularity?.explain}"
                } else {
                    this.popReasonTv.visibility = View.GONE
                }

                if (isShowLianBanFlag(binding.root.context)) {
                    if (result.lianbanCount > 0) {
                        binding.lianbanCountFlagTv.setBackgroundColor(
                            Color.valueOf(
                                1f, 0f, 0f, result.lianbanCount / 15f
                            ).toArgb()
                        )
                        binding.lianbanCountFlagTv.visibility = View.VISIBLE
                        binding.lianbanCountFlagTv.text = result.lianbanCount.toString()
                    } else {
                        binding.lianbanCountFlagTv.visibility = View.INVISIBLE
                    }
                } else {
                    binding.lianbanCountFlagTv.visibility = View.INVISIBLE
                }

                this.stockName.text = stock.name
                this.stockName.setTextColor(result.groupColor)


                if (result.dargonTigerRank != null) {
                    this.dragonFlagIv.visibility = View.VISIBLE
                    this.stockName.setOnClickListener {
                        result.stock.openDragonTigerRank(host.context)
                    }
                } else {
                    this.dragonFlagIv.visibility = View.GONE
                }

                this.dragonFlagIv.setOnClickListener {
                    result.stock.openDragonTigerRank(host.context)
                }


                if (result.currentDayHistory != null) {
                    currentChg.setTextColor(result.currentDayHistory!!.color)
                    currentChg.text = result.currentDayHistory!!.chg.toString()
                    if (isShowCurrentChg(binding.root.context)) {
                        currentChg.visibility = View.VISIBLE
                    } else {
                        currentChg.visibility = View.GONE
                    }
                } else {
                    currentChg.visibility = View.GONE
                }

                if (result.nextDayHistory != null) {
                    nextDayChg.setTextColor(result.nextDayHistory!!.color)
                    nextDayChg.text = result.nextDayHistory!!.chg.toString()
                    if (isShowNextChg(binding.root.context)) {
                        nextDayChg.visibility = View.VISIBLE
                    } else {
                        nextDayChg.visibility = View.GONE
                    }

                } else {
                    nextDayChg.visibility = View.GONE
                }


                val formatText = result.toFormatText()
                if (host.hideStockRowLabel) {
                    this.labelTv.visibility = View.GONE
                } else {
                    if (formatText.isNotEmpty()) {
                        this.labelTv.visibility = View.VISIBLE
                        this.labelTv.text = formatText
                    } else {
                        this.labelTv.visibility = View.INVISIBLE
                    }
                }

                if (popularitySort != 0) {
                    if (result.popularity != null) {
                        this.activeLabelTv.visibility = View.VISIBLE
                        this.activeLabelTv.text = when (popularitySort) {
                            1 -> result.popularity!!.rank.toString()
                            2 -> result.popularity!!.thsRank.toString()
                            3 -> result.popularity!!.tgbRank.toString()
                            4 -> result.popularity!!.dzhRank.toString()
                            else -> "-1"
                        }

                        if (this.activeLabelTv.text.contains("-")) {
                            this.activeLabelTv.visibility = View.INVISIBLE
                        }
                    } else {
                        this.activeLabelTv.visibility = View.INVISIBLE
                    }
                } else {
                    if (result.activeRate > 2) {
                        this.activeLabelTv.visibility = View.VISIBLE
                        this.activeLabelTv.text = result.activeRate.toInt().toString()
                    } else {
                        this.activeLabelTv.visibility = View.INVISIBLE
                    }
                }




                this.nextDayIv.visibility =
                    if (result.nextDayZT || result.nextDayCry) View.VISIBLE else View.GONE
                if (result.nextDayCry) {
                    this.nextDayIv.setImageResource(R.drawable.ic_cry)
                }
                if (result.nextDayZT) {
                    this.nextDayIv.setImageResource(R.drawable.ic_thumb_up)
                }

                root.setOnClickListener {
                    stock.openWeb(host.context)
                }

                var ev: MotionEvent? = null
                root.setOnTouchListener { view, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                        ev = motionEvent
                    }
                    return@setOnTouchListener false
                }

                root.setOnLongClickListener {
                    val b =
                        LayoutStockPopupWindowBinding.inflate(LayoutInflater.from(it.context))

                    if (result.follow != null) {
                        b.followBtn.text = "取消关注"
                    }

                    if (result.follow?.stickyOnTop == 1) {
                        b.stickyOnTopBtn.text = "取消置顶"
                    }

                    // 标色按钮和调色盘只在宿主开启时显示
                    b.markColorBtn.visibility =
                        if (host.showMarkerColorPalette) View.VISIBLE else View.GONE
                    b.colorPaletteRow.visibility = View.GONE

                    if (result.expandReason) {
                        b.expandReasonBtn.text = "折叠涨停原因"
                    }

                    if (!result.zt) {
                        b.expandReasonBtn.visibility = View.GONE
                    } else {
                        b.expandReasonBtn.visibility = View.VISIBLE
                    }


                    if (result.popularity == null) {
                        b.expandPOPReasonBtn.visibility = View.GONE
                    } else {
                        b.expandPOPReasonBtn.visibility = View.VISIBLE
                    }

                    if (result.expandPOPReason) {
                        b.expandPOPReasonBtn.text = "折叠热度原因"
                    }


                    val pw = PopupWindow(
                        b.root, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true
                    )

                    // 标色：复用 FollowPanel 的十色板；点击 markColorBtn 展开/收起调色盘
                    if (host.showMarkerColorPalette) {
                        b.markColorBtn.setOnClickListener {
                            b.colorPaletteRow.visibility =
                                if (b.colorPaletteRow.visibility == View.VISIBLE) View.GONE
                                else View.VISIBLE
                            b.root.post {
                                if (!pw.isShowing) return@post
                                b.root.measure(
                                    View.MeasureSpec.makeMeasureSpec(
                                        0,
                                        View.MeasureSpec.UNSPECIFIED
                                    ),
                                    View.MeasureSpec.makeMeasureSpec(
                                        0,
                                        View.MeasureSpec.UNSPECIFIED
                                    ),
                                )
                                pw.update(b.root.measuredWidth, b.root.measuredHeight)
                            }
                        }
                        val picks = arrayOf(
                            b.palettePick1, b.palettePick2, b.palettePick3, b.palettePick4,
                            b.palettePick5, b.palettePick6, b.palettePick7, b.palettePick8,
                            b.palettePick9, b.palettePick10, b.palettePick11, b.palettePick12,
                        )
                        PALETTE_COLORS.forEachIndexed { ix, argb ->
                            picks[ix].background = FollowPanelFragment.circleBg(argb)
                            picks[ix].setOnClickListener {
                                pw.dismiss()
                                host.coroutineScope.launch(Dispatchers.IO) {
                                    val code = result.stock.code
                                    val current = result.follow
                                        ?: Injector.appDatabase.followDao().getFollows()
                                            .firstOrNull { it.code == code && it.type == 1 }
                                        ?: Follow(code, 1, 0, argb)
                                    val updated = current.copy(color = argb)
                                    Injector.appDatabase.followDao().insertFollow(updated)
                                    val positions = data.mapIndexedNotNull { index, item ->
                                        if (item is GroupedStockListItem.Row
                                            && item.result.stock.code == code
                                        ) index else null
                                    }
                                    positions.forEach { idx ->
                                        (data.getOrNull(idx) as? GroupedStockListItem.Row)
                                            ?.result?.follow = updated
                                    }
                                    host.coroutineScope.launch(Dispatchers.Main) {
                                        positions.forEach { notifyItemChanged(it) }
                                    }
                                    host.onStockFollowChanged(result.stock)
                                }
                            }
                        }
                    }

                    b.expandReasonBtn.setOnClickListener {
                        result.expandReason = !result.expandReason
                        data.forEachIndexed { index, item ->
                            if (item is GroupedStockListItem.Row && item.result == result) {
                                notifyItemChanged(index)
                            }
                        }
                        pw.dismiss()
                    }
                    b.joinBtn.setOnClickListener {
                        DIYBKDialogFragment2(
                            result.stock,
                            host.endTimeYmdText()
                        ).show(
                            host.fragmentManager, "diy_bk"
                        )
                        pw.dismiss()
                    }

                    b.expandPOPReasonBtn.setOnClickListener {

                        result.expandPOPReason = !result.expandPOPReason
                        data.forEachIndexed { index, item ->
                            if (item is GroupedStockListItem.Row && item.result == result) {
                                notifyItemChanged(index)
                            }
                        }
                        pw.dismiss()
                    }

                    b.relatedConceptBtn.setOnClickListener {
                        pw.dismiss()
                        StockInfoFragment(
                            result.stock,
                            host.endTimeYmdText()
                        ).show(
                            host.fragmentManager, "stock_info"
                        )
                    }
                    b.dragonTigerRankBtn.setOnClickListener {
                        pw.dismiss()
                        result.stock.openDragonTigerRank(host.context)
                    }
                    b.ydHistoryBtn.setOnClickListener {
                        YDHistoryActivity.start(host.context, result.stock.code)
                    }

                    b.strongLinkStocksBtn.setOnClickListener {
                        pw.dismiss()
                        val endDate =
                            host.endTimeYmdText()
                                .toIntOrNull()
                        RelatedStocksActivity.start(
                            host.context,
                            result.stock.code,
                            endDateYmd = endDate,
                        )
                    }

                    b.stockFitRankingBtn.setOnClickListener {
                        pw.dismiss()
                        StockFitRankingActivity.start(
                            host.context,
                            result.stock.code,
                        )
                    }

                    //仅关注不置顶
                    b.followBtn.setOnClickListener {
                        pw.dismiss()
                        host.coroutineScope.launch(Dispatchers.IO) {
                            val code = result.stock.code
                            // 异动模式下同一只股票可能出现多次：刷新所有位置
                            val positions = data.mapIndexedNotNull { index, item ->
                                if (item is GroupedStockListItem.Row && item.result.stock.code == code) index else null
                            }
                            if (result.follow != null) {
                                Injector.appDatabase.followDao()
                                    .deleteFollow(Follow(code, 1))
                                positions.forEach { idx ->
                                    (data.getOrNull(idx) as? GroupedStockListItem.Row)?.result?.follow =
                                        null
                                }
                                host.coroutineScope.launch(Dispatchers.Main) {
                                    positions.forEach { notifyItemChanged(it) }
                                }

                            } else {
                                // 新关注时：近 60 天异动共现关联度从高到低，继承首个已自选且带标记色的关联股的 color
                                val endDateYmd =
                                    host.endTimeYmdText()
                                        .toIntOrNull() ?: today()
                                val linkedColor = linkedColorFromRecentRelatedFollows(
                                    stockCode = code,
                                    endDateYmd = endDateYmd,
                                    daysBack = 60,
                                )

                                val newFollow = Follow(
                                    code = code,
                                    type = 1,
                                    stickyOnTop = 0,
                                    color = linkedColor
                                )
                                Injector.appDatabase.followDao().insertFollow(newFollow)
                                positions.forEach { idx ->
                                    (data.getOrNull(idx) as? GroupedStockListItem.Row)?.result?.follow =
                                        newFollow
                                }
                                host.coroutineScope.launch(Dispatchers.Main) {
                                    positions.forEach { notifyItemChanged(it) }
                                }

                            }
                            host.onStockFollowChanged(result.stock)
                        }
                    }

                    b.stickyOnTopBtn.setOnClickListener {
                        pw.dismiss()
                        host.coroutineScope.launch(Dispatchers.IO) {
                            val code = result.stock.code
                            val positions = data.mapIndexedNotNull { index, item ->
                                if (item is GroupedStockListItem.Row && item.result.stock.code == code) index else null
                            }
                            val endDateYmd =
                                host.endTimeYmdText()
                                    .toIntOrNull() ?: today()
                            val wasSticky = result.follow?.stickyOnTop == 1
                            val keepColor = result.follow?.let { it.color }
                                ?: linkedColorFromRecentRelatedFollows(
                                    stockCode = code,
                                    endDateYmd = endDateYmd,
                                    daysBack = 60,
                                )
                            val newSticky = if (wasSticky) 0 else 1
                            val n = Follow(code, 1, newSticky, keepColor)
                            Injector.appDatabase.followDao().insertFollow(n)
                            positions.forEach { idx ->
                                (data.getOrNull(idx) as? GroupedStockListItem.Row)?.result?.follow =
                                    n
                            }

                            val rowItem = data.firstOrNull {
                                it is GroupedStockListItem.Row && it.result == result
                            } as? GroupedStockListItem.Row
                            val p = if (rowItem != null) data.indexOf(rowItem) else -1
                            // 给宿主一次持久化外部排序的机会（FollowPanel 用来写 SP 顺序）；
                            // 持久化必须在适配器视觉重排之前完成，让宿主能基于"重排前"的可见顺序计算新顺序。
                            host.onStockPinned(result.stock, toTop = newSticky == 1)
                            host.coroutineScope.launch(Dispatchers.Main) {
                                if (rowItem == null || p < 0) return@launch
                                if (wasSticky) {
                                    data.removeAt(p)
                                    notifyItemRemoved(p)
                                    delay(300)
                                    data.add(itemCount - 1, rowItem)
                                    notifyItemInserted(itemCount - 1)
                                } else {
                                    data.removeAt(p)
                                    notifyItemRemoved(p)
                                    delay(300)
                                    data.add(0, rowItem)
                                    notifyItemInserted(0)
                                }
                            }
                        }
                    }

                    b.stickyOnBottomBtn.setOnClickListener {
                        pw.dismiss()
                        host.coroutineScope.launch(Dispatchers.IO) {
                            val code = result.stock.code
                            val positions = data.mapIndexedNotNull { index, item ->
                                if (item is GroupedStockListItem.Row && item.result.stock.code == code) index else null
                            }
                            val endDateYmd =
                                host.endTimeYmdText()
                                    .toIntOrNull() ?: today()
                            val keepColor = result.follow?.let { it.color }
                                ?: linkedColorFromRecentRelatedFollows(
                                    stockCode = code,
                                    endDateYmd = endDateYmd,
                                    daysBack = 60,
                                )
                            val n = Follow(code, 1, 0, keepColor)
                            Injector.appDatabase.followDao().insertFollow(n)
                            positions.forEach { idx ->
                                (data.getOrNull(idx) as? GroupedStockListItem.Row)?.result?.follow =
                                    n
                            }
                            val rowItem = data.firstOrNull {
                                it is GroupedStockListItem.Row && it.result == result
                            } as? GroupedStockListItem.Row
                            val p = if (rowItem != null) data.indexOf(rowItem) else -1
                            host.coroutineScope.launch(Dispatchers.Main) {
                                host.onStockPinned(result.stock, toTop = false)
                                if (rowItem == null || p < 0) return@launch
                                data.removeAt(p)
                                notifyItemRemoved(p)
                                delay(300)
                                data.add(rowItem)
                                notifyItemInserted(data.size - 1)
                            }
                        }
                    }

                    val motion = ev ?: return@setOnLongClickListener false
                    val dm = binding.root.resources.displayMetrics
                    val edgeInset = (8 * dm.density).toInt()
                    val rootW = binding.root.width
                    val rootH = binding.root.height
                    val wSpec = View.MeasureSpec.makeMeasureSpec(rootW, View.MeasureSpec.AT_MOST)
                    val hSpec = View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST)
                    b.root.measure(wSpec, hSpec)
                    val popupW = b.root.measuredWidth
                    var popupH = b.root.measuredHeight
                    if (popupH <= 0) {
                        b.root.measure(
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                    }
                    val rootLoc = IntArray(2)
                    binding.root.getLocationInWindow(rootLoc)
                    val touchRelX =
                        (motion.rawX.toInt() - rootLoc[0]).coerceIn(0, rootW)
                    var xRel = touchRelX
                    val minX = edgeInset
                    val maxX = rootW - popupW - edgeInset
                    xRel = when {
                        maxX >= minX -> xRel.coerceIn(minX, maxX)
                        else -> edgeInset.coerceAtMost((rootW - popupW).coerceAtLeast(0))
                    }
                    pw.showAtLocation(binding.root, Gravity.NO_GRAVITY, xRel, motion.y.toInt())
                    return@setOnLongClickListener true
                }

            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemStockGroupHeaderBinding.inflate(inflater, parent, false))
            else -> StockVH(ItemStockBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = data[position]) {
            is GroupedStockListItem.Header -> (holder as HeaderVH).bind(item)
            is GroupedStockListItem.Row -> (holder as StockVH).bind(item.result, position)
        }
    }


}