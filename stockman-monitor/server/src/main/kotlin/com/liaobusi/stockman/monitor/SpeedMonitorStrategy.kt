package com.liaobusi.stockman.monitor

import java.util.Locale

class SpeedMonitorStrategy(
    private val windows: List<SpeedWindow> = DEFAULT_WINDOWS
) {
    fun evaluate(cache: List<StockTick>, current: StockTick): List<SpeedSignal> {
        if (cache.isEmpty()) return emptyList()
        return buildList {
            windows.forEach { window ->
                bestRiseSignal(cache, current, window)?.let { add(it) }
                bestDropSignal(cache, current, window)?.let { add(it) }
            }
        }
    }

    private fun bestRiseSignal(cache: List<StockTick>, current: StockTick, window: SpeedWindow): SpeedSignal? {
        val anchor = bestAnchor(cache, current, window) { previous -> current.chg - previous.chg } ?: return null
        val seconds = elapsedSeconds(current, anchor)
        val delta = current.chg - anchor.chg
        if (delta < window.minDeltaChg) return null
        return SpeedSignal(
            eventType = "SPIKE",
            reason = "${seconds}秒内涨速+${format(delta)}% (${format(anchor.chg)}%→${format(current.chg)}%)",
            seconds = seconds,
            startChg = anchor.chg,
            endChg = current.chg,
            deltaChg = delta,
            window = window
        )
    }

    private fun bestDropSignal(cache: List<StockTick>, current: StockTick, window: SpeedWindow): SpeedSignal? {
        val anchor = bestAnchor(cache, current, window) { previous -> previous.chg - current.chg } ?: return null
        val seconds = elapsedSeconds(current, anchor)
        val delta = anchor.chg - current.chg
        if (delta < window.minDeltaChg) return null
        return SpeedSignal(
            eventType = "DROP",
            reason = "${seconds}秒内跌速-${format(delta)}% (${format(anchor.chg)}%→${format(current.chg)}%)",
            seconds = seconds,
            startChg = anchor.chg,
            endChg = current.chg,
            deltaChg = -delta,
            window = window
        )
    }

    private fun bestAnchor(
        cache: List<StockTick>,
        current: StockTick,
        window: SpeedWindow,
        score: (StockTick) -> Double
    ): StockTick? {
        var best: StockTick? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (previous in cache) {
            if (elapsedSeconds(current, previous) !in window.secondsRange) continue
            val value = score(previous)
            if (value > bestScore) {
                best = previous
                bestScore = value
            }
        }
        return best
    }

    private fun elapsedSeconds(current: StockTick, previous: StockTick): Int {
        return ((current.time - previous.time) / 1000).toInt().coerceAtLeast(0)
    }

    private fun format(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

    companion object {
        val DEFAULT_WINDOWS = listOf(
            SpeedWindow(secondsRange = 0..15, minDeltaChg = 0.5),
            SpeedWindow(secondsRange = 15..60, minDeltaChg = 1.0),
            SpeedWindow(secondsRange = 61..90, minDeltaChg = 1.5),
            SpeedWindow(secondsRange = 91..180, minDeltaChg = 2.0)
        )
    }
}

data class SpeedWindow(
    val secondsRange: IntRange,
    val minDeltaChg: Double
)

data class SpeedSignal(
    val eventType: String,
    val reason: String,
    val seconds: Int,
    val startChg: Double,
    val endChg: Double,
    val deltaChg: Double,
    val window: SpeedWindow
)
