package com.liaobusi.stockman
import com.liaobusi.stockman5.R

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.liaobusi.stockman.Injector.sp
import com.liaobusi.stockman.Strategy4Activity.Companion.openJXQSStrategy
import com.liaobusi.stockman5.databinding.ActivityStrategy4Binding
import com.liaobusi.stockman5.databinding.FragmentDiyBkBinding
import com.liaobusi.stockman5.databinding.FragmentStockInfoBinding
import com.liaobusi.stockman5.databinding.ItemDiyBkBinding
import com.liaobusi.stockman5.databinding.ItemStockInfoBkBinding
import com.liaobusi.stockman5.databinding.LayoutPopupWindow2Binding
import com.liaobusi.stockman.db.BK
import com.liaobusi.stockman.db.DIYBk
import com.liaobusi.stockman.db.HistoryBK
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.db.color
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman.db.specialBK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.toColorInt

val colors = listOf(
    "#1565c0".toColorInt(),
    "#ad1457".toColorInt(),
    "#283593".toColorInt(),
    "#b71c1c".toColorInt(),
    "#009688".toColorInt(),
    "#795548".toColorInt(),
    "#e65100".toColorInt(),
)


/**
 * 均线强势：无 Toolbar，复盘 / 盯盘各 Fragment 自带栏；宿主仅承载 Fragment + 模式 FAB。
 */
class Strategy4Activity : AppCompatActivity() {

    companion object {

        private const val TAG_REPLAY = "strategy4_replay"
        private const val TAG_WATCH = "strategy4_watch"
        private const val STATE_MAIN_MODE = "strategy4_main_mode"

        private const val MODE_REPLAY = 0
        private const val MODE_WATCH = 1

        /** [bkCode] 为板块代码，逗号分隔，每项须以 `BK` 开头；非股票代码 */
        fun openJXQSStrategy(context: Context, bkCode: String, endTime: String) {
            val i = Intent(
                context, Strategy4Activity::class.java,
            ).apply {
                putExtra("bk", bkCode)
                putExtra("endTime", endTime)
            }
            context.startActivity(i)
        }

        /** 按股票代码逗号串打开（走自定义板块 stockCodes，不经过板块 BK 校验） */
        fun openJXQSStrategyForStockCodes(
            context: Context,
            stockCodesCsv: String,
            endTime: String,
        ) {
            val diy = DIYBk(
                code = "__inline_stock_codes__",
                name = "股票组合",
                bkCodes = "BKFake",
                dsp = "",
                stockCodes = stockCodesCsv.trim(),
            )
            openJXQSStrategy2(context, diy, endTime)
        }

        fun openJXQSStrategy2(context: Context, diyBk: DIYBk, endTime: String) {
            val i = Intent(
                context, Strategy4Activity::class.java,
            ).apply {
                putExtra("diyBk", Gson().toJson(diyBk))
                putExtra("endTime", endTime)
            }
            context.startActivity(i)
        }
    }

    private lateinit var activityBinding: ActivityStrategy4Binding

    /** [MODE_REPLAY] / [MODE_WATCH] */
    private var strategy4MainMode: Int = MODE_REPLAY

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MAIN_MODE, strategy4MainMode)
    }

    private fun replayFragmentEnsure(): Strategy4ReplayFragment {
        supportFragmentManager.findFragmentByTag(TAG_REPLAY)?.let { return it as Strategy4ReplayFragment }
        val f = Strategy4ReplayFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.strategy4FragmentContainer, f, TAG_REPLAY)
            .commitNow()
        return f
    }

    private fun watchFragmentEnsure(): Strategy4WatchFragment {
        supportFragmentManager.findFragmentByTag(TAG_WATCH)?.let { return it as Strategy4WatchFragment }
        val f = Strategy4WatchFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.strategy4FragmentContainer, f, TAG_WATCH)
            .commitNow()
        return f
    }

    private fun applyMainMode(mode: Int) {
        strategy4MainMode = mode
        val replay = replayFragmentEnsure()
        val tx = supportFragmentManager.beginTransaction()
        if (mode == MODE_REPLAY) {
            val watch = supportFragmentManager.findFragmentByTag(TAG_WATCH) as? Strategy4WatchFragment
            tx.show(replay)
            watch?.let { tx.hide(it) }
            tx.commit()
            replay.applyFabAfterPagerSwitchToReplay()
        } else {
            val watch = watchFragmentEnsure()
            tx.hide(replay)
            tx.show(watch)
            tx.commit()
        }
        updateModeSwitchFab()
    }

    /** FAB：contentDescription 为将要进入的模式（复盘 ↔ 盯盘） */
    private fun updateModeSwitchFab() {
        val fab = activityBinding.strategy4ModeFab
        val nextModeText = if (strategy4MainMode == MODE_REPLAY) {
            getString(R.string.strategy4_mode_watch)
        } else {
            getString(R.string.strategy4_mode_replay)
        }
        fab.contentDescription = "${getString(R.string.strategy4_fab_mode_switch)}：$nextModeText"
        fab.rotation = if (strategy4MainMode == MODE_REPLAY) 0f else 180f
    }

    private fun strategy4ReplayFragmentOrNull(): Strategy4ReplayFragment? =
        supportFragmentManager.findFragmentByTag(TAG_REPLAY) as? Strategy4ReplayFragment

    fun watchEndTimeYmdText(): String =
        strategy4ReplayFragmentOrNull()?.endTimeYmdTextForWatch().orEmpty()

    fun isStrategy4WatchPager(): Boolean = strategy4MainMode == MODE_WATCH

    /** 盯盘 Toolbar「刷新」：仍驱动隐藏中的复盘 Fragment 拉快照并选股（与原先一致） */
    fun triggerStrategy4ReplayRefresh() {
        strategy4ReplayFragmentOrNull()?.triggerToolbarRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityBinding = ActivityStrategy4Binding.inflate(layoutInflater)
        setContentView(activityBinding.root)

        strategy4MainMode = savedInstanceState?.getInt(STATE_MAIN_MODE, MODE_REPLAY) ?: MODE_REPLAY

        activityBinding.strategy4ModeFab.setOnClickListener {
            applyMainMode(if (strategy4MainMode == MODE_REPLAY) MODE_WATCH else MODE_REPLAY)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (strategy4MainMode == MODE_WATCH) {
                    applyMainMode(MODE_REPLAY)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        applyMainMode(strategy4MainMode)
    }
}


class StockInfoFragment(private val stock: Stock, private val date: String) : DialogFragment() {

    private lateinit var binding: FragmentStockInfoBinding

    private val resultList = mutableListOf<String>()

    override fun onStart() {
        super.onStart()
        val dm = resources.displayMetrics
        dialog?.window?.setLayout(
            (dm.widthPixels * 0.92f).toInt(),
            LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = FragmentStockInfoBinding.inflate(inflater)
        binding.stockHeaderStock.text = "${stock.code}-${stock.name}"

        binding.cancelBtn.setOnClickListener { dismiss() }

        binding.jumpBtn.setOnClickListener {
            if (resultList.isEmpty()) {
                Toast.makeText(requireContext(), "请至少勾选一个板块", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Strategy4Activity.openJXQSStrategy(requireContext(), resultList.joinToString(","), date)
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows: List<Pair<BK, HistoryBK>> = withContext(Dispatchers.IO) {
                val dateInt = date.toIntOrNull() ?: return@withContext emptyList()
                val bkDao = Injector.appDatabase.bkDao()
                val histDao = Injector.appDatabase.historyBKDao()
                stock.bk.split(',')
                    .mapNotNull { code ->
                        val bk = bkDao.getBKByCode(code) ?: return@mapNotNull null
                        if (bk.specialBK) return@mapNotNull null
                        val h = histDao.getHistoryByDate3(code, dateInt) ?: return@mapNotNull null
                        Triple(bk, h, h.chg)
                    }
                    .sortedByDescending { it.third }
                    .map { Pair(it.first, it.second) }
            }

            if (!isAdded) return@launch

            binding.bkLL.removeAllViews()
            for ((bk, historyBK) in rows) {
                val itemBinding = ItemStockInfoBkBinding.inflate(inflater, binding.bkLL, false)
                itemBinding.bkCodeName.text = "${bk.code}-${bk.name}"
                itemBinding.chgTv.text = historyBK.chg.toString()
                itemBinding.chgTv.setTextColor(historyBK.color)
                itemBinding.bkCodeName.setOnClickListener {
                    bk.openWeb(requireContext())
                }
                itemBinding.bkCodeName.setOnLongClickListener {
                    DIYBKDialogFragment(bk).show(parentFragmentManager, "diy_bk")
                    true
                }
                itemBinding.bkCb.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (bk.code !in resultList) resultList.add(bk.code)
                    } else {
                        resultList.remove(bk.code)
                    }
                }
                itemBinding.bkCb.isChecked = bk.code in resultList
                binding.bkLL.addView(itemBinding.root)
            }
        }

        return binding.root
    }
}


class DIYBKDialogFragment2(
    private val stock: Stock, private val endTime: String = today().toString(),
) : DialogFragment() {

    private lateinit var binding: FragmentDiyBkBinding

    override fun onStart() {
        super.onStart()
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.94f).toInt()
        val h = (dm.heightPixels * 0.93f).toInt()
        dialog?.window?.setLayout(w, h)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View? {
        binding = FragmentDiyBkBinding.inflate(inflater)
        binding.rv.layoutManager = LinearLayoutManager(binding.rv.context)

        lifecycleScope.launch(Dispatchers.IO) {
            val list = Injector.appDatabase.diyBkDao().getDIYBks()
            launch(Dispatchers.Main) {
                binding.rv.adapter = DIYBKAdapter2(
                    list.map {
                        SelectableItem(it, it.stockCodes.contains(stock.code, true))
                    }.toMutableList(),
                )
                binding.rv.post { binding.rv.requestLayout() }
            }
        }

        binding.cancelBtn.setOnClickListener {
            dismiss()
        }

        binding.okBtn.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                (binding.rv.adapter as DIYBKAdapter2).save(stock)
                dismiss()
            }
        }

        binding.bkCodeName.text = stock.code + "-" + stock.name

        binding.createBkBtn.setOnClickListener {
            val name = binding.diyBkName.editableText.toString()
            if (name.isEmpty()) {
                Toast.makeText(this.context, "请输入板块名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val codes = stock.code
            val code = sp.getInt("diy_bk_code", 10000) + 1
            val dsp = stock.code + "(${stock.name})"
            binding.diyBkName.setText("")
            val item = DIYBk("BK$code", name, "", dsp, codes)

            lifecycleScope.launch(Dispatchers.IO) {
                Injector.appDatabase.diyBkDao().insert(item)
                Injector.sp.edit().putInt("diy_bk_code", code).apply()
                launch(Dispatchers.Main) {
                    (binding.rv.adapter as DIYBKAdapter2).add(SelectableItem(item, true))
                }
            }
        }

        return binding.root
    }


    inner class DIYBKAdapter2(private val list: MutableList<SelectableItem<DIYBk>>) :
        RecyclerView.Adapter<DIYBKAdapter2.VH>() {

        fun add(item: SelectableItem<DIYBk>) {
            list.add(item)
            notifyItemInserted(list.size - 1)
        }


        fun save(stock: Stock) {
            list.forEach {
                if (it.selected) {
                    if (!it.data.stockCodes.contains(stock.code)) {
                        val newStockCodes = it.data.stockCodes + ",${stock.code}"
                        val newDsp = it.data.dsp + ",${stock.code}(${stock.name})"
                        val newBean = it.data.copy(
                            stockCodes = newStockCodes.removePrefix(","),
                            dsp = newDsp.removePrefix(","),
                        )
                        Injector.appDatabase.diyBkDao().insert(newBean)
                    }
                } else {
                    if (it.data.stockCodes.contains(stock.code)) {
                        val l = it.data.stockCodes.split(",").toMutableList()
                        val newCodes = kotlin.text.StringBuilder()
                        l.filter { it != stock.code }.forEach {
                            newCodes.append(it).append(",")
                        }
                        val newDsp = kotlin.text.StringBuilder()
                        val dspList = it.data.dsp.split(",").toMutableList()
                        dspList.filter { !it.contains(stock.code) }.forEach {
                            newDsp.append(it).append(",")
                        }
                        val newBean = it.data.copy(
                            stockCodes = newCodes.removeSuffix(",").toString(),
                            dsp = newDsp.removeSuffix(",").toString(),
                        )
                        Injector.appDatabase.diyBkDao().insert(newBean)
                    }
                }
            }
        }

        inner class VH(private val itemBinding: ItemDiyBkBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {
            @SuppressLint("ClickableViewAccessibility")
            fun bind(item: SelectableItem<DIYBk>, position: Int) {
                itemBinding.apply {
                    cb.setOnCheckedChangeListener(null)
                    codeNameTv.text = item.data.code + "-" + item.data.name
                    this.root.setOnClickListener {
                        Strategy4Activity.openJXQSStrategy2(
                            context!!, item.data, this@DIYBKDialogFragment2.endTime,
                        )
                    }

                    var ev: MotionEvent? = null
                    root.setOnTouchListener { view, motionEvent ->
                        if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                            ev = motionEvent
                        }
                        return@setOnTouchListener false
                    }

                    this.root.setOnLongClickListener {

                        val b = LayoutPopupWindow2Binding.inflate(LayoutInflater.from(it.context))
                        val pw = PopupWindow(
                            b.root, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true,
                        )

                        b.deleteBtn.setOnClickListener {
                            lifecycleScope.launch(Dispatchers.IO) {
                                Injector.appDatabase.diyBkDao().delete(item.data)
                                launch(Dispatchers.Main) {
                                    list.removeAt(position)
                                    notifyItemRemoved(position)
                                    Toast.makeText(
                                        context, "删除${item.data.name}板块", Toast.LENGTH_SHORT,
                                    ).show()
                                    pw.dismiss()
                                }
                            }
                        }
                        pw.showAsDropDown(it, (ev?.x ?: 0f).toInt() + 50, -150)

                        return@setOnLongClickListener true
                    }


                    cb.isChecked = item.selected
                    codes.text = item.data.dsp
                    cb.setOnCheckedChangeListener { _, isChecked ->
                        item.selected = isChecked
                    }

                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(
                ItemDiyBkBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false,
                ),
            )
        }

        override fun getItemCount(): Int {
            return list.size
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(list[position], position)
        }
    }
}
