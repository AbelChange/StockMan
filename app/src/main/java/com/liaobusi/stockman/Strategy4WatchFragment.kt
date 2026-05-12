package com.liaobusi.stockman

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.liaobusi.stockman5.R
import com.liaobusi.stockman5.databinding.FragmentStrategy4WatchBinding

/**
 * 均线强势页的「盯盘」模式宿主：自带 Toolbar（自选·板块·涨停池·异动、刷新）；子 Fragment 共用下方容器。
 */
class Strategy4WatchFragment : Fragment() {

    companion object {
        private const val TAG_FOLLOW = "watch_follow"
        private const val TAG_BK = "watch_bk"
        private const val TAG_LIMIT_UP = "watch_limit_up"
        private const val TAG_UNUSUAL = "watch_unusual"
        private const val STATE_WATCH_TAB = "strategy4_watch_tab_index"
    }

    private var _binding: FragmentStrategy4WatchBinding? = null
    private val binding get() = _binding!!

    /** 0 自选 · 1 板块 · 2 涨停池 · 3 异动 */
    private var watchTabIndex: Int = 0

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WATCH_TAB, watchTabIndex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watchTabIndex = savedInstanceState?.getInt(STATE_WATCH_TAB, 0) ?: 0
        if (savedInstanceState == null) {
            val follow = FollowPanelFragment.newInstance()
            val bk = BkPanelFragment.newEmbeddedInstance()
            val limitUp = UnusualActionListFragment.newInstance(5)
            val unusual = UnusualActionListFragment.newInstance(0)
            childFragmentManager.beginTransaction().apply {
                add(R.id.watchFragmentHost, follow, TAG_FOLLOW)
                add(R.id.watchFragmentHost, bk, TAG_BK)
                add(R.id.watchFragmentHost, limitUp, TAG_LIMIT_UP)
                add(R.id.watchFragmentHost, unusual, TAG_UNUSUAL)
                hide(bk)
                hide(limitUp)
                hide(unusual)
                setReorderingAllowed(true)
            }.commit()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStrategy4WatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.watchTabFollowBtn.setOnClickListener { setWatchTab(0) }
        binding.watchTabBkBtn.setOnClickListener { setWatchTab(1) }
        binding.watchTabLimitUpBtn.setOnClickListener { setWatchTab(2) }
        binding.watchTabUnusualBtn.setOnClickListener { setWatchTab(3) }

        binding.watchToolbarRefreshBtn.setOnClickListener {
            (requireActivity() as? Strategy4Activity)?.triggerStrategy4ReplayRefresh()
        }

        styleWatchTabChips(watchTabIndex)
        selectTab(watchTabIndex)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun setWatchTab(index: Int) {
        watchTabIndex = index.coerceIn(0, 3)
        styleWatchTabChips(watchTabIndex)
        selectTab(watchTabIndex)
    }

    private fun styleWatchTabChips(selectedIndex: Int) {
        if (_binding == null) return
        val ctx = binding.root.context
        val idle = ContextCompat.getColor(ctx, R.color.yd_secondary)
        fun chip(tv: TextView, sel: Boolean) {
            tv.setBackgroundResource(
                if (sel) R.drawable.bg_follow_sort_seg_selected
                else R.drawable.bg_follow_sort_seg_idle,
            )
            tv.setTextColor(if (sel) Color.WHITE else idle)
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        chip(binding.watchTabFollowBtn, selectedIndex == 0)
        chip(binding.watchTabBkBtn, selectedIndex == 1)
        chip(binding.watchTabLimitUpBtn, selectedIndex == 2)
        chip(binding.watchTabUnusualBtn, selectedIndex == 3)
    }

    fun selectTab(index: Int) {
        watchTabIndex = index.coerceIn(0, 3)
        if (_binding != null) {
            styleWatchTabChips(watchTabIndex)
        }
        val follow = childFragmentManager.findFragmentByTag(TAG_FOLLOW) ?: return
        val bk = childFragmentManager.findFragmentByTag(TAG_BK) ?: return
        val limitUp = childFragmentManager.findFragmentByTag(TAG_LIMIT_UP) ?: return
        val unusual = childFragmentManager.findFragmentByTag(TAG_UNUSUAL) ?: return
        childFragmentManager.beginTransaction().apply {
            when (watchTabIndex) {
                0 -> {
                    show(follow); hide(bk); hide(limitUp); hide(unusual)
                }
                1 -> {
                    show(bk); hide(follow); hide(limitUp); hide(unusual)
                }
                2 -> {
                    show(limitUp); hide(follow); hide(bk); hide(unusual)
                }
                else -> {
                    show(unusual); hide(follow); hide(bk); hide(limitUp)
                }
            }
            setReorderingAllowed(true)
        }.commit()

        when (watchTabIndex) {
            2 -> (limitUp as? UnusualActionListFragment)?.refreshWhenSelected()
            3 -> (unusual as? UnusualActionListFragment)?.refreshWhenSelected()
        }
    }
}
