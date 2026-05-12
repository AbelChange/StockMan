package com.liaobusi.stockman

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.liaobusi.stockman.db.Stock
import com.liaobusi.stockman.db.openWeb
import com.liaobusi.stockman5.R
import com.liaobusi.stockman5.databinding.FragmentBkPageBinding
import com.liaobusi.stockman5.databinding.ItemFollowPanelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BkPageFragment : Fragment() {

    private var _binding: FragmentBkPageBinding? = null
    private val binding: FragmentBkPageBinding get() = _binding!!
    private lateinit var sortVm: BkPanelSortViewModel

    private var rawStocks: List<Stock> = emptyList()
    private var hotRankByCode: Map<String, Int> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBkPageBinding.inflate(inflater, container, false)
        sortVm = ViewModelProvider(requireParentFragment())[BkPanelSortViewModel::class.java]

        binding.rv.apply {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            adapter = StocksAdapter()
        }

        sortVm.mode.observe(viewLifecycleOwner) { applySortAndRender() }

        loadStocks()
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun loadStocks() {
        val stockCodesCsv = arguments?.getString(ARG_STOCK_CODES).orEmpty()
        val bkCodesCsv = arguments?.getString(ARG_BK_CODES).orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val stocksAndRank = withContext(Dispatchers.IO) {
                val stockCodes =
                    stockCodesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val bkCodes =
                    bkCodesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

                val directStocks =
                    if (stockCodes.isEmpty()) emptyList()
                    else Injector.appDatabase.stockDao().getStockByCodes(stockCodes)

                val bkStocks =
                    if (bkCodes.isEmpty()) emptyList()
                    else bkCodes.flatMap { code ->
                        Injector.appDatabase.bkStockDao().getStocksByBKCode(code)
                    }

                val stocks = (directStocks + bkStocks).distinctBy { it.code }

                val endDate = today()
                val rankMap = Injector.appDatabase.popularityRankDao()
                    .getRanksByDate(endDate)
                    .associate { it.code to it.rank }

                stocks to rankMap
            }

            if (!isAdded || _binding == null) return@launch
            rawStocks = stocksAndRank.first
            hotRankByCode = stocksAndRank.second
            applySortAndRender()
        }
    }

    private fun applySortAndRender() {
        if (_binding == null) return
        val mode = sortVm.mode.value ?: BkPanelSortViewModel.SortMode.CHG
        val sorted = when (mode) {
            BkPanelSortViewModel.SortMode.CHG ->
                rawStocks.sortedWith(compareByDescending<Stock> { it.chg }.thenBy { it.code })

            BkPanelSortViewModel.SortMode.HOT ->
                rawStocks.sortedWith(
                    compareBy<Stock> { hotRankByCode[it.code] ?: Int.MAX_VALUE }
                        .thenByDescending { it.chg }
                        .thenBy { it.code },
                )
        }
        (binding.rv.adapter as? StocksAdapter)?.setData(sorted)
    }

    private inner class StocksAdapter : RecyclerView.Adapter<StocksAdapter.VH>() {
        inner class VH(val binding: ItemFollowPanelBinding) : RecyclerView.ViewHolder(binding.root)

        private val data = mutableListOf<Stock>()

        fun setData(list: List<Stock>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemFollowPanelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = data[position]
            val b = holder.binding
            b.name.text = s.name
            b.chgTv.text = "${s.chg}%"
            b.chgTv.setTextColor(Color.BLACK)
            if (s.chg > 0) b.chgTv.setTextColor(Color.RED)
            else if (s.chg < 0) b.chgTv.setTextColor(Color.parseColor("#ff00ad43"))

            val defaultStripe =
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.gray_400)
            b.colorStripe.setBackgroundColor(defaultStripe)

            b.root.setOnClickListener { s.openWeb(it.context) }
        }
    }

    companion object {
        private const val ARG_STOCK_CODES = "stockCodes"
        private const val ARG_BK_CODES = "bkCodes"

        fun newInstance(stockCodes: String, bkCodes: String): BkPageFragment {
            return BkPageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STOCK_CODES, stockCodes)
                    putString(ARG_BK_CODES, bkCodes)
                }
            }
        }
    }
}

