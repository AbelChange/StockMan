package com.liaobusi.stockman
import com.liaobusi.stockman5.R

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman.db.BK
import com.liaobusi.stockman5.databinding.FragmentFollowEmbeddedBinding
import com.liaobusi.stockman5.databinding.ItemFollowPanelBinding
import com.liaobusi.stockman5.databinding.LayoutFollowPanelPopupBinding
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman.db.Follow
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.repo.StockRepo
import com.liaobusi.stockman.repo.StockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.toColorInt

class FollowPanelFragment : Fragment() {

    private var embeddedBinding: FragmentFollowEmbeddedBinding? = null

    private val recycler: RecyclerView get() = embeddedBinding!!.rv

    private val sortManualTv: TextView get() = embeddedBinding!!.sortManualBtn
    private val sortChgTv: TextView get() = embeddedBinding!!.sortChgBtn
    private val sortColorTv: TextView get() = embeddedBinding!!.sortColorBtn

    private val orderSpKey = "follow_panel_order_v1"
    private val sortSpKey = "follow_panel_sort_mode"

    private enum class FollowSortMode(val spValue: Int) {
        MANUAL(0),
        BY_CHG_DESC(1),
        BY_COLOR_THEN_CHG(2),
        ;

        companion object {
            fun fromSp(v: Int): FollowSortMode =
                FollowSortMode.entries.firstOrNull { it.spValue == v } ?: MANUAL
        }
    }

    private fun PanelRow.quoteChg(): Float = stock?.chg ?: bk!!.chg

    /**
     * 自选行数据：附带对应 Follow（含 stickyOnTop、color）；
     * [enriched] 为通过 [StockRepo.strategy4] 补齐的完整 [StockResult]（仅股票自选有效），
     * 用于嵌入式列表渲染当日行情/涨停复盘/热度/龙虎榜等丰富 UI。
     */
    data class PanelRow(
        val follow: Follow,
        val stock: Stock? = null,
        val bk: BK? = null,
        val enriched: StockResult? = null,
    ) {
        init {
            require((stock != null) xor (bk != null))
        }

        fun panelKey(): String = "${follow.type}:${follow.code}"

        fun openWeb(context: android.content.Context) {
            stock?.openWeb(context) ?: bk!!.openWeb(context)
        }

        companion object {
            fun fromStock(stock: Stock, f: Follow, enriched: StockResult? = null) =
                PanelRow(follow = f, stock = stock, enriched = enriched)

            fun fromBk(bkVal: BK, f: Follow) = PanelRow(follow = f, bk = bkVal)
        }
    }

    companion object {
        fun newInstance(): FollowPanelFragment = FollowPanelFragment()

        /**
         * 将自选股票行转为与复盘列表一致的 [GroupedStockListItem]（仅 type=1 股票）。
         * 优先使用 [PanelRow.enriched] 中由 [StockRepo.strategy4] 算出的完整结果；
         * 没有时回退到仅含 stock 的占位 [StockResult]。
         */
        fun panelStockRowsToGroupedItems(rows: List<PanelRow>): List<GroupedStockListItem> =
            rows.filter { it.stock != null }.map { pr ->
                val base = pr.enriched ?: StockResult(stock = pr.stock!!)
                GroupedStockListItem.Row(base.copy(follow = pr.follow))
            }

        fun circleBg(colorArgb: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorArgb)
                setStroke(4, Color.argb(80, 255, 255, 255))
            }
    }

    fun isShowing(): Boolean {
        return isAdded && embeddedBinding != null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        embeddedBinding = FragmentFollowEmbeddedBinding.inflate(inflater, container, false)
        recycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = GroupedStockResultAdapter(groupedStockResultAdapterHost())
        }
        embeddedBinding!!.sortManualBtn.setOnClickListener { applySortChoice(FollowSortMode.MANUAL) }
        embeddedBinding!!.sortChgBtn.setOnClickListener { applySortChoice(FollowSortMode.BY_CHG_DESC) }
        embeddedBinding!!.sortColorBtn.setOnClickListener { applySortChoice(FollowSortMode.BY_COLOR_THEN_CHG) }
        refreshSortChipUi()
        return embeddedBinding!!.root
    }

    override fun onResume() {
        super.onResume()
        refreshSortChipUi()
        if (!isHidden) loadOnce()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshSortChipUi()
            loadOnce()
        }
    }

    override fun onDestroyView() {
        embeddedBinding = null
        super.onDestroyView()
    }

    private fun groupedStockResultAdapterHost(): GroupedStockResultAdapter.Host =
        object : GroupedStockResultAdapter.Host {
            override val context: android.content.Context get() = requireContext()
            override val lifecycleOwner: LifecycleOwner get() = viewLifecycleOwner
            override val coroutineScope: CoroutineScope get() = viewLifecycleOwner.lifecycleScope
            override val fragmentManager: FragmentManager get() = parentFragmentManager
            override fun endTimeYmdText(): String {
                val act = activity
                return if (act is Strategy4Activity) act.watchEndTimeYmdText()
                else today().toString()
            }

            // FollowPanelFragment 仅负责“切到页时 loadOnce”，定时刷新交给 adapter 批量刷新
            override val enableAdapterAutoRefresh: Boolean = true
            override val enableAdapterBatchAutoRefresh: Boolean = true

            // 自选模式下显示"标色"按钮和调色盘
            override val showMarkerColorPalette: Boolean = true

            override fun onStockPinned(stock: Stock, toTop: Boolean) {
                pinFollowedStock(stock.code, toTop)
            }

            override fun onStockFollowChanged(stock: Stock) {
                // follow 变化（关注/取关/标色）后重新跑 loadFollowList，避免 strategy4 的 enriched 数据飘
                loadOnce()
            }
        }

    /** 把 code 对应的自选股移到列表顶部 / 底部并把顺序持久化到 SharedPreferences。 */
    private fun pinFollowedStock(code: String, toTop: Boolean) {
        val targetKey = keyOf(1, code)
        forceManualSort()
        val gAdapter = recycler.adapter as? GroupedStockResultAdapter
        val keys = gAdapter?.getStockList()?.map { keyOf(1, it.code) } ?: readOrder()
        val newOrder = if (toTop) {
            listOf(targetKey) + keys.filter { it != targetKey }
        } else {
            keys.filter { it != targetKey } + listOf(targetKey)
        }
        Injector.sp.edit().putString(orderSpKey, newOrder.distinct().joinToString(",")).apply()
    }

    /** 嵌入式且无板块自选时复用 [GroupedStockResultAdapter]；否则保持紧凑 [FollowAdapter]。 */
    private fun bindRecyclerAdapterForList(list: List<PanelRow>) {
        val hasBk = list.any { it.bk != null }
        if (!hasBk) {
            val items = panelStockRowsToGroupedItems(list)
            when (val a = recycler.adapter) {
                is GroupedStockResultAdapter -> a.setData(items, 1)
                else -> {
                    recycler.adapter = GroupedStockResultAdapter(groupedStockResultAdapterHost()).also {
                        it.setData(items, 1)
                    }
                }
            }
        } else {
            when (val a = recycler.adapter) {
                is FollowAdapter -> a.setData(list)
                else -> {
                    recycler.adapter = FollowAdapter(this).also { it.setData(list) }
                }
            }
        }
    }

    private fun loadOnce() {
        if (embeddedBinding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { loadFollowList() }
            if (!isAdded || embeddedBinding == null) return@launch
            bindRecyclerAdapterForList(list)
        }
    }

    fun keyOf(type: Int, code: String): String = "${type}:${code}"

    private suspend fun loadFollowList(): List<PanelRow> {
        val follows = Injector.appDatabase.followDao().getFollows()
        val stockCodes = follows.filter { it.type == 1 }.map { it.code }
        val bkCodes = follows.filter { it.type == 2 }.map { it.code }

        val stockList =
            if (stockCodes.isNotEmpty()) Injector.appDatabase.stockDao()
                .getStockByCodes(stockCodes)
            else emptyList()
        val stockMap = stockList.associateBy { it.code }

        val bkMap =
            if (bkCodes.isNotEmpty()) Injector.appDatabase.bkDao().getAllBK()
                .filter { it.code in bkCodes }
                .associateBy { it.code }
            else emptyMap()

        // 仅在嵌入式纯股票自选时调 strategy4 把当日行情/涨停复盘/热度/龙虎榜等字段补齐；
        // 用宽松到几乎不过滤的市值与"低于均线天数"参数，避免 strategy4 的过滤剔除掉用户的自选标的。
        val needEnrich = bkCodes.isEmpty() && stockList.isNotEmpty()
        val enrichedMap: Map<String, StockResult> =
            if (needEnrich) {
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

        val itemsByKey = mutableMapOf<String, PanelRow>()
        for (f in follows) {
            val pk = keyOf(f.type, f.code)
            when (f.type) {
                1 -> stockMap[f.code]?.let {
                    itemsByKey[pk] = PanelRow.fromStock(it, f, enriched = enrichedMap[f.code])
                }

                2 -> bkMap[f.code]?.let { itemsByKey[pk] = PanelRow.fromBk(it, f) }
            }
        }

        val order = readOrder()
        val result = mutableListOf<PanelRow>()
        val used = mutableSetOf<String>()
        for (k in order) {
            val row = itemsByKey[k] ?: continue
            result.add(row)
            used.add(k)
        }
        for ((k, v) in itemsByKey) {
            if (k !in used) result.add(v)
        }
        return applySortMode(result)
    }

    fun readOrder(): List<String> {
        val raw = Injector.sp.getString(orderSpKey, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.contains(":") && it.length > 3 }
    }

    fun saveOrder(list: List<PanelRow>) {
        val keys = list.map { it.panelKey() }
        Injector.sp.edit().putString(orderSpKey, keys.joinToString(",")).apply()
    }

    private fun readSortMode(): FollowSortMode =
        FollowSortMode.fromSp(Injector.sp.getInt(sortSpKey, FollowSortMode.MANUAL.spValue))

    private fun writeSortMode(mode: FollowSortMode) {
        Injector.sp.edit().putInt(sortSpKey, mode.spValue).apply()
    }

    private fun applySortChoice(mode: FollowSortMode) {
        if (readSortMode() == mode) return
        writeSortMode(mode)
        refreshSortChipUi()
        loadOnce()
    }

    private fun refreshSortChipUi() {
        if (embeddedBinding == null) return
        val ctx = embeddedBinding!!.root.context
        val idle = ContextCompat.getColor(ctx, R.color.yd_secondary)
        fun styleChip(tv: TextView, selected: Boolean) {
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_follow_sort_seg_selected else R.drawable.bg_follow_sort_seg_idle,
            )
            tv.setTextColor(if (selected) Color.WHITE else idle)
            tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        val mode = readSortMode()
        styleChip(sortManualTv, mode == FollowSortMode.MANUAL)
        styleChip(sortChgTv, mode == FollowSortMode.BY_CHG_DESC)
        styleChip(sortColorTv, mode == FollowSortMode.BY_COLOR_THEN_CHG)
    }

    private fun applySortMode(manualOrdered: List<PanelRow>): List<PanelRow> {
        return when (readSortMode()) {
            FollowSortMode.MANUAL -> manualOrdered
            FollowSortMode.BY_CHG_DESC ->
                manualOrdered.sortedWith(
                    compareByDescending<PanelRow> { it.quoteChg() }.thenBy { it.panelKey() },
                )

            FollowSortMode.BY_COLOR_THEN_CHG ->
                manualOrdered.sortedWith(
                    compareByDescending<PanelRow> { it.follow.color != 0 }
                        .thenBy { it.follow.color }
                        .thenByDescending { it.quoteChg() }
                        .thenBy { it.panelKey() },
                )
        }
    }

    private fun forceManualSort() {
        writeSortMode(FollowSortMode.MANUAL)
        refreshSortChipUi()
    }

    fun canPersistManualRowOrder(): Boolean = readSortMode() == FollowSortMode.MANUAL

    private inner class FollowAdapter(
        private val fragment: FollowPanelFragment,
    ) : RecyclerView.Adapter<FollowAdapter.VH>() {

        inner class VH(val binding: ItemFollowPanelBinding) : RecyclerView.ViewHolder(binding.root)

        private val data = mutableListOf<PanelRow>()

        fun setData(list: List<PanelRow>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
            if (fragment.canPersistManualRowOrder()) fragment.saveOrder(data.toList())
        }

        fun updateQuotes(latest: List<PanelRow>) {
            data.clear()
            data.addAll(latest)
            notifyDataSetChanged()
            if (fragment.canPersistManualRowOrder()) fragment.saveOrder(data.toList())
        }

        fun pinTop(pos: Int) {
            if (pos !in data.indices) return
            fragment.forceManualSort()
            val item = data.removeAt(pos)
            data.add(0, item)
            notifyItemMoved(pos, 0)
            fragment.saveOrder(data.toList())
        }

        fun pinBottom(pos: Int) {
            if (pos !in data.indices) return
            fragment.forceManualSort()
            val item = data.removeAt(pos)
            data.add(item)
            notifyItemMoved(pos, data.size - 1)
            fragment.saveOrder(data.toList())
        }

        fun applyMarkerColor(adapterPosition: Int, colorArgb: Int) {
            if (adapterPosition !in data.indices) return
            val row = data[adapterPosition]
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                Injector.appDatabase.followDao().insertFollow(row.follow.copy(color = colorArgb))
                val list = fragment.loadFollowList()
                withContext(Dispatchers.Main) {
                    if (!fragment.isAdded) return@withContext
                    setData(list)
                }
            }
        }

        fun applyMarkerColor(target: Follow, colorArgb: Int) {
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                Injector.appDatabase.followDao().insertFollow(target.copy(color = colorArgb))
                val list = fragment.loadFollowList()
                withContext(Dispatchers.Main) {
                    if (!fragment.isAdded) return@withContext
                    setData(list)
                }
            }
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(
                ItemFollowPanelBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
            )
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            val b = holder.binding

            val defaultStripe =
                androidx.core.content.ContextCompat.getColor(
                    fragment.requireContext(),
                    R.color.gray_400
                )

            when {
                row.stock != null -> {
                    val s = row.stock!!
                    b.name.text = s.name
                    b.chgTv.text = "${s.chg}%"
                    b.chgTv.setTextColor(Color.BLACK)
                    if (s.chg > 0) b.chgTv.setTextColor(Color.RED)
                    else if (s.chg < 0) b.chgTv.setTextColor(Color.parseColor("#ff00ad43"))
                }

                row.bk != null -> {
                    val k = row.bk!!
                    b.name.text = k.name
                    b.chgTv.text = "${k.chg}%"
                    b.chgTv.setTextColor(Color.BLACK)
                    if (k.chg > 0) b.chgTv.setTextColor(Color.RED)
                    else if (k.chg < 0) b.chgTv.setTextColor(Color.parseColor("#ff00ad43"))
                }
            }

            if (row.follow.color == 0) {
                b.colorStripe.setBackgroundColor(defaultStripe)
            } else {
                b.colorStripe.setBackgroundColor(row.follow.color)
            }

            b.root.setOnClickListener { row.openWeb(it.context) }

            var ev: MotionEvent? = null
            b.root.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_DOWN) ev = motionEvent
                false
            }

            b.root.setOnLongClickListener { anchor ->
                // 刷新线程会频繁 notifyDataSetChanged，导致 adapterPosition 抖动/变 NO_POSITION；
                // 弹窗里所有操作都用本次 bind 的 follow 锁定目标，避免“点了没反应”
                val targetFollow = row.follow
                val pop = LayoutFollowPanelPopupBinding.inflate(LayoutInflater.from(anchor.context))
                val pw = PopupWindow(
                    pop.root,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true,
                )

                // 预先测量，避免 showAsDropDown + 固定 offset 触发系统二次修正导致“跳动”
                pop.root.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                val popupW = pop.root.measuredWidth
                val popupH = pop.root.measuredHeight

                val visibleFrame = Rect()
                anchor.getWindowVisibleDisplayFrame(visibleFrame)

                val anchorLoc = IntArray(2)
                anchor.getLocationOnScreen(anchorLoc)
                val touchX = (ev?.x ?: 0f).toInt()

                var x = anchorLoc[0] + touchX
                x = x.coerceIn(
                    visibleFrame.left,
                    (visibleFrame.right - popupW).coerceAtLeast(visibleFrame.left)
                )

                val yAbove = anchorLoc[1] - popupH
                val yBelow = anchorLoc[1] + anchor.height
                val y = if (yAbove >= visibleFrame.top) yAbove else yBelow

                pop.pinTopBtn.setOnClickListener {
                    pw.dismiss()
                    val p = holder.adapterPosition
                    if (p != RecyclerView.NO_POSITION) pinTop(p)
                }

                pop.pinBottomBtn.setOnClickListener {
                    pw.dismiss()
                    val p = holder.adapterPosition
                    if (p != RecyclerView.NO_POSITION) pinBottom(p)
                }

                pop.markColorBtn.setOnClickListener {
                    pop.colorPaletteRow.visibility =
                        if (pop.colorPaletteRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    // 内容高度变化后保持锚点位置不抖动
                    pop.root.post { if (pw.isShowing) pw.update(x, y, -1, -1) }
                }

                val picks = arrayOf(
                    pop.palettePick1,
                    pop.palettePick2,
                    pop.palettePick3,
                    pop.palettePick4,
                    pop.palettePick5,
                    pop.palettePick6,
                    pop.palettePick7,
                    pop.palettePick8,
                    pop.palettePick9,
                    pop.palettePick10,
                    pop.palettePick11,
                    pop.palettePick12,
                )
                PALETTE_COLORS.forEachIndexed { ix, argb ->
                    picks[ix].background = FollowPanelFragment.circleBg(argb)
                    picks[ix].setOnClickListener {
                        pw.dismiss()
                        applyMarkerColor(targetFollow, PALETTE_COLORS[ix])
                    }
                }

                pop.unfollowBtn.setOnClickListener {
                    val p = holder.adapterPosition
                    val r = data.getOrNull(p) ?: return@setOnClickListener
                    pw.dismiss()
                    fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        Injector.appDatabase.followDao()
                            .deleteFollow(Follow(r.follow.code, r.follow.type))
                        val list = fragment.loadFollowList()
                        withContext(Dispatchers.Main) {
                            if (!fragment.isAdded) return@withContext
                            setData(list)
                        }
                    }
                }

                pw.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
                true
            }
        }

    }

}
