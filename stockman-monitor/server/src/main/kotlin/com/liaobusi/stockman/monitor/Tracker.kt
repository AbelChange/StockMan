package com.liaobusi.stockman.monitor

import java.util.Locale

class Tracker {
    private val cache = mutableListOf<StockTick>()
    private val cacheSize = 80
    private var minChgIdx = -1

    fun update(stock: StockTick): AlertEvent? {
        val eventTypes = mutableListOf<String>()
        val reasons = buildList {
            val last = cache.lastOrNull()
            if (last != null) {
                if (samePrice(stock.ztPrice, stock.price) && !samePrice(last.price, last.ztPrice)) {
                    add("[涨停]")
                    eventTypes += "LIMIT_UP"
                }
                if (samePrice(last.ztPrice, last.price) && stock.price < stock.ztPrice) {
                    add("[炸板]")
                    eventTypes += "BREAK_LIMIT_UP"
                }
                if (samePrice(stock.dtPrice, stock.price) && !samePrice(last.price, last.dtPrice)) {
                    add("[跌停]")
                    eventTypes += "LIMIT_DOWN"
                }
                if (samePrice(last.dtPrice, last.price) && stock.price > stock.dtPrice) {
                    add("[翘板]")
                    eventTypes += "OPEN_LIMIT_DOWN"
                }
            }

            val skipChgWindows =
                cache.isNotEmpty() && minChgIdx in cache.indices && stock.chg - cache[minChgIdx].chg < 1.0
            if (!skipChgWindows) {
                cache.relativeRecordFromEnd(5)?.let { previous ->
                    appendChgSpike(stock, previous, minDeltaChg = 1.0, secRange = 0..15)?.let {
                        add(it)
                        eventTypes += "SPIKE"
                    }
                }

                val n = cache.size
                if (n >= 3) {
                    appendChgSpike(stock, cache[n * 2 / 3], 2.0, 15..60)?.let {
                        add(it)
                        eventTypes += "SPIKE"
                    }
                    appendChgSpike(stock, cache[n / 3], 3.0, 61..90)?.let {
                        add(it)
                        eventTypes += "SPIKE"
                    }
                }

                cache.firstOrNull()?.let { previous ->
                    appendChgSpike(stock, previous, 4.0, 91..180)?.let {
                        add(it)
                        eventTypes += "SPIKE"
                    }
                }
            }
        }

        pushCache(stock)

        if (reasons.isEmpty()) return null

        return AlertEvent(
            code = stock.code,
            name = stock.name,
            title = "${stock.code}${stock.name}异动,涨跌幅${stock.chg}%",
            content = reasons.joinToString(" "),
            chg = stock.chg,
            price = stock.price,
            time = stock.time,
            reasons = reasons,
            eventTypes = eventTypes.distinct()
        )
    }

    private fun appendChgSpike(
        stock: StockTick,
        previous: StockTick,
        minDeltaChg: Double,
        secRange: IntRange
    ): String? {
        val seconds = ((stock.time - previous.time) / 1000).toInt().coerceAtLeast(0)
        if (seconds !in secRange) return null
        val zf = stock.chg - previous.chg
        if (zf < minDeltaChg) return null
        return "${seconds}秒内涨幅${String.format(Locale.getDefault(), "%.2f", zf)}%"
    }

    private fun pushCache(stock: StockTick) {
        val prevMinChg =
            if (minChgIdx >= 0 && minChgIdx < cache.size) cache[minChgIdx].chg
            else Double.POSITIVE_INFINITY
        cache.add(stock)
        val newIdx = cache.lastIndex
        minChgIdx = when {
            cache.size == 1 -> 0
            stock.chg < prevMinChg -> newIdx
            minChgIdx < 0 || minChgIdx > cache.lastIndex -> indexOfMinimumChg()
            else -> minChgIdx
        }
        if (cache.size >= cacheSize) {
            if (minChgIdx == 0) {
                cache.removeAt(0)
                minChgIdx = indexOfMinimumChg()
            } else {
                cache.removeAt(0)
                minChgIdx--
            }
        }
    }

    private fun indexOfMinimumChg(): Int {
        if (cache.isEmpty()) return -1
        var bestI = 0
        var bestChg = cache[0].chg
        for (i in 1 until cache.size) {
            if (cache[i].chg < bestChg) {
                bestChg = cache[i].chg
                bestI = i
            }
        }
        return bestI
    }

    private fun List<StockTick>.relativeRecordFromEnd(offset: Int): StockTick? {
        return if (size >= offset) get(size - offset) else lastOrNull()
    }
}
