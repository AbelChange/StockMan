package com.liaobusi.stockman.monitor.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StockTick(
    val code: String,
    val name: String,
    val price: Double,
    val chg: Double,
    val ztPrice: Double,
    val dtPrice: Double,
    val bk: String = "",
    val time: Long
)

@Serializable
data class AlertEvent(
    val code: String,
    val name: String,
    val title: String,
    val content: String,
    val chg: Double,
    val price: Double,
    val time: Long,
    val reasons: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val replay: ZtReplayItem? = null
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
    val updatedAt: Long = 0
)

@Serializable
data class MonitorTarget(
    val code: String,
    val name: String,
    val sources: List<String> = emptyList(),
    val limitUp: LimitUpPoolItem? = null,
    val replay: ZtReplayItem? = null
)

@Serializable
data class MonitorTargetsResponse(
    val date: Int,
    val targets: List<MonitorTarget> = emptyList()
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
data class MonitorFollowItem(
    val code: String,
    val type: Int,
    val name: String,
    val stickyOnTop: Int = 1,
    val color: Int = 0,
    val updatedAt: Long = 0
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
data class ReplayLiveResponse(
    @SerialName("List") val list: List<ReplayLiveItem> = emptyList(),
    val errcode: String? = null
)

@Serializable
data class ReplayLiveItem(
    @SerialName("ID") val id: String = "",
    @SerialName("Time") val time: Long = 0,
    @SerialName("Comment") val comment: String = "",
    @SerialName("PlateName") val plateName: String = "",
    @SerialName("PlateZDF") val plateChange: String = "",
    @SerialName("UserName") val userName: String = "",
    @SerialName("Image") val image: String = "",
    @SerialName("Stock") val stock: List<List<JsonElement>> = emptyList()
)
