package com.liaobusi.stockman.monitor.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface ThsApi {
    @Headers(
        "Referer: https://data.10jqka.com.cn/datacenterph/limitup/limtupInfo.html",
        "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
    )
    @GET("dataapi/limit_up/limit_up_pool?field=199112%2C10%2C9001%2C330323%2C330324%2C330325%2C9002%2C330329%2C133971%2C133970%2C1968584%2C3475914%2C9003&filter=HS%2CGEM2STAR&order_field=330324&order_type=0")
    suspend fun getLimitUpPool(
        @Query("date") date: Int,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 200
    ): ThsLimitUpPoolResponse

    @Headers(
        "Referer: https://data.10jqka.com.cn/datacenterph/limitup/limtupInfo.html",
        "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
    )
    @GET("dataapi/limit_up/block_top?filter=HS%2CGEM2STAR")
    suspend fun getZtReplay(
        @Query("date") date: Int
    ): ThsZtReplayResponse
}

data class ThsLimitUpPoolResponse(
    @SerializedName("data") val data: ThsLimitUpPoolData?,
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("status_msg") val statusMsg: String?
)

data class ThsLimitUpPoolData(
    @SerializedName("info") val info: List<ThsLimitUpPoolStock>?,
    @SerializedName("page") val page: ThsPage?
)

data class ThsLimitUpPoolStock(
    @SerializedName("code") val code: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("first_limit_up_time") val firstLimitUpTime: String?,
    @SerializedName("last_limit_up_time") val lastLimitUpTime: String?,
    @SerializedName("high_days") val highDays: String?,
    @SerializedName("limit_up_type") val limitUpType: String?,
    @SerializedName("reason_type") val reasonType: String?,
    @SerializedName("open_num") val openNum: Int?,
    @SerializedName("turnover_rate") val turnoverRate: Double?,
    @SerializedName("latest") val latest: Double?,
    @SerializedName("change_rate") val changeRate: Double?
)

data class ThsPage(
    @SerializedName("count") val count: Int?,
    @SerializedName("limit") val limit: Int?,
    @SerializedName("page") val page: Int?,
    @SerializedName("total") val total: Int?
)

data class ThsZtReplayResponse(
    @SerializedName("data") val data: List<ThsZtReplayBlock>?,
    @SerializedName("status_code") val statusCode: Int,
    @SerializedName("status_msg") val statusMsg: String?
)

data class ThsZtReplayBlock(
    @SerializedName("name") val name: String?,
    @SerializedName("block_name") val blockName: String?,
    @SerializedName("reason_type") val reasonType: String?,
    @SerializedName("reason_info") val reasonInfo: String?,
    @SerializedName("stock_list") val stockList: List<ThsZtReplayStock>?
)

data class ThsZtReplayStock(
    @SerializedName("code") val code: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("reason_type") val reasonType: String?,
    @SerializedName("reason_info") val reasonInfo: String?,
    @SerializedName("first_limit_up_time") val firstLimitUpTime: Long?
)
