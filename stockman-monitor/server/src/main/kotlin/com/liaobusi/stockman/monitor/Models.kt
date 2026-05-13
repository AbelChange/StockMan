package com.liaobusi.stockman.monitor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

@Serializable
data class StockTick(
    val code: String,
    val name: String,
    val price: Double,
    val chg: Double,
    val ztPrice: Double,
    val dtPrice: Double,
    val bk: String = "",
    val time: Long = System.currentTimeMillis()
)

@Serializable
data class AlertEvent(
    val code: String,
    val name: String,
    val title: String,
    val content: String,
    val chg: Double,
    val price: Double,
    val time: Long = System.currentTimeMillis(),
    val reasons: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val replay: ZtReplayItem? = null
)

@Serializable
data class MonitorSnapshot(
    val stocks: List<StockTick>,
    val alerts: List<AlertEvent>
)

@Serializable
data class ManualTickRequest(
    val code: String,
    val price: Double? = null,
    val chg: Double? = null
)

@Serializable
data class MonitorConfig(
    val enabled: Boolean = false,
    val targetSources: List<String> = listOf("limit_up"),
    val customCodes: List<String> = emptyList(),
    val customBkCodes: List<String> = emptyList(),
    val cooldownSeconds: Int = 60,
    val tradingTimeOnly: Boolean = true,
    val browserNotifyEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MonitorConfigRequest(
    val enabled: Boolean? = null,
    val targetSources: List<String>? = null,
    val customCodes: List<String>? = null,
    val customBkCodes: List<String>? = null,
    val cooldownSeconds: Int? = null,
    val tradingTimeOnly: Boolean? = null,
    val browserNotifyEnabled: Boolean? = null
)

@Serializable
data class MonitorStatus(
    val config: MonitorConfig,
    val running: Boolean,
    val tradingTime: Boolean,
    val targetCount: Int,
    val alertCount: Int,
    val lastMessage: String = ""
)

@Serializable
data class MonitorTarget(
    val code: String,
    val name: String,
    val sources: List<String>,
    val limitUp: LimitUpPoolItem? = null,
    val replay: ZtReplayItem? = null
)

@Serializable
data class MonitorTargetsResponse(
    val date: Int,
    val targets: List<MonitorTarget>
)

@Serializable
data class MonitorFollowItem(
    val code: String,
    val type: Int,
    val name: String,
    val stickyOnTop: Int = 1,
    val color: Int = 0,
    val updatedAt: Long = 0
)

@Serializable
data class MonitorTargetsRefreshResult(
    val limitUpPool: SyncWriteResult,
    val ztReplay: SyncWriteResult?,
    val targets: MonitorTargetsResponse
)

@Serializable
data class LimitUpPoolItem(
    val date: Int,
    val code: String,
    val name: String,
    val firstLimitUpTime: Long,
    val lastLimitUpTime: String,
    val highDays: String,
    val limitUpType: String,
    val reasonType: String,
    val openNum: Int,
    val turnoverRate: Double,
    val latest: Double,
    val changeRate: Double
)

@Serializable
data class ZtReplayItem(
    val date: Int,
    val code: String,
    val reason: String,
    val groupName: String,
    val expound: String,
    val time: String = "--:--:--",
    val groupName2: String = "",
    val reason2: String = "",
    val expound2: String = "",
    val expound3: String = ""
)

@Serializable
data class SyncWriteResult(
    val date: Int,
    val count: Int,
    val source: String,
    val message: String
)

@Serializable
data class MonitorFollowRequest(
    val code: String,
    val type: Int = 1,
    val name: String = "",
    val stickyOnTop: Int = 1,
    val color: Int = 0
)

@Serializable
data class MonitorBkStocksRequest(
    val bkCode: String,
    val bkName: String = "",
    val stockCodes: List<String>
)

@Serializable
data class MonitorUnusualRequest(
    val time: Long,
    val comment: String,
    val stocks: String,
    val type: Int = 1,
    val bk: String? = null
)

@Serializable
data class ReplayLiveResponse(
    @SerialName("List") val list: List<ReplayLiveItem> = emptyList(),
    val errcode: String? = null
)

@Serializable
data class ReplayLiveItem(
    @SerialName("Time") val time: Long = 0,
    @SerialName("Comment") val comment: String = "",
    @SerialName("PlateName") val plateName: String = "",
    @SerialName("PlateCode") val plateCode: String = "",
    @SerialName("Stock") val stock: List<List<JsonElement>> = emptyList()
)

@Serializable
data class EastMoneyDebugResult(
    val code: String,
    val secId: String,
    val direct: Boolean,
    val url: String,
    val resolvedHosts: List<String>,
    val httpCode: Int? = null,
    val success: Boolean,
    val elapsedMs: Long,
    val contentLength: Long? = null,
    val bodyPreview: String? = null,
    val okHttpLog: String,
    val error: String? = null
)

data class StockSeed(
    val code: String,
    val name: String,
    val yesterdayClose: Double,
    val limitRate: Double = 0.1,
    val baseChg: Double = 0.0,
    val circulationMarketValue: Double = 0.0,
    val toMarketTime: Int = 20000101,
    val bk: String = ""
) {
    val ztPrice: Double = money(yesterdayClose * (1 + limitRate))
    val dtPrice: Double = money(yesterdayClose * (1 - limitRate))
}

fun StockSeed.tick(chg: Double): StockTick {
    val bounded = chg.coerceIn(-limitRate * 100, limitRate * 100)
    return StockTick(
        code = code,
        name = name,
        price = money(yesterdayClose * (1 + bounded / 100)),
        chg = percent(bounded),
        ztPrice = ztPrice,
        dtPrice = dtPrice,
        bk = bk
    )
}

fun StockSeed.tickByPrice(price: Double): StockTick {
    val chg = ((price - yesterdayClose) / yesterdayClose) * 100
    return StockTick(
        code = code,
        name = name,
        price = money(price),
        chg = percent(chg),
        ztPrice = ztPrice,
        dtPrice = dtPrice,
        bk = bk
    )
}

fun money(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

fun percent(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()

fun samePrice(left: Double, right: Double): Boolean = abs(left - right) < 0.005

fun limitRate(code: String, name: String): Double {
    return when {
        name.startsWith("ST") || name.startsWith("*") -> 1.05
        code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689") -> 1.2
        code.startsWith("82") || code.startsWith("83") || code.startsWith("87") || code.startsWith("88") || code.startsWith("43") || code.startsWith("92") -> 1.3
        else -> 1.1
    }
}

fun downLimitRate(code: String, name: String): Double {
    return when {
        name.startsWith("ST") || name.startsWith("*") -> 0.95
        code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689") -> 0.8
        code.startsWith("82") || code.startsWith("83") || code.startsWith("87") || code.startsWith("88") || code.startsWith("43") || code.startsWith("92") -> 0.7
        else -> 0.9
    }
}
