package com.liaobusi.stockman

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class BkPanelSortViewModel : ViewModel() {

    enum class SortMode {
        HOT,
        CHG,
    }

    private val _mode = MutableLiveData(SortMode.CHG)
    val mode: LiveData<SortMode> = _mode

    fun setMode(newMode: SortMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
    }
}

