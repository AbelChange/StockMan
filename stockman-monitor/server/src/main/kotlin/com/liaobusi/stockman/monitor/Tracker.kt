package com.liaobusi.stockman.monitor

class Tracker {
    private val cache = mutableListOf<StockTick>()
    private val cacheSize = 80
    private val speedMonitorStrategy = SpeedMonitorStrategy()

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

            speedMonitorStrategy.evaluate(cache, stock).forEach { signal ->
                add(signal.reason)
                eventTypes += signal.eventType
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

    private fun pushCache(stock: StockTick) {
        cache.add(stock)
        if (cache.size >= cacheSize) {
            cache.removeAt(0)
        }
    }
}
