package com.liaobusi.stockman

import android.graphics.Color
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman5.databinding.ItemBkBinding
import com.liaobusi.stockman5.databinding.LayoutPopupWindowBinding
import com.liaobusi.stockman.db.Follow
import com.liaobusi.stockman.db.Hide
import com.liaobusi.stockman.db.color
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman.repo.BKResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BK（板块/概念/行业等）列表适配器：支持选中、关注/隐藏、展开摘要、以及交易时段的可见行自动刷新。
 *
 * 通过 [Host] 注入页面依赖，便于在不同 Activity/Fragment 复用。
 */
class BKResultAdapter(
    private val host: Host,
) : RecyclerView.Adapter<BKResultAdapter.VH>() {

    interface Host {
        val lifecycleOwner: LifecycleOwner
        val coroutineScope: CoroutineScope

        /** 页面上“涨停模式”或“涨停数排序”开关（影响 flagTv 展示逻辑）。 */
        fun isZtMode(): Boolean = false

        /** 页面上“涨停数排序”开关（影响 flagTv 展示逻辑）。 */
        fun isZtsSort(): Boolean = false

        /** endTime（yyyyMMdd），用于打开策略页。 */
        fun endTimeYmdText(): String

        /** 点击某个 BK 行时回调。 */
        fun onBkSelected(code: String) {}
    }

    private val data = mutableListOf<BKResult>()
    private var selectedItem: BKResult? = null
    private var job: Job? = null

    fun setData(data: List<BKResult>) {
        this.data.clear()
        this.data.addAll(data)
        notifyDataSetChanged()
        selectedItem = data.firstOrNull()
        selectedItem?.bk?.code?.let { host.onBkSelected(it) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        job?.cancel()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        job?.cancel()
        job = host.coroutineScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000)
                if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE || recyclerView.isLayoutRequested) {
                    continue
                }
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: continue
                val firstPos = lm.findFirstVisibleItemPosition()
                val lastPos = lm.findLastVisibleItemPosition()
                if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) {
                    continue
                }

                for (i in firstPos..lastPos) {
                    val result = data.getOrNull(i) ?: continue
                    if (result.currentDayHistory != null) {
                        val s = Injector.appDatabase.bkDao().getBKByCode(result.bk.code)
                        val cur = Injector.appDatabase.historyBKDao().getHistoryByDate3(
                            result.bk.code,
                            result.currentDayHistory!!.date
                        )
                        val next =
                            if (result.nextDayHistory != null) Injector.appDatabase.historyBKDao()
                                .getHistoryByDate3(
                                    result.bk.code,
                                    result.nextDayHistory!!.date
                                ) else null
                        if (cur?.chg != result.currentDayHistory!!.chg || next?.chg != result.nextDayHistory?.chg) {
                            data[i] = result.copy(
                                bk = s!!,
                                currentDayHistory = cur,
                                nextDayHistory = next
                            )
                            host.coroutineScope.launch(Dispatchers.Main) {
                                notifyItemChanged(i)
                            }
                        }
                    }
                }
            }
        }
    }

    inner class VH(
        private val itemBinding: ItemBkBinding,
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        @RequiresApi(Build.VERSION_CODES.O)
        fun bind(result: BKResult, position: Int) {
            itemBinding.apply {

                if (result.expectHotList?.isNotEmpty() == true) {
                    hotIv.visibility = View.VISIBLE
                    val sb = StringBuilder().apply {
                        result.expectHotList!!.forEach {
                            this.append("${it.summary}\n\n")
                        }
                    }
                    summary.text = sb.trimEnd().toString()
                } else {
                    hotIv.visibility = View.GONE
                    summary.text = ""
                }

                summary.visibility =
                    if (result.expandSumary && summary.text.isNotEmpty()) View.VISIBLE else View.GONE

                when {
                    result.hide -> this.root.setBackgroundColor(0xffB0E0E6.toInt())
                    result.follow -> this.root.setBackgroundColor(0x33333333)
                    selectedItem?.bk?.code == result.bk.code -> this.root.setBackgroundColor(
                        0xffffff00.toInt()
                    )

                    else -> this.root.setBackgroundColor(0xffffffff.toInt())
                }

                this.bkName.text = result.bk.name

                var ev: MotionEvent? = null
                root.setOnTouchListener { _, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                        ev = motionEvent
                    }
                    false
                }

                if (result.currentDayHistory != null) {
                    result.currentDayHistory!!.apply {
                        currentChg.setTextColor(color)
                        currentChg.text = chg.toString()
                        currentChg.visibility =
                            if (isShowCurrentChg(root.context)) View.VISIBLE else View.GONE
                    }
                }

                if (result.nextDayHistory != null) {
                    result.nextDayHistory!!.apply {
                        nextDayChg.setTextColor(color)
                        nextDayChg.text = chg.toString()
                        nextDayChg.visibility = View.VISIBLE
                    }
                } else {
                    nextDayChg.visibility = View.GONE
                }

                flagTv.visibility = View.INVISIBLE
                if (host.isZtMode()) {
                    if (result.ztCount > 0) {
                        flagTv.setBackgroundColor(
                            Color.valueOf(
                                1f, 0f, 0f, result.ztCount / 15f
                            ).toArgb()
                        )
                        flagTv.visibility = View.VISIBLE
                        flagTv.text = result.ztCount.toString()
                    }
                } else {
                    if (result.highestLianBanCount > 0) {
                        flagTv.setBackgroundColor(
                            Color.valueOf(
                                1f, 0f, 0f, result.highestLianBanCount / 15f
                            ).toArgb()
                        )
                        flagTv.visibility = View.VISIBLE
                        flagTv.text = result.highestLianBanCount.toString()
                    }
                }

                root.setOnLongClickListener {
                    val b = LayoutPopupWindowBinding.inflate(LayoutInflater.from(it.context))
                    val pw = PopupWindow(
                        b.root,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        true
                    )

                    b.apply {
                        if (result.follow) followBtn.text = "取消关注"
                        if (result.hide) hideBtn.text = "取消隐藏"
                        expandBtn.text = if (result.expandSumary) "折叠详情" else "展开详情"
                        val code = result.bk.code

                        expandBtn.setOnClickListener {
                            val index = data.indexOf(result)
                            result.expandSumary = !result.expandSumary
                            notifyItemChanged(index)
                            pw.dismiss()
                        }

                        ztrcBtn.setOnClickListener {
                            Strategy2Activity.openZTRCStrategy(
                                itemView.context,
                                code,
                                host.endTimeYmdText(),
                            )
                        }
                        ztxpBtn.setOnClickListener {
                            Strategy1Activity.openZTXPStrategy(
                                itemView.context,
                                code,
                                host.endTimeYmdText(),
                            )
                        }
                        jxqsBtn.setOnClickListener {
                            Strategy4Activity.openJXQSStrategy(
                                itemView.context,
                                code,
                                host.endTimeYmdText(),
                            )
                        }
                        dbhpBtn.setOnClickListener {
                            Strategy6Activity.openDBHPStrategy(
                                itemView.context,
                                code,
                                host.endTimeYmdText(),
                            )
                        }
                        ztqsBtn.setOnClickListener {
                            Strategy7Activity.openZTQSStrategy(
                                itemView.context,
                                code,
                                host.endTimeYmdText(),
                            )
                        }
                        dfcfBtn.setOnClickListener {
                            result.bk.openWeb(itemView.context)
                        }

                        followBtn.setOnClickListener {
                            pw.dismiss()
                            host.coroutineScope.launch(Dispatchers.IO) {
                                val p = data.indexOf(result)
                                if (result.follow) {
                                    result.follow = false
                                    Injector.appDatabase.followDao()
                                        .deleteFollow(Follow(result.bk.code, 2))
                                    host.coroutineScope.launch(Dispatchers.Main) {
                                        data.remove(result)
                                        notifyItemRemoved(p)
                                        delay(300)
                                        data.add(itemCount - 1, result)
                                        notifyItemInserted(itemCount - 1)
                                    }
                                } else {
                                    result.follow = true
                                    Injector.appDatabase.followDao()
                                        .insertFollow(Follow(result.bk.code, 2))
                                    host.coroutineScope.launch(Dispatchers.Main) {
                                        data.remove(result)
                                        notifyItemRemoved(p)
                                        delay(300)
                                        data.add(0, result)
                                        notifyItemInserted(0)
                                    }
                                }
                            }
                        }

                        hideBtn.setOnClickListener {
                            pw.dismiss()
                            host.coroutineScope.launch(Dispatchers.IO) {
                                val p = data.indexOf(result)
                                if (result.hide) {
                                    result.hide = false
                                    Injector.appDatabase.hideDao()
                                        .deleteHide(Hide(result.bk.code, 2))
                                } else {
                                    result.hide = true
                                    Injector.appDatabase.hideDao()
                                        .insertHide(Hide(result.bk.code, 2))
                                }
                                host.coroutineScope.launch(Dispatchers.Main) {
                                    notifyItemChanged(p)
                                }
                            }
                        }
                    }

                    pw.showAsDropDown(it, (ev?.x ?: 0f).toInt(), -1100)
                    true
                }

                root.setOnClickListener {
                    selectedItem?.let {
                        val i = data.indexOf(it)
                        if (i >= 0) notifyItemChanged(i)
                    }
                    selectedItem = result
                    val index = data.indexOf(selectedItem)
                    if (index >= 0) notifyItemChanged(index)
                    host.onBkSelected(result.bk.code)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemBkBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(data[position], position)
    }
}

