package com.liaobusi.stockman.monitor

import com.liaobusi.stockman.monitor.network.MarketApiFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.sin
import kotlin.random.Random

class MarketEngine {
    private val logger = LoggerFactory.getLogger(MarketEngine::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val unusualClient = OkHttpClient()
    private val seeds = listOf(
        StockSeed("600519", "贵州茅台", 1688.00, baseChg = 0.2, circulationMarketValue = 18200.0, toMarketTime = 20010827, bk = "白酒,消费"),
        StockSeed("300750", "宁德时代", 192.40, limitRate = 0.2, baseChg = -0.1, circulationMarketValue = 8200.0, toMarketTime = 20180611, bk = "锂电池,新能源车"),
        StockSeed("000001", "平安银行", 10.48, baseChg = 0.0, circulationMarketValue = 2030.0, toMarketTime = 19910403, bk = "银行"),
        StockSeed("002594", "比亚迪", 211.60, baseChg = 0.4, circulationMarketValue = 6100.0, toMarketTime = 20110630, bk = "新能源车,电池"),
        StockSeed("688981", "中芯国际", 49.32, limitRate = 0.2, baseChg = 0.1, circulationMarketValue = 3900.0, toMarketTime = 20200716, bk = "半导体,芯片")
    )
    val database = StockDatabase()
    private val realtimeStockSync = RealtimeStockSync(database)
    private val historyStockSync = HistoryStockSync(database)
    private val thsApi = MarketApiFactory.thsApi()
    private val stockLock = Any()
    private val stocks: MutableMap<String, StockTick>
    private var step = 0
    @Volatile private var historyProgress = HistorySyncProgress()
    @Volatile private var stopHistorySyncRequested = false
    private var historySyncJob: Job? = null

    init {
        database.initialize(seeds)
        logger.info("MarketEngine initialized: database={}", database.path())
        stocks = database.getStocks().associateBy { it.code }.toMutableMap()
        scope.launch {
            runCatching { realtimeStockSync.initializeRealtimeStocksIfEmpty() }
                .onSuccess { status ->
                    if (status != null) {
                        reloadStocksFromDatabase()
                    }
                }
                .onFailure { logger.warn("Stock sync failed: {}", it.message) }
        }
        scope.launch {
            while (isActive) {
                delay(60_000)
                syncHistoryFromRealtimeSnapshotAfterClose()
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (currentStockCount() <= seeds.size) {
                    simulate()
                }
            }
        }
    }

    fun snapshot(): MonitorSnapshot {
        return MonitorSnapshot(
            stocks = currentStocksSorted(),
            alerts = emptyList()
        )
    }

    fun stockTicks(codes: Set<String>): List<StockTick> {
        if (codes.isEmpty()) return emptyList()
        return synchronized(stockLock) {
            codes.mapNotNull { stocks[it] }.sortedBy { it.code }
        }
    }

    suspend fun manualTick(request: ManualTickRequest): StockTick? {
        val seed = seeds.firstOrNull { it.code == request.code } ?: return null
        val tick = when {
            request.price != null -> seed.tickByPrice(request.price)
            request.chg != null -> seed.tick(request.chg)
            else -> return null
        }
        accept(tick, persistSeed = true)
        return tick
    }

    suspend fun refreshRealtimeStocksNow(source: String): SyncStatus {
        val refreshSource = RealtimeRefreshSource.from(source)
        logger.info("Manual realtime stock sync requested: source={}", refreshSource)
        val status = realtimeStockSync.refreshRealtimeStocks(source = refreshSource, retry = false)
        reloadStocksFromDatabase()
        logger.info("Manual realtime stock sync completed: count={}, source={}", status.lastStockSyncCount, status.lastStockSyncSource)
        return status
    }

    fun syncStatus(): SyncStatus = database.syncStatus()

    suspend fun syncHistoryStocksNow(codes: List<String>): HistorySyncStatus {
        logger.info("Manual history stock sync requested: codes={}", codes.size)
        return historyStockSync.syncRecentKLines(codes = codes, retry = true)
            .also { logger.info("Manual history stock sync completed: date={}, count={}, stocks={}, source={}", it.lastHistorySyncDate, it.lastHistorySyncCount, it.lastHistorySyncStockCount, it.lastHistorySyncSource) }
    }

    fun historySyncStatus(): HistorySyncStatus = database.historySyncStatus()

    fun startHistoryStocksSync(codes: List<String> = emptyList(), full: Boolean = false): HistorySyncProgress {
        val runningJob = historySyncJob
        if (runningJob?.isActive == true) return historyProgress
        stopHistorySyncRequested = false
        historyProgress = HistorySyncProgress(running = true, lastMessage = if (full) "历史全量同步排队中" else "历史增量同步排队中")
        historySyncJob = scope.launch {
            runCatching {
                historyStockSync.syncRecentKLinesIncremental(
                    codes = codes,
                    retry = true,
                    full = full && codes.isEmpty(),
                    onProgress = { progress -> historyProgress = progress },
                    shouldStop = { stopHistorySyncRequested }
                )
            }.onFailure { error ->
                logger.warn("Background history stock sync failed: {}", error.message)
                historyProgress = historyProgress.copy(
                    running = false,
                    failed = historyProgress.failed + 1,
                    endedAt = System.currentTimeMillis(),
                    lastMessage = "同步异常: ${error.message ?: "unknown error"}"
                )
            }.onSuccess {
                historyProgress = historyProgress.copy(
                    running = false,
                    endedAt = System.currentTimeMillis(),
                    lastMessage = if (stopHistorySyncRequested) {
                        "同步已停止，成功 ${historyProgress.success}，失败 ${historyProgress.failed}"
                    } else {
                        "同步完成，成功 ${historyProgress.success}，失败 ${historyProgress.failed}"
                    }
                )
            }
        }
        return historyProgress
    }

    fun stopHistoryStocksSync(): HistorySyncProgress {
        stopHistorySyncRequested = true
        historyProgress = historyProgress.copy(stopRequested = true, lastMessage = "正在停止，当前请求结束后生效")
        return historyProgress
    }

    fun historyStocksSyncProgress(): HistorySyncProgress = historyProgress

    fun monitorConfig(): MonitorConfig = database.monitorConfig()

    fun saveMonitorConfig(request: MonitorConfigRequest): MonitorConfig {
        return database.saveMonitorConfig(request)
    }

    fun startMonitor(): MonitorStatus {
        val config = database.saveMonitorConfig(MonitorConfigRequest(enabled = true))
        return monitorStatus(config)
    }

    fun stopMonitor(): MonitorStatus {
        val config = database.saveMonitorConfig(MonitorConfigRequest(enabled = false))
        return monitorStatus(config)
    }

    fun monitorStatus(config: MonitorConfig = database.monitorConfig()): MonitorStatus {
        val targets = database.monitorTargets(config).targets
        return MonitorStatus(
            config = config,
            running = config.enabled && (!config.tradingTimeOnly || isTradingSession()),
            tradingTime = isTradingSession(),
            targetCount = targets.size,
            alertCount = 0,
            lastMessage = if (config.enabled) "监控已开启" else "监控已停止"
        )
    }

    fun monitorTargets(): MonitorTargetsResponse = database.monitorTargets()

    fun monitorFollows(): List<MonitorFollowItem> = database.monitorFollows()

    fun upsertMonitorFollow(request: MonitorFollowRequest): MonitorTarget {
        return database.upsertMonitorFollow(request)
    }

    fun removeMonitorFollow(code: String, type: Int): Map<String, Int> {
        val deleted = database.removeMonitorFollow(code, type)
        return mapOf("deleted" to deleted)
    }

    fun upsertBkStocks(request: MonitorBkStocksRequest): SyncWriteResult {
        return database.upsertBkStocks(request)
    }

    fun insertUnusualAction(request: MonitorUnusualRequest): SyncWriteResult {
        return database.insertUnusualAction(request)
    }

    suspend fun syncLimitUpPool(date: Int = ChinaMarketCalendar.currentHistoryTargetDate()): SyncWriteResult {
        val items = mutableListOf<LimitUpPoolItem>()
        var page = 1
        while (true) {
            val response = thsApi.getLimitUpPool(date = date, page = page)
            val rows = response.data?.info.orEmpty()
            rows.mapNotNullTo(items) { row ->
                val code = row.code?.takeIf { it.length == 6 } ?: return@mapNotNullTo null
                LimitUpPoolItem(
                    date = date,
                    code = code,
                    name = row.name.orEmpty(),
                    firstLimitUpTime = row.firstLimitUpTime?.toLongOrNull() ?: 0L,
                    lastLimitUpTime = row.lastLimitUpTime.orEmpty(),
                    highDays = row.highDays.orEmpty(),
                    limitUpType = row.limitUpType.orEmpty(),
                    reasonType = row.reasonType.orEmpty(),
                    openNum = row.openNum ?: 0,
                    turnoverRate = row.turnoverRate ?: 0.0,
                    latest = row.latest ?: 0.0,
                    changeRate = row.changeRate ?: 0.0
                )
            }
            val pageInfo = response.data?.page
            val count = pageInfo?.count ?: rows.size
            val limit = pageInfo?.limit ?: 200
            if (rows.isEmpty() || count < limit) break
            page += 1
        }
        val result = database.upsertLimitUpPool(items.distinctBy { it.date to it.code }, source = "THS")
        return result
    }

    suspend fun syncZtReplay(date: Int = ChinaMarketCalendar.currentHistoryTargetDate()): SyncWriteResult {
        val response = thsApi.getZtReplay(date)
        val items = response.data.orEmpty().flatMap { block ->
            val groupName = block.name ?: block.blockName.orEmpty()
            val reason = block.reasonType.orEmpty()
            val expound = block.reasonInfo.orEmpty()
            block.stockList.orEmpty().mapNotNull { stock ->
                val code = stock.code?.takeIf { it.length == 6 } ?: return@mapNotNull null
                val stockReason = stock.reasonType ?: reason
                val stockExpound = stock.reasonInfo ?: expound
                ZtReplayItem(
                    date = date,
                    code = code,
                    reason = stockReason,
                    groupName = groupName,
                    expound = stockExpound,
                    time = formatLimitUpTime(stock.firstLimitUpTime)
                )
            }
        }
        val result = database.upsertZtReplay(items.distinctBy { it.date to it.code }, source = "THS")
        return result
    }

    fun syncKplLiveUnusual(): SyncWriteResult {
        val body = FormBody.Builder()
            .add("a", "ZhiBoContent")
            .add("apiv", "w42")
            .add("c", "ConceptionPoint")
            .add("PhoneOSNew", "1")
            .add("DeviceID", "598d905194133b9e")
            .add("VerSion", "5.21.0.2")
            .add("index", "0")
            .build()
        val request = Request.Builder()
            .url("https://apphwhq.longhuvip.com/w1/api/index.php")
            .post(body)
            .build()
        val text = unusualClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("KPL live HTTP ${response.code}: ${payload.take(120)}")
            payload
        }
        val rows = json.decodeFromString<ReplayLiveResponse>(text).list.mapNotNull { item ->
            val stocks = item.stock.mapNotNull { stock ->
                stock.firstOrNull()?.jsonPrimitive?.contentOrNull?.let { normalizeStockCodeLoose(it) }
            }.distinct()
            if (stocks.isEmpty()) return@mapNotNull null
            val bk = listOf(item.plateName, item.plateCode)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { null }
            MonitorUnusualRequest(
                time = item.time,
                comment = item.comment,
                stocks = stocks.joinToString(","),
                type = 3,
                bk = bk
            )
        }
        val result = database.upsertUnusualActions(rows, source = "KPL")
        return result
    }

    private fun syncHistoryFromRealtimeSnapshotAfterClose(force: Boolean = false) {
        if (!force && !ChinaMarketCalendar.isAfterTradingClose(LocalDateTime.now(RealtimeStockSync.CHINA_ZONE))) return
        val stockStatus = database.syncStatus()
        val targetDate = ChinaMarketCalendar.currentHistoryTargetDate(LocalDateTime.now(RealtimeStockSync.CHINA_ZONE))
        if (!force && database.lastRealtimeToHistoryDate() == targetDate) return
        val stockDate = stockStatus.lastStockSyncDate?.let { ChinaMarketCalendar.normalizeTradingDate(it) }
        if (stockDate != targetDate) {
            logger.info("Skip realtime-to-history sync: stockDate={}, targetDate={}", stockDate, targetDate)
            return
        }
        val stockCount = stockStatus.lastStockSyncCount ?: 0
        if (stockCount <= FULL_MARKET_STOCK_MIN_COUNT) {
            logger.info("Skip realtime-to-history sync: stockCount={} incomplete", stockCount)
            return
        }
        val status = database.upsertHistoryFromCurrentStocks(
            date = targetDate,
            source = "RealtimeStock:${stockStatus.lastStockSyncSource ?: "unknown"}"
        )
        database.markRealtimeToHistoryDate(targetDate)
        logger.info(
            "Realtime-to-history sync completed: date={}, stockCount={}, historyCount={}, source={}",
            status.lastHistorySyncDate,
            status.lastHistorySyncStockCount,
            status.lastHistorySyncCount,
            status.lastHistorySyncSource
        )
    }

    private suspend fun simulate() {
        step += 1
        seeds.forEachIndexed { index, seed ->
            val previous = synchronized(stockLock) { stocks.getValue(seed.code) }
            val wave = sin((step + index * 9) / 7.0) * 0.35
            val noise = Random.nextDouble(-0.12, 0.12)
            val pulse = when {
                seed.code == "000001" && step % 45 in 8..12 -> 1.15
                seed.code == "002594" && step % 80 == 20 -> 3.2
                seed.code == "600519" && step % 70 == 30 -> 9.98
                seed.code == "600519" && step % 70 == 31 -> 8.7
                seed.code == "300750" && step % 90 == 40 -> -19.95
                seed.code == "300750" && step % 90 == 41 -> -17.2
                else -> 0.0
            }
            val nextChg = if (pulse != 0.0) pulse else previous.chg + wave + noise
            accept(seed.tick(nextChg))
        }
    }

    private suspend fun accept(tick: StockTick, persistSeed: Boolean = false) {
        synchronized(stockLock) {
            stocks[tick.code] = tick
        }
        if (persistSeed) seeds.firstOrNull { it.code == tick.code }?.let { seed ->
            database.upsertTick(seed, tick)
        }
    }

    private fun reloadStocksFromDatabase() {
        val syncedStocks = database.getStocks().sortedBy { it.code }
        synchronized(stockLock) {
            stocks.clear()
            stocks.putAll(syncedStocks.associateBy { it.code })
        }
    }

    private fun currentStocksSorted(): List<StockTick> {
        return synchronized(stockLock) { stocks.values.sortedBy { it.code } }
    }

    private fun currentStockCount(): Int {
        return synchronized(stockLock) { stocks.size }
    }

    private fun isTradingSession(now: LocalDateTime = LocalDateTime.now(RealtimeStockSync.CHINA_ZONE)): Boolean {
        if (!ChinaMarketCalendar.isTradingDay(now.toLocalDate())) return false
        val time = now.toLocalTime()
        return (time > LocalTime.of(9, 30) && time < LocalTime.of(11, 30)) ||
            (time >= LocalTime.of(13, 0) && time < LocalTime.of(15, 0))
    }

    private fun formatLimitUpTime(value: Long?): String {
        val seconds = value ?: return "--:--:--"
        if (seconds <= 0) return "--:--:--"
        val text = seconds.toString().padStart(6, '0')
        return "${text.substring(0, 2)}:${text.substring(2, 4)}:${text.substring(4, 6)}"
    }

    private fun normalizeStockCodeLoose(value: String): String? =
        Regex("\\d{6}").find(value)?.value
}
