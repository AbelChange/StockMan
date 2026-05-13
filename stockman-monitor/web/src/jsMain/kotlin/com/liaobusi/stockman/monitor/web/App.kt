package com.liaobusi.stockman.monitor.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.round
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.css.AlignItems
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.FlexDirection
import org.jetbrains.compose.web.css.JustifyContent
import org.jetbrains.compose.web.css.LineStyle
import org.jetbrains.compose.web.css.Style
import org.jetbrains.compose.web.css.StyleScope
import org.jetbrains.compose.web.css.StyleSheet
import org.jetbrains.compose.web.css.alignItems
import org.jetbrains.compose.web.css.background
import org.jetbrains.compose.web.css.border
import org.jetbrains.compose.web.css.borderRadius
import org.jetbrains.compose.web.css.boxSizing
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.css.flex
import org.jetbrains.compose.web.css.flexDirection
import org.jetbrains.compose.web.css.fontFamily
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.fontWeight
import org.jetbrains.compose.web.css.gap
import org.jetbrains.compose.web.css.gridTemplateColumns
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.justifyContent
import org.jetbrains.compose.web.css.margin
import org.jetbrains.compose.web.css.maxWidth
import org.jetbrains.compose.web.css.minHeight
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Iframe
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import org.jetbrains.compose.web.renderComposable

private val json = Json { ignoreUnknownKeys = true }
private const val MONITOR_QUOTE_REFRESH_MS = 2_000L

fun main() {
    renderComposable(rootElementId = "root") {
        Style(AppStyles)
        MonitorApp()
    }
}

@Composable
fun MonitorApp() {
    val stocks = remember { mutableStateListOf<StockTick>() }
    val alerts = remember { mutableStateListOf<AlertEvent>() }
    val stockMetadataByCode = remember { mutableMapOf<String, StockTick>() }
    val localTrackers = remember { mutableMapOf<String, ClientMonitorTracker>() }
    val lastAlertByCodeAndType = remember { mutableMapOf<String, Long>() }
    val selectedSources = remember { mutableStateListOf<String>() }
    var connected by remember { mutableStateOf(false) }
    var activePage by remember { mutableStateOf("monitor") }
    var monitorSourcesByCode by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var monitorTargetsByCode by remember { mutableStateOf<Map<String, MonitorTarget>>(emptyMap()) }
    var monitorSourceStatus by remember { mutableStateOf("来源加载中") }
    var customCodes by remember { mutableStateOf("") }
    var customBkCodes by remember { mutableStateOf("") }
    var monitoringEnabled by remember { mutableStateOf(false) }
    var backendTargetSources by remember { mutableStateOf(listOf("limit_up")) }
    var cooldownSeconds by remember { mutableStateOf(60) }
    var tradingTime by remember { mutableStateOf(isTradingTime()) }
    var sourceEditorOpen by remember { mutableStateOf(false) }
    var alertFilter by remember { mutableStateOf("all") }
    var notificationState by remember {
        mutableStateOf(runCatching { Notification.permission }.getOrDefault("default"))
    }

    LaunchedEffect(Unit) {
        runCatching {
            val config = loadMonitorConfig()
            monitoringEnabled = config.enabled
            backendTargetSources = normalizeBackendTargetSources(config.targetSources)
            customCodes = config.customCodes.joinToString(" ")
            customBkCodes = config.customBkCodes.joinToString(" ")
            cooldownSeconds = config.cooldownSeconds
            alerts.clear()
            connected = true
        }.onFailure {
            connected = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            tradingTime = isTradingTime()
            if (!tradingTime && monitoringEnabled) {
                monitoringEnabled = false
            }
            delay(30_000)
        }
    }

    LaunchedEffect(selectedSources.toList(), customCodes, backendTargetSources) {
        val result = loadMonitorSourceMap(selectedSources.toList(), customCodes)
        val backend = loadBackendTargetMap()
        monitorSourcesByCode = mergeSourceMaps(result.sourcesByCode, backend.sourcesByCode)
        monitorTargetsByCode = backend.targetsByCode
        monitorSourceStatus = listOf(result.message, backend.message).filter { it.isNotBlank() }.joinToString("；")
        val activeCodes = monitorSourcesByCode.keys
        localTrackers.keys.retainAll(activeCodes)
        lastAlertByCodeAndType.keys.removeAll { key -> key.substringBefore(":") !in activeCodes }
        val metadata = loadStockTicks(activeCodes)
        stockMetadataByCode.keys.retainAll(activeCodes)
        metadata.forEach { stockMetadataByCode[it.code] = it }
        val byCode = stocks.associateBy { it.code }.toMutableMap()
        metadata.forEach { tick ->
            if (byCode[tick.code] == null) byCode[tick.code] = tick
        }
        stocks.clear()
        stocks.addAll(byCode.values.filter { it.code in activeCodes }.sortedByDescending { it.chg })
    }

    LaunchedEffect(monitoringEnabled, tradingTime, monitorSourcesByCode.keys.sorted().joinToString(",")) {
        while (monitoringEnabled && tradingTime) {
            val targetCodes = monitorSourcesByCode.keys
            if (targetCodes.isNotEmpty()) {
                runCatching {
                    val realtimeTicks = fetchSinaRealtimeTicks(targetCodes)
                    val cachedByCode = stockMetadataByCode.toMutableMap()
                    stocks.forEach { cachedByCode[it.code] = it }
                    val targetTicks = realtimeTicks
                        .map { tick -> mergeClientStockTick(tick, cachedByCode[tick.code]) }
                    if (targetTicks.isNotEmpty()) {
                        val byCode = stocks.associateBy { it.code }.toMutableMap()
                        targetTicks.forEach { byCode[it.code] = it }
                        stocks.clear()
                        stocks.addAll(byCode.values.filter { it.code in targetCodes }.sortedByDescending { it.chg })
                        val now = (js("Date.now()") as Double).toLong()
                        targetTicks.forEach { tick ->
                            val candidate = localTrackers.getOrPut(tick.code) { ClientMonitorTracker() }.update(tick)
                                ?: return@forEach
                            val allowedTypes = candidate.eventTypes.distinct().filter { type ->
                                val key = "${tick.code}:$type"
                                val previous = lastAlertByCodeAndType[key] ?: 0L
                                now - previous >= cooldownSeconds * 1000L
                            }
                            if (allowedTypes.isEmpty()) return@forEach
                            allowedTypes.forEach { type -> lastAlertByCodeAndType["${tick.code}:$type"] = now }
                            val target = monitorTargetsByCode[tick.code]
                            val sourceLabels = monitorSourcesByCode[tick.code]
                                ?.split(" / ")
                                ?.filter { it.isNotBlank() }
                                .orEmpty()
                            val alert = candidate.toAlert(
                                allowedTypes = allowedTypes,
                                sources = target?.sources?.takeIf { it.isNotEmpty() } ?: sourceLabels,
                                replay = target?.replay
                            )
                            alerts.add(0, alert)
                            if (alerts.size > 30) alerts.removeLast()
                            showNotification(alert)
                        }
                    }
                }.onFailure {
                    monitorSourceStatus = listOf(monitorSourceStatus, "前端行情失败").filter { it.isNotBlank() }.joinToString("；")
                }
            }
            delay(MONITOR_QUOTE_REFRESH_MS)
        }
    }

    Div({ classes(AppStyles.page) }) {
        Div({ classes(AppStyles.shell) }) {
            Header(
                connected = connected,
                notificationState = notificationState,
                activePage = activePage,
                onPageChange = { activePage = it },
                onRequestNotification = {
                    requestNotificationPermission { notificationState = it }
                }
            )
            when (activePage) {
                "monitor" -> {
                val monitorStocks = stocks
                    .mapNotNull { stock ->
                        val source = monitorSourcesByCode[stock.code]
                        if (source == null) null else MonitorStock(stock, source, monitorTargetsByCode[stock.code])
                    }
                    .sortedWith(
                        compareByDescending<MonitorStock> { item -> alerts.firstOrNull { it.code == item.tick.code }?.time ?: 0L }
                            .thenByDescending { it.actionScore() }
                            .thenByDescending { it.tick.chg }
                    )
                val filteredAlerts = alerts.filter { alertFilter == "all" || it.primaryEventType() == alertFilter }
                DashboardStats(
                    enabled = monitoringEnabled,
                    tradingTime = tradingTime,
                    connected = connected,
                    notificationState = notificationState,
                    stockCount = stockMetadataByCode.size,
                    targetCount = monitorStocks.size,
                    alerts = alerts,
                    cooldownSeconds = cooldownSeconds
                )
                MonitorSourcePanel(
                    selectedSources = selectedSources,
                    backendTargetSources = backendTargetSources,
                    cooldownSeconds = cooldownSeconds,
                    status = monitorSourceStatus,
                    targetCount = monitorStocks.size,
                    expanded = sourceEditorOpen,
                    onToggleExpanded = { sourceEditorOpen = !sourceEditorOpen },
                    onToggleSource = { id ->
                        if (selectedSources.contains(id)) selectedSources.remove(id) else selectedSources.add(id)
                    },
                    onToggleBackendTarget = { id ->
                        backendTargetSources = if (backendTargetSources.contains(id)) {
                            backendTargetSources - id
                        } else {
                            backendTargetSources + id
                        }
                    },
                    onCooldownSecondsChange = { cooldownSeconds = it.coerceIn(5, 3600) },
                    onRefresh = {
                        loadMonitorSourceMapAsync(
                            selectedSourceIds = selectedSources.toList(),
                            customCodes = customCodes,
                            customBkCodes = customBkCodes,
                            backendTargetSources = backendTargetSources,
                            cooldownSeconds = cooldownSeconds
                        ) { result ->
                            monitorSourcesByCode = result.sourcesByCode
                            monitorTargetsByCode = result.targetsByCode
                            monitorSourceStatus = result.message
                        }
                    }
                )
                Div({ classes(AppStyles.panel, AppStyles.alertPanel) }) {
                    AlertHeader(
                        enabled = monitoringEnabled,
                        alertFilter = alertFilter,
                        alerts = alerts,
                        onFilterChange = { alertFilter = it }
                    )
                    AlertList(filteredAlerts)
                }
                Div({ classes(AppStyles.panel, AppStyles.marketPanel) }) {
                    MonitorSectionTitle(
                        enabled = monitoringEnabled,
                        tradingTime = tradingTime,
                        meta = "${monitorStocks.size} 只标的",
                        onStart = {
                            if (isTradingTime()) {
                                saveMonitorConfigAsync(true, backendTargetSources, customCodes, customBkCodes, cooldownSeconds) {}
                                monitoringEnabled = true
                            }
                        },
                        onStop = {
                            saveMonitorConfigAsync(false, backendTargetSources, customCodes, customBkCodes, cooldownSeconds) {}
                            monitoringEnabled = false
                        }
                    )
                    StockTable(
                        stocks = monitorStocks,
                        onFollowStock = { stock ->
                            if (window.confirm("关注个股 ${stock.code} ${stock.name}？")) {
                                updateMonitorFollowAsync(stock.code, 1, true) {}
                            }
                        },
                        onFollowBk = { bkCode ->
                            if (window.confirm("关注板块 $bkCode？")) {
                                updateMonitorFollowAsync(bkCode, 2, true) {}
                            }
                        }
                    )
                }
                ManualTickPanel { alert ->
                    alerts.add(0, alert)
                    if (alerts.size > 30) alerts.removeLast()
                    showNotification(alert)
                }
                EastMoneyDebugPanel()
                }
                "follows" -> FollowPage()
                "kpl-live" -> KplLivePage()
                "jiuyang" -> JiuyangPage()
            }
        }
    }
}

@Composable
fun Header(
    connected: Boolean,
    notificationState: String,
    activePage: String,
    onPageChange: (String) -> Unit,
    onRequestNotification: () -> Unit
) {
    Div({ classes(AppStyles.header) }) {
        Div {
            H1 { Text("StockMan Monitor") }
            P { Text("本地行情监控 · Compose Web") }
        }
        Div({ classes(AppStyles.actions) }) {
            Button(attrs = {
                classes(if (activePage == "monitor") AppStyles.primaryButton else AppStyles.secondaryButton)
                onClick { onPageChange("monitor") }
            }) { Text("监控") }
            Button(attrs = {
                classes(if (activePage == "follows") AppStyles.primaryButton else AppStyles.secondaryButton)
                onClick { onPageChange("follows") }
            }) { Text("关注") }
            Button(attrs = {
                classes(if (activePage == "kpl-live") AppStyles.primaryButton else AppStyles.secondaryButton)
                onClick { onPageChange("kpl-live") }
            }) { Text("开盘啦 异动直播") }
            Button(attrs = {
                classes(if (activePage == "jiuyang") AppStyles.primaryButton else AppStyles.secondaryButton)
                onClick { onPageChange("jiuyang") }
            }) { Text("韭阳公社异动") }
            Span({ classes(if (connected) AppStyles.okPill else AppStyles.warnPill) }) {
                Text(if (connected) "已连接" else "未连接")
            }
            Button(attrs = {
                classes(AppStyles.primaryButton)
                onClick { onRequestNotification() }
            }) {
                Text(
                    when (notificationState) {
                        "granted" -> "通知已开启"
                        "denied" -> "通知被拒绝"
                        "unsupported" -> "不支持通知"
                        else -> "开启通知"
                    }
                )
            }
        }
    }
}

@Composable
fun FollowPage() {
    var follows by remember { mutableStateOf<List<MonitorFollowItem>>(emptyList()) }
    var status by remember { mutableStateOf("加载中") }

    fun reload() {
        kotlinx.coroutines.GlobalScope.promise { loadMonitorFollows() }
            .then {
                follows = it
                status = "共 ${it.size} 条关注"
                null
            }
            .catch {
                status = "加载失败: ${it.message ?: "unknown"}"
                null
            }
    }

    LaunchedEffect(Unit) { reload() }

    Div({ classes(AppStyles.panel) }) {
        Div({ classes(AppStyles.sourceHeader) }) {
            Div {
                H2 { Text("关注列表") }
                P { Text(status) }
            }
            Button(attrs = {
                classes(AppStyles.secondaryButton)
                onClick { reload() }
            }) { Text("刷新") }
        }
        FollowGroup(
            title = "个股",
            follows = follows.filter { it.type == 1 },
            onChanged = { reload() }
        )
        FollowGroup(
            title = "板块",
            follows = follows.filter { it.type == 2 },
            onChanged = { reload() }
        )
    }
}

@Composable
private fun FollowGroup(
    title: String,
    follows: List<MonitorFollowItem>,
    onChanged: () -> Unit
) {
    H2({ classes(AppStyles.followGroupTitle) }) { Text(title) }
    if (follows.isEmpty()) {
        Div({ classes(AppStyles.emptyState) }) { Text("暂无关注") }
        return
    }
    Table({ classes(AppStyles.table) }) {
        Thead {
            Tr {
                Th { Text("代码") }
                Th { Text("名称") }
                Th { Text("排序") }
                Th { Text("操作") }
            }
        }
        Tbody {
            follows.forEachIndexed { index, item ->
                FollowRow(
                    item = item,
                    onSave = { name, order ->
                        updateMonitorFollowAsync(
                            code = item.code,
                            type = item.type,
                            add = true,
                            name = name,
                            stickyOnTop = order
                        ) { onChanged() }
                    },
                    onMove = { delta ->
                        val order = (item.stickyOnTop + delta).coerceAtLeast(0)
                        updateMonitorFollowAsync(
                            code = item.code,
                            type = item.type,
                            add = true,
                            name = item.name,
                            stickyOnTop = order
                        ) { onChanged() }
                    },
                    onDelete = {
                        if (window.confirm("删除关注 ${item.code} ${item.name}？")) {
                            updateMonitorFollowAsync(item.code, item.type, false) { onChanged() }
                        }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < follows.lastIndex
                )
            }
        }
    }
}

@Composable
private fun FollowRow(
    item: MonitorFollowItem,
    onSave: (String, Int) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    var name by remember(item.code, item.type, item.updatedAt) { mutableStateOf(item.name) }
    var order by remember(item.code, item.type, item.updatedAt) { mutableStateOf(item.stickyOnTop.toString()) }
    Tr {
        Td { Text(item.code) }
        Td {
            Input(type = InputType.Text) {
                value(name)
                onInput { name = it.value }
            }
        }
        Td {
            Input(type = InputType.Number) {
                value(order)
                attr("min", "0")
                onInput { order = it.value.toString() }
            }
        }
        Td {
            Div({ classes(AppStyles.followActions) }) {
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    onClick { onSave(name, order.toIntOrNull() ?: item.stickyOnTop) }
                }) { Text("保存") }
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    if (!canMoveUp) disabled()
                    onClick { onMove(1) }
                }) { Text("上移") }
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    if (!canMoveDown) disabled()
                    onClick { onMove(-1) }
                }) { Text("下移") }
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    onClick { onDelete() }
                }) { Text("删除") }
            }
        }
    }
}

@Composable
fun KplLivePage() {
    val items = remember { mutableStateListOf<ReplayLiveItem>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var lastRefresh by remember { mutableStateOf("-") }
    var stopped by remember { mutableStateOf(false) }

    suspend fun refresh() {
        runCatching {
            val formData = js("new URLSearchParams()")
            formData.append("a", "ZhiBoContent")
            formData.append("apiv", "w42")
            formData.append("c", "ConceptionPoint")
            formData.append("PhoneOSNew", "1")
            formData.append("DeviceID", "598d905194133b9e")
            formData.append("VerSion", "5.21.0.2")
            formData.append("index", "0")
            val options = js("({})")
            options.method = "POST"
            options.headers = js("({'Content-Type':'application/x-www-form-urlencoded'})")
            options.body = formData
            val text = fetchKplLiveText(options)
            json.decodeFromString<ReplayLiveResponse>(text)
        }.onSuccess { payload ->
            items.clear()
            items.addAll(payload.list)
            error = ""
            lastRefresh = formatTime((js("Date.now()") as Double).toLong())
        }.onFailure {
            error = it.message ?: it.toString()
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        while (!isAfterKplLiveClose()) {
            refresh()
            delay(10_000)
        }
        refresh()
        stopped = true
    }

    Div({ classes(AppStyles.replayLayout) }) {
        Div({ classes(AppStyles.panel) }) {
            SectionTitle(
                "开盘啦 异动直播",
                if (loading) {
                    "加载中"
                } else if (stopped) {
                    "直播已结束 · $lastRefresh"
                } else {
                    "10 秒自动刷新 · $lastRefresh"
                }
            )
            if (error.isNotBlank()) {
                Div({ classes(AppStyles.errorBox) }) { Text(error) }
            }
            if (!loading && items.isEmpty() && error.isBlank()) {
                Div({ classes(AppStyles.emptyState) }) { Text("暂无复盘内容") }
            }
            Div({ classes(AppStyles.replayList) }) {
                items.forEach { item ->
                    ReplayCard(item)
                }
            }
        }
    }
}

private suspend fun fetchKplLiveText(options: dynamic): String {
    return runCatching {
        val response = window.fetch("https://apphwhq.longhuvip.com/w1/api/index.php", options).await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("HTTP ${response.status.toInt()}: $text")
        if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) {
            kotlin.error("non json response")
        }
        val payload = JSON.parse<dynamic>(text)
        val length = payload.List?.length as? Int ?: 0
        if (length <= 0) kotlin.error("empty kpl live list")
        text
    }.getOrElse {
        val response = window.fetch("http://localhost:8080/api/replay/kpl-live").await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("proxy HTTP ${response.status.toInt()}: $text")
        text
    }
}

@Composable
fun JiuyangPage() {
    var date by remember { mutableStateOf(todayDate()) }
    val url = "https://www.jiuyangongshe.com/action/$date"

    Div({ classes(AppStyles.replayLayout) }) {
        Div({ classes(AppStyles.panel) }) {
            Div({ classes(AppStyles.sectionTitle) }) {
                H2 { Text("韭阳公社异动") }
                Div({ classes(AppStyles.form) }) {
                    Input(type = InputType.Date) {
                        value(date)
                        onInput { date = it.value }
                    }
                    Button(attrs = {
                        classes(AppStyles.secondaryButton)
                        onClick { window.open(url, "_blank") }
                    }) { Text("新窗口") }
                }
            }
            Iframe(attrs = {
                attr("src", url)
                classes(AppStyles.webFrame)
            })
        }
    }
}

private data class ReplayStockChip(
    val code: String,
    val name: String,
    val change: Double
)

private fun List<kotlinx.serialization.json.JsonElement>.toReplayStockChip(): ReplayStockChip? {
    val code = getOrNull(0)?.jsonPrimitive?.content ?: return null
    val name = getOrNull(1)?.jsonPrimitive?.content ?: return null
    val change = getOrNull(2)?.jsonPrimitive?.doubleOrNull ?: 0.0
    return ReplayStockChip(code = code, name = name, change = change)
}

@Composable
fun ReplayCard(item: ReplayLiveItem) {
    Div({ classes(AppStyles.replayCard) }) {
        Div({ classes(AppStyles.replayMeta) }) {
            Span({ classes(AppStyles.timeText) }) { Text(formatSeconds(item.time)) }
            if (item.plateName.isNotBlank()) {
                Span({ classes(AppStyles.platePill) }) {
                    Text(item.plateName + if (item.plateChange.isNotBlank()) " ${item.plateChange}%" else "")
                }
            }
        }
        Div({ classes(AppStyles.replayBody) }) {
            if (item.image.isNotBlank()) {
                Img(src = item.image, attrs = { classes(AppStyles.avatar) })
            }
            Div({ classes(AppStyles.replayContent) }) {
                P { Text(item.comment) }
                val stocks = item.stock.mapNotNull { it.toReplayStockChip() }.take(8)
                if (stocks.isNotEmpty()) {
                    Div({ classes(AppStyles.stockChips) }) {
                        stocks.forEach { stock ->
                            Span({ classes(if (stock.change >= 0.0) AppStyles.stockChipUp else AppStyles.stockChipDown) }) {
                                Text("${stock.code} ${stock.name} ${stock.change.fmt()}%")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, meta: String) {
    Div({ classes(AppStyles.sectionTitle) }) {
        H2 { Text(title) }
        Span { Text(meta) }
    }
}

@Composable
fun DashboardStats(
    enabled: Boolean,
    tradingTime: Boolean,
    connected: Boolean,
    notificationState: String,
    stockCount: Int,
    targetCount: Int,
    alerts: List<AlertEvent>,
    cooldownSeconds: Int
) {
    Div({ classes(AppStyles.dashboardGrid) }) {
        StatusTile("监控", if (enabled) "运行中" else if (tradingTime) "未开始" else "非交易", if (enabled) "目标 $targetCount 只" else "等待启动")
        StatusTile("行情", "前端 2s", if (connected) "服务配置已加载 · 本地缓存 $stockCount 只" else "服务配置未连接")
        StatusTile("通知", "${alerts.size} 条", alertEventSummary(alerts))
        StatusTile("策略", "涨速/跌速", "2s行情 · 15s 0.5% · 冷却 ${cooldownSeconds}s")
        StatusTile("浏览器通知", notificationLabel(notificationState), notificationHelp(notificationState))
    }
}

@Composable
private fun StatusTile(label: String, value: String, meta: String) {
    Div({ classes(AppStyles.statusTile) }) {
        Span({ classes(AppStyles.statusLabel) }) { Text(label) }
        Span({ classes(AppStyles.statusValue) }) { Text(value) }
        Span({ classes(AppStyles.statusMeta) }) { Text(meta) }
    }
}

@Composable
fun AlertHeader(
    enabled: Boolean,
    alertFilter: String,
    alerts: List<AlertEvent>,
    onFilterChange: (String) -> Unit
) {
    Div({ classes(AppStyles.alertHeader) }) {
        Div {
            H2 { Text("异动通知") }
            Span({ classes(AppStyles.statusMeta) }) { Text(if (enabled) "${alerts.size} 条" else "未开始监控") }
        }
        Div({ classes(AppStyles.filterBar) }) {
            listOf(
                "all" to "全部",
                "LIMIT_UP" to "涨停",
                "BREAK_LIMIT_UP" to "炸板",
                "SPIKE" to "急涨",
                "DROP" to "急跌",
                "LIMIT_DOWN" to "跌停",
                "OPEN_LIMIT_DOWN" to "翘板"
            ).forEach { (id, name) ->
                Button(attrs = {
                    classes(if (alertFilter == id) AppStyles.filterButtonOn else AppStyles.filterButtonOff)
                    onClick { onFilterChange(id) }
                }) { Text("$name ${alertCount(alerts, id)}") }
            }
        }
    }
}

@Composable
fun MonitorSectionTitle(
    enabled: Boolean,
    tradingTime: Boolean,
    meta: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Div({ classes(AppStyles.sectionTitle) }) {
        H2 { Text("实时监控") }
        Div({ classes(AppStyles.monitorActions) }) {
            Span({ classes(if (enabled) AppStyles.okPill else AppStyles.warnPill) }) {
                Text(
                    when {
                        enabled -> "监控中"
                        tradingTime -> "未开始"
                        else -> "非交易时间"
                    }
                )
            }
            Span { Text(meta) }
            Span({ classes(AppStyles.monitorIntervalControl) }) {
                Text("新浪行情 ${MONITOR_QUOTE_REFRESH_MS / 1_000} 秒")
            }
            Button(attrs = {
                classes(if (enabled) AppStyles.secondaryButton else AppStyles.primaryButton)
                if (!tradingTime && !enabled) disabled()
                onClick {
                    if (enabled) onStop() else onStart()
                }
            }) {
                Text(if (enabled) "停止监控" else "开始监控")
            }
        }
    }
}

@Composable
fun MonitorSourcePanel(
    selectedSources: List<String>,
    backendTargetSources: List<String>,
    cooldownSeconds: Int,
    status: String,
    targetCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleBackendTarget: (String) -> Unit,
    onCooldownSecondsChange: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Div({ classes(AppStyles.sourcePanel) }) {
        Div({ classes(AppStyles.sourceHeader) }) {
            Div {
                H2 { Text("监控来源") }
                P { Text(sourceSummary(backendTargetSources, selectedSources, targetCount, status)) }
            }
            Button(attrs = {
                classes(AppStyles.secondaryButton)
                onClick { onToggleExpanded() }
            }) { Text(if (expanded) "收起来源" else "编辑来源") }
        }
        Div({ classes(AppStyles.sourceSummaryRow) }) {
            backendTargetSources.forEach { id ->
                SourceIcon(backendTargetName(id))
            }
            selectedSources.take(8).forEach { id ->
                monitorSources.firstOrNull { it.id == id }?.let { SourceIcon(it.name) }
            }
            if (selectedSources.size > 8) Span({ classes(AppStyles.statusMeta) }) { Text("+${selectedSources.size - 8}") }
        }
        if (expanded) {
            Div({ classes(AppStyles.sourceButtons) }) {
                SourceGroupRow("热榜", monitorSources.filter { it.group == "hot" }, selectedSources, onToggleSource)
                SourceGroupRow("龙虎榜", monitorSources.filter { it.group == "lhb" }, selectedSources, onToggleSource)
                BackendTargetRow(
                    "其他",
                    listOf("limit_up" to "涨停", "unusual_today" to "异动", "follow_stock" to "关注个股"),
                    backendTargetSources,
                    onToggleBackendTarget
                )
            }
            Div({ classes(AppStyles.customCodesRow) }) {
                Span { Text("冷却") }
                Input(type = InputType.Number) {
                    value(cooldownSeconds.toString())
                    attr("min", "5")
                    attr("max", "3600")
                    attr("step", "5")
                    onInput { event ->
                        event.value.toString().toIntOrNull()?.let { onCooldownSecondsChange(it) }
                    }
                }
                Span { Text("秒") }
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    onClick { onRefresh() }
                }) { Text("刷新来源") }
            }
        }
    }
}

@Composable
private fun BackendTargetRow(
    title: String,
    sources: List<Pair<String, String>>,
    selectedSources: List<String>,
    onToggleSource: (String) -> Unit
) {
    Div({ classes(AppStyles.sourceGroupRow) }) {
        Span({ classes(AppStyles.sourceGroupTitle) }) { Text(title) }
        Div({ classes(AppStyles.sourceGroupButtons) }) {
            sources.forEach { (id, name) ->
                Button(attrs = {
                    classes(if (selectedSources.contains(id)) AppStyles.sourceButtonOn else AppStyles.sourceButtonOff)
                    onClick { onToggleSource(id) }
                }) {
                    SourceIcon(name)
                    Text(name)
                }
            }
        }
    }
}

@Composable
private fun SourceGroupRow(
    title: String,
    sources: List<MonitorSource>,
    selectedSources: List<String>,
    onToggleSource: (String) -> Unit
) {
    Div({ classes(AppStyles.sourceGroupRow) }) {
        Span({ classes(AppStyles.sourceGroupTitle) }) { Text(title) }
        Div({ classes(AppStyles.sourceGroupButtons) }) {
            sources.forEach { source ->
                Button(attrs = {
                    classes(if (selectedSources.contains(source.id)) AppStyles.sourceButtonOn else AppStyles.sourceButtonOff)
                    onClick { onToggleSource(source.id) }
                }) {
                    Text(source.name)
                    SourceIcon(source.name)
                }
            }
        }
    }
}

@Composable
fun SourceIcon(sourceName: String) {
    val source = monitorSources.firstOrNull { it.name == sourceName }
    val icon = when {
        sourceName == "涨停" -> "涨"
        sourceName == "异动" -> "异"
        sourceName == "关注个股" -> "关"
        sourceName == "昨日涨停" -> "昨"
        sourceName == "今日涨停池" -> "今"
        sourceName.startsWith("连") -> sourceName
        sourceName.startsWith("异动") || sourceName == "今日异动" -> "异"
        sourceName == "自定义" -> "自"
        source != null -> source.icon
        else -> sourceName.take(1)
    }
    Span({
        classes(sourceIconClass(sourceName))
        attr("title", sourceName)
    }) {
        Text(icon)
    }
}

@Composable
fun SourceIconSet(sourceLabel: String, target: MonitorTarget? = null) {
    Div({ classes(AppStyles.sourceIconSet) }) {
        val labels = sourceLabel.split(" / ").filter { it.isNotBlank() }.toMutableList()
        val chainIcon = target?.limitUp?.highDays?.let { limitUpChainIcon(it) }
        if (chainIcon != null && labels.any { it == "今日涨停池" || it == "昨日涨停" }) labels.add(chainIcon)
        labels.distinct().forEach { name ->
            SourceIcon(name)
        }
    }
}

@Composable
private fun StockTable(
    stocks: List<MonitorStock>,
    onFollowStock: (StockTick) -> Unit,
    onFollowBk: (String) -> Unit
) {
    val pageSize = 50
    var page by remember { mutableStateOf(1) }
    var expandedBkCode by remember { mutableStateOf<String?>(null) }
    val totalPages = ((stocks.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(1, totalPages)
    val fromIndex = ((currentPage - 1) * pageSize).coerceAtMost(stocks.size)
    val toIndex = (fromIndex + pageSize).coerceAtMost(stocks.size)
    val pageStocks = stocks.subList(fromIndex, toIndex)

    LaunchedEffect(stocks.size, totalPages) {
        if (page != currentPage) page = currentPage
    }

    Div {
        Table({ classes(AppStyles.table) }) {
            Thead {
                Tr {
                    Th { Text("代码") }
                    Th { Text("名称") }
                    Th { Text("状态") }
                    Th { Text("来源") }
                    Th { Text("板块") }
                    Th { Text("现价") }
                    Th { Text("涨跌幅") }
                    Th { Text("涨停") }
                    Th { Text("跌停") }
                    Th { Text("首次封板") }
                    Th { Text("高度") }
                    Th { Text("开板") }
                    Th { Text("类型") }
                    Th { Text("原因") }
                }
            }
            Tbody {
                pageStocks.forEach { item ->
                    val stock = item.tick
                    val limitUp = item.target?.limitUp
                    val bkCodes = stock.bkCodes()
                    val expanded = expandedBkCode == stock.code
                    val visibleBkCodes = if (expanded) bkCodes else bkCodes.take(3)
                    Tr {
                        Td {
                            Button(attrs = {
                                classes(AppStyles.tableLinkButton)
                                onClick { onFollowStock(stock) }
                            }) { Text(stock.code) }
                        }
                        Td {
                            Button(attrs = {
                                classes(AppStyles.tableLinkButton)
                                onClick { onFollowStock(stock) }
                            }) { Text(stock.name) }
                        }
                        Td { StockActionPill(item) }
                        Td { SourceIconSet(item.source, item.target) }
                        Td {
                            Div({ classes(AppStyles.bkChipSet) }) {
                                visibleBkCodes.forEach { bkCode ->
                                    Button(attrs = {
                                        classes(AppStyles.bkChip)
                                        onClick { onFollowBk(bkCode) }
                                    }) { Text(bkCode) }
                                }
                                if (bkCodes.size > 3) {
                                    Button(attrs = {
                                        classes(AppStyles.bkChipMore)
                                        onClick { expandedBkCode = if (expanded) null else stock.code }
                                    }) { Text(if (expanded) "收起" else "+${bkCodes.size - 3}") }
                                }
                            }
                        }
                        Td { Text(stock.price.fmt()) }
                        Td({ classes(if (stock.chg >= 0) AppStyles.upText else AppStyles.downText) }) {
                            Text("${stock.chg.fmt()}%")
                        }
                        Td { Text(stock.ztPrice.fmt()) }
                        Td { Text(stock.dtPrice.fmt()) }
                        Td { Text(formatLimitUpTime(limitUp?.firstLimitUpTime)) }
                        Td { Text(limitUp?.highDays.orEmpty()) }
                        Td { Text(limitUp?.openNum?.toString().orEmpty()) }
                        Td { Text(limitUp?.limitUpType.orEmpty()) }
                        Td { Text(limitUp?.reasonType.orEmpty()) }
                    }
                }
            }
        }
        Div({ classes(AppStyles.pagination) }) {
            Span {
                val start = if (stocks.isEmpty()) 0 else fromIndex + 1
                Text("第 $currentPage / $totalPages 页 · $start-$toIndex / ${stocks.size}")
            }
            Div({ classes(AppStyles.pageButtons) }) {
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    if (currentPage <= 1) disabled()
                    onClick { page = (currentPage - 1).coerceAtLeast(1) }
                }) { Text("上一页") }
                Button(attrs = {
                    classes(AppStyles.secondaryButton)
                    if (currentPage >= totalPages) disabled()
                    onClick { page = (currentPage + 1).coerceAtMost(totalPages) }
                }) { Text("下一页") }
            }
        }
    }
}

@Composable
private fun StockActionPill(item: MonitorStock) {
    val label = item.actionLabel()
    Span({ classes(AppStyles.actionPill) }) { Text(label) }
}

@Composable
fun AlertList(alerts: List<AlertEvent>) {
    if (alerts.isEmpty()) {
        Div({ classes(AppStyles.emptyState) }) {
            Text("等待异动信号")
        }
        return
    }
    Div({ classes(AppStyles.alertList) }) {
        alerts.forEach { alert ->
            Div({ classes(AppStyles.alertItem) }) {
                Div({ classes(AppStyles.alertTop) }) {
                    Div({ classes(AppStyles.alertTitleRow) }) {
                        AlertEventBadge(alert)
                        Span({ classes(AppStyles.alertTitle) }) { Text("${alert.code} ${alert.name}") }
                    }
                    Span({ classes(if (alert.chg >= 0) AppStyles.upText else AppStyles.downText) }) {
                        Text("${alert.chg.fmt()}%")
                    }
                }
                P { Text(alert.content) }
                if (alert.sources.isNotEmpty()) {
                    Span({ classes(AppStyles.timeText) }) { Text("来源 ${alert.sources.joinToString(" / ")}") }
                }
                alert.replay?.let { replay ->
                    val replayText = listOf(replay.groupName, replay.reason, replay.time)
                        .filter { it.isNotBlank() && it != "--:--:--" }
                        .joinToString(" · ")
                    if (replayText.isNotBlank()) {
                        P { Text(replayText) }
                    }
                }
                Span({ classes(AppStyles.timeText) }) { Text(formatTime(alert.time)) }
            }
        }
    }
}

@Composable
private fun AlertEventBadge(alert: AlertEvent) {
    Span({ classes(AppStyles.alertBadge) }) { Text(alertEventLabel(alert.primaryEventType())) }
}

@Composable
fun ManualTickPanel(onManualAlert: (AlertEvent) -> Unit) {
    var code by remember { mutableStateOf("600519") }
    var chg by remember { mutableStateOf("4.2") }
    var status by remember { mutableStateOf("") }

    Div({ classes(AppStyles.manual) }) {
        Div {
            H2 { Text("手动测试") }
            P { Text("推一个涨跌幅到本地服务，并直接唤醒一条浏览器通知。") }
        }
        Div({ classes(AppStyles.form) }) {
            Input(type = InputType.Text) {
                value(code)
                placeholder("代码")
                onInput { code = it.value }
            }
            Input(type = InputType.Text) {
                value(chg)
                placeholder("涨跌幅")
                onInput { chg = it.value }
            }
            Button(attrs = {
                classes(AppStyles.secondaryButton)
                onClick {
                    val body = """{"code":"$code","chg":${chg.toDoubleOrNull() ?: 0.0}}"""
                    val options = js("({})")
                    options.method = "POST"
                    options.headers = js("""({"Content-Type": "application/json"})""")
                    options.body = body
                    window.fetch("http://localhost:8080/api/tick", options)
                        .then { response -> response.text() }
                        .then { text ->
                            runCatching {
                                val tick = json.decodeFromString<StockTick>(text.toString())
                                val now = (js("Date.now()") as Double).toLong()
                                val alert = AlertEvent(
                                    code = tick.code,
                                    name = tick.name,
                                    title = "${tick.code}${tick.name}手动测试",
                                    content = "手动测试通知，涨跌幅 ${tick.chg.fmt()}%，现价 ${tick.price.fmt()}，${formatTime(now)}",
                                    chg = tick.chg,
                                    price = tick.price,
                                    time = now
                                )
                                onManualAlert(alert)
                                status = if (runCatching { Notification.permission }.getOrNull() == "granted") {
                                    "已推送测试通知"
                                } else {
                                    "已生成测试事件，但浏览器通知未授权"
                                }
                            }.onFailure {
                                status = "推送失败: ${it.message ?: it.toString()}"
                            }
                            null
                        }
                        .catch { error ->
                            status = "推送失败: ${error.toString()}"
                            null
                        }
                }
            }) { Text("推送") }
            if (status.isNotBlank()) {
                Span({ classes(AppStyles.timeText) }) { Text(status) }
            }
        }
    }
}

@Composable
fun EastMoneyDebugPanel() {
    var code by remember { mutableStateOf("300059") }
    var result by remember { mutableStateOf("关闭或切换代理后，点击按钮查看 OkHttp 请求/响应日志。") }

    Div({ classes(AppStyles.manual) }) {
        Div {
            H2 { Text("东财接口测试") }
            P { Text("临时诊断服务端到 push2his.eastmoney.com 的真实网络链路。") }
        }
        Div({ classes(AppStyles.form) }) {
            Input(type = InputType.Text) {
                value(code)
                placeholder("代码")
                onInput { code = it.value }
            }
            Button(attrs = {
                classes(AppStyles.secondaryButton)
                onClick {
                    result = "测试中..."
                    fetchEastMoneyDebug(code, direct = false) { result = it }
                }
            }) { Text("测试东财") }
            Button(attrs = {
                classes(AppStyles.secondaryButton)
                onClick {
                    result = "直连测试中..."
                    fetchEastMoneyDebug(code, direct = true) { result = it }
                }
            }) { Text("强制直连") }
            Div({ classes(AppStyles.debugOutput) }) {
                Text(result)
            }
        }
    }
}

private fun fetchEastMoneyDebug(code: String, direct: Boolean, onResult: (String) -> Unit) {
    val url = "http://localhost:8080/api/debug/eastmoney/history?code=$code&direct=$direct"
    window.fetch(url)
        .then { response -> response.text() }
        .then { text ->
            onResult(debugLogFromResponse(text.toString()))
            null
        }
        .catch { error ->
            onResult("请求失败: ${error.toString()}")
            null
        }
}

private fun debugLogFromResponse(text: String): String {
    return runCatching {
        val payload = JSON.parse<dynamic>(text)
        val log = payload.okHttpLog?.toString().orEmpty()
        log.ifBlank { text }
    }.getOrElse {
        text
    }
}

private data class MonitorStock(
    val tick: StockTick,
    val source: String,
    val target: MonitorTarget? = null
)

private data class MonitorSource(
    val id: String,
    val name: String,
    val icon: String,
    val group: String,
    val url: () -> String,
    val parser: (dynamic) -> List<String>
)

private data class MonitorSourceLoadResult(
    val sourcesByCode: Map<String, String>,
    val message: String,
    val targetsByCode: Map<String, MonitorTarget> = emptyMap()
)

private val monitorSources = listOf(
    MonitorSource(
        id = "em-popularity",
        name = "东方财富热榜",
        icon = "东",
        group = "hot",
        url = { "https://data.eastmoney.com/dataapi/xuangu/list?st=POPULARITY_RANK&sr=1&ps=300&p=1&sty=SECUCODE%2CSECURITY_CODE%2CSECURITY_NAME_ABBR%2CPOPULARITY_RANK&filter=(POPULARITY_RANK%3E0)(POPULARITY_RANK%3C%3D1000)&source=SELECT_SECURITIES&client=WEB" },
        parser = { payload ->
            dynamicArray(payload.result?.data ?: payload.data ?: payload.list).mapNotNull { item ->
                codeFromDynamic(item.SECURITY_CODE ?: item.SECUCODE ?: item.securityCode ?: item.secuCode ?: item.code)
            }
        }
    ),
    MonitorSource(
        id = "ths-hot",
        name = "同花顺热榜",
        icon = "同",
        group = "hot",
        url = { "https://dq.10jqka.com.cn/fuyao/hot_list_data/out/hot_list/v1/stock?stock_type=a&type=hour&list_type=normal" },
        parser = { payload -> dynamicArray(payload.data?.stock_list ?: payload.data?.list ?: payload.list ?: payload.data).mapNotNull { codeFromDynamic(it.code ?: it.stock_code ?: it.stockCode) } }
    ),
    MonitorSource(
        id = "dzh-hot",
        name = "大智慧热榜",
        icon = "智",
        group = "hot",
        url = { "https://imsearch.dzh.com.cn/stock/top?size=300&type=0&time=h" },
        parser = { payload ->
            dynamicArray(payload.result ?: payload.data ?: payload.list).flatMap { item ->
                dynamicObjectKeys(item).mapNotNull { key -> codeFromDynamic(key) }
            }
        }
    ),
    MonitorSource(
        id = "cls-hot",
        name = "财联社热榜",
        icon = "财",
        group = "hot",
        url = { "https://api3.cls.cn/v1/hot_stock?app=cailianpress&os=android&sv=850&sign=e4d8f886f269874b1578fec645b258fa" },
        parser = { payload ->
            dynamicArray(payload.data ?: payload.list).mapNotNull { item ->
                codeFromDynamic(item.stock?.StockID ?: item.title ?: item.code ?: item.stock_code ?: item.secu_code)
            }
        }
    ),
    MonitorSource(
        id = "tgb-hot",
        name = "淘股吧热榜",
        icon = "淘",
        group = "hot",
        url = { "https://www.taoguba.com.cn/new/nrnt/getNoticeStock?type=D" },
        parser = { payload ->
            dynamicArray(payload.dto ?: payload.data ?: payload.list ?: payload.result).mapNotNull { item ->
                codeFromDynamic(item.fullCode ?: item.implied?.stockCode ?: item.code ?: item.stockCode ?: item.stock_code)
            }
        }
    ),
    MonitorSource(
        id = "em-lhb",
        name = "东方财富龙虎榜",
        icon = "龙",
        group = "lhb",
        url = {
            val date = todayDate()
            "https://datacenter-web.eastmoney.com/api/data/v1/get?sortColumns=SECURITY_CODE%2CTRADE_DATE&sortTypes=1%2C-1&pageSize=100&pageNumber=1&reportName=RPT_DAILYBILLBOARD_DETAILSNEW&columns=SECURITY_CODE%2CSECUCODE%2CSECURITY_NAME_ABBR%2CTRADE_DATE%2CEXPLAIN%2CCLOSE_PRICE%2CCHANGE_RATE%2CBILLBOARD_NET_AMT%2CBILLBOARD_BUY_AMT%2CBILLBOARD_SELL_AMT%2CBILLBOARD_DEAL_AMT%2CACCUM_AMOUNT%2CDEAL_NET_RATIO%2CDEAL_AMOUNT_RATIO%2CTURNOVERRATE%2CFREE_MARKET_CAP%2CEXPLANATION%2CD1_CLOSE_ADJCHRATE%2CD2_CLOSE_ADJCHRATE%2CD5_CLOSE_ADJCHRATE%2CD10_CLOSE_ADJCHRATE%2CSECURITY_TYPE_CODE&source=WEB&client=WEB&filter=(TRADE_DATE%3C%3D%27$date%27)(TRADE_DATE%3E%3D%27$date%27)"
        },
        parser = { payload -> dynamicArray(payload.result?.data ?: payload.data ?: payload.list).mapNotNull { codeFromDynamic(it.SECURITY_CODE ?: it.securityCode ?: it.code) } }
    ),
    MonitorSource(
        id = "ths-lhb",
        name = "同花顺龙虎榜",
        icon = "龙",
        group = "lhb",
        url = {
            val date = todayDate()
            "https://data.10jqka.com.cn/dataapi/transaction/stock/v1/list?order_field=hot_rank&order_type=asc&date=$date&filter=&page=1&size=50&module=all&order_null_greater=1"
        },
        parser = { payload ->
            dynamicArray(payload.data?.items ?: payload.data?.list ?: payload.data ?: payload.list).mapNotNull { item ->
                codeFromDynamic(item.stock_code ?: item.stockCode ?: item.code ?: item.market_code)
            }
        }
    )
)

private suspend fun loadMonitorSourceMap(selectedSourceIds: List<String>, customCodes: String): MonitorSourceLoadResult {
    val sourcesByCode = linkedMapOf<String, MutableList<String>>()
    val errors = mutableListOf<String>()
    selectedSourceIds.mapNotNull { id -> monitorSources.firstOrNull { it.id == id } }.forEach { source ->
        runCatching {
            val text = fetchMonitorSourceText(source)
            val payload = JSON.parse<dynamic>(text)
            val codes = source.parser(payload).distinct()
            codes.forEach { code -> sourcesByCode.getOrPut(code) { mutableListOf() }.add(source.name) }
            codes.size
        }.onFailure {
            errors += "${source.name}失败"
        }
    }
    customCodes.split(Regex("\\s+"))
        .mapNotNull { normalizeStockCode(it) }
        .distinct()
        .forEach { code -> sourcesByCode.getOrPut(code) { mutableListOf() }.add("自定义") }
    val labelMap = sourcesByCode.mapValues { (_, names) -> names.distinct().joinToString(" / ") }
    val message = buildString {
        append("前端筛选 ${selectedSourceIds.size} 个，列表命中 ${labelMap.size} 只")
        if (errors.isNotEmpty()) append("；").append(errors.joinToString("、"))
    }
    return MonitorSourceLoadResult(labelMap, message)
}

private suspend fun loadMonitorConfig(): MonitorConfig {
    val response = window.fetch("http://localhost:8080/api/monitor/config").await()
    val text = response.text().await()
    if (!response.ok) kotlin.error("monitor config HTTP ${response.status.toInt()}: $text")
    return json.decodeFromString(text)
}

private suspend fun loadBackendTargetMap(): MonitorSourceLoadResult {
    return runCatching {
        val response = window.fetch("http://localhost:8080/api/monitor/targets").await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("monitor targets HTTP ${response.status.toInt()}: $text")
        val payload = json.decodeFromString<MonitorTargetsResponse>(text)
        val map = payload.targets.associate { target ->
            target.code to target.sources.joinToString(" / ")
        }
        MonitorSourceLoadResult(map, "当前监控池 ${map.size} 只", payload.targets.associateBy { it.code })
    }.getOrElse {
        MonitorSourceLoadResult(emptyMap(), "监控池加载失败")
    }
}

private suspend fun fetchSinaRealtimeTicks(targetCodes: Set<String>): List<StockTick> {
    val response = window.fetch(
        "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "Market_Center.getHQNodeDataSimple?page=1&num=6000&sort=symbol&asc=1&node=hs_a&_s_r_a=page"
    ).await()
    val text = response.text().await()
    val rows = JSON.parse<dynamic>(text)
    val size = rows.length as Int
    val now = (js("Date.now()") as Double).toLong()
    return buildList {
        for (index in 0 until size) {
            val row = rows[index]
            val code = row.code?.toString()?.ifBlank { null }
                ?: row.symbol?.toString().orEmpty()
                    .removePrefix("sh")
                    .removePrefix("sz")
                    .removePrefix("bj")
            if (code !in targetCodes) continue
            val name = row.name?.toString().orEmpty()
            val price = row.trade?.toString()?.toDoubleOrNull() ?: 0.0
            val previousClose = row.settlement?.toString()?.toDoubleOrNull() ?: 0.0
            if (code.isBlank() || name.isBlank() || price <= 0.0 || previousClose <= 0.0) continue
            val chg = row.changepercent?.toString()?.toDoubleOrNull() ?: ((price - previousClose) / previousClose * 100.0)
            val limit = stockLimitRate(code, name)
            add(
                StockTick(
                    code = code,
                    name = name,
                    price = money(price),
                    chg = percent(chg),
                    ztPrice = money(previousClose * limit),
                    dtPrice = money(previousClose * stockDownLimitRate(code, name)),
                    bk = "",
                    time = now
                )
            )
        }
    }
}

private fun mergeClientStockTick(tick: StockTick, cached: StockTick?): StockTick {
    if (cached == null) return tick
    return tick.copy(
        ztPrice = cached.ztPrice.takeIf { it > 0.0 } ?: tick.ztPrice,
        dtPrice = cached.dtPrice.takeIf { it > 0.0 } ?: tick.dtPrice,
        bk = cached.bk.ifBlank { tick.bk }
    )
}

private fun stockLimitRate(code: String, name: String): Double {
    val normalizedName = name.uppercase()
    if (normalizedName.contains("ST") || normalizedName.contains("*")) return 1.05
    if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689")) return 1.20
    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("92")) return 1.30
    return 1.10
}

private fun stockDownLimitRate(code: String, name: String): Double {
    val normalizedName = name.uppercase()
    if (normalizedName.contains("ST") || normalizedName.contains("*")) return 0.95
    if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689")) return 0.80
    if (code.startsWith("8") || code.startsWith("4") || code.startsWith("92")) return 0.70
    return 0.90
}

private fun money(value: Double): Double = round(value * 100.0) / 100.0

private fun percent(value: Double): Double = round(value * 100.0) / 100.0

private suspend fun loadStockTicks(codes: Set<String>): List<StockTick> {
    if (codes.isEmpty()) return emptyList()
    val response = window.fetch("http://localhost:8080/api/stocks?codes=${codes.joinToString(",")}").await()
    val text = response.text().await()
    if (!response.ok) kotlin.error("stocks HTTP ${response.status.toInt()}: $text")
    return json.decodeFromString(text)
}

private fun mergeSourceMaps(left: Map<String, String>, right: Map<String, String>): Map<String, String> {
    val merged = linkedMapOf<String, MutableList<String>>()
    fun addAll(map: Map<String, String>) {
        map.forEach { (code, label) ->
            label.split(" / ").filter { it.isNotBlank() }
                .forEach { merged.getOrPut(code) { mutableListOf() }.add(it) }
        }
    }
    addAll(left)
    addAll(right)
    return merged.mapValues { (_, labels) -> labels.distinct().joinToString(" / ") }
}

private fun normalizeBackendTargetSources(sources: List<String>): List<String> {
    if (sources.isEmpty()) return listOf("limit_up")
    val normalized = linkedSetOf<String>()
    sources.forEach { source ->
        when (source) {
            "limit_up_today", "limit_up_yesterday", "limit_up" -> normalized.add("limit_up")
            "unusual_today", "follow_stock" -> normalized.add(source)
        }
    }
    return normalized.ifEmpty { linkedSetOf("limit_up") }.toList()
}

private fun saveMonitorConfigAsync(
    enabled: Boolean,
    targetSources: List<String>,
    customCodes: String,
    customBkCodes: String,
    cooldownSeconds: Int,
    onDone: () -> Unit
) {
    kotlinx.coroutines.GlobalScope.promise {
        saveMonitorConfig(enabled, targetSources, customCodes, customBkCodes, cooldownSeconds)
    }.then {
        onDone()
        null
    }
}

private suspend fun saveMonitorConfig(
    enabled: Boolean,
    targetSources: List<String>,
    customCodes: String,
    customBkCodes: String,
    cooldownSeconds: Int
) {
    val codes = customCodes.split(Regex("\\s+|,|，"))
        .mapNotNull { normalizeStockCode(it) }
        .distinct()
    val bkCodes = customBkCodes.split(Regex("\\s+|,|，"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val body = json.encodeToString(
        MonitorConfig(
            enabled = enabled,
            targetSources = targetSources.distinct(),
            customCodes = codes,
            customBkCodes = bkCodes,
            cooldownSeconds = cooldownSeconds.coerceIn(5, 3600)
        )
    )
    val options = js("({})")
    options.method = "POST"
    options.headers = js("""({"Content-Type":"application/json"})""")
    options.body = body
    val response = window.fetch("http://localhost:8080/api/monitor/config", options).await()
    if (!response.ok) kotlin.error("save monitor config HTTP ${response.status.toInt()}")
}

private fun updateMonitorFollowAsync(
    code: String,
    type: Int,
    add: Boolean,
    name: String = "",
    stickyOnTop: Int = 1,
    onDone: (String) -> Unit
) {
    val normalized = if (type == 1) normalizeStockCode(code).orEmpty() else code.trim().uppercase()
    if (normalized.isBlank()) {
        onDone("代码为空")
        return
    }
    kotlinx.coroutines.GlobalScope.promise {
        updateMonitorFollow(normalized, type, add, name, stickyOnTop)
        val action = if (add) "已关注" else "已取消"
        "$action $normalized"
    }.then {
        onDone(it)
        null
    }.catch {
        onDone("操作失败: ${it.message ?: "unknown"}")
        null
    }
}

private suspend fun loadMonitorFollows(): List<MonitorFollowItem> {
    val response = window.fetch("http://localhost:8080/api/monitor/follows").await()
    val text = response.text().await()
    if (!response.ok) kotlin.error("monitor follows HTTP ${response.status.toInt()}: $text")
    return json.decodeFromString(text)
}

private suspend fun updateMonitorFollow(code: String, type: Int, add: Boolean, name: String, stickyOnTop: Int) {
    val options = js("({})")
    options.method = "POST"
    if (add) {
        options.headers = js("""({"Content-Type":"application/json"})""")
        options.body = json.encodeToString(
            MonitorFollowRequest(
                code = code,
                type = type,
                name = name,
                stickyOnTop = stickyOnTop
            )
        )
        val response = window.fetch("http://localhost:8080/api/monitor/follow", options).await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("follow HTTP ${response.status.toInt()}: $text")
    } else {
        val response = window.fetch("http://localhost:8080/api/monitor/follow/remove?code=$code&type=$type", options).await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("remove follow HTTP ${response.status.toInt()}: $text")
    }
}

private suspend fun fetchMonitorSourceText(source: MonitorSource): String {
    return runCatching {
        val response = window.fetch(source.url()).await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("HTTP ${response.status.toInt()}")
        if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) {
            kotlin.error("non json response")
        }
        text
    }.getOrElse {
        val response = window.fetch("http://localhost:8080/api/monitor/source/${source.id}").await()
        val text = response.text().await()
        if (!response.ok) kotlin.error("proxy HTTP ${response.status.toInt()}: $text")
        text
    }
}

private fun loadMonitorSourceMapAsync(
    selectedSourceIds: List<String>,
    customCodes: String,
    customBkCodes: String,
    backendTargetSources: List<String>,
    cooldownSeconds: Int,
    onResult: (MonitorSourceLoadResult) -> Unit
) {
    kotlinx.coroutines.GlobalScope.promise {
        saveMonitorConfig(enabled = true, backendTargetSources, customCodes, customBkCodes, cooldownSeconds)
        val local = loadMonitorSourceMap(selectedSourceIds, customCodes)
        val refreshOptions = js("({})")
        refreshOptions.method = "POST"
        window.fetch("http://localhost:8080/api/monitor/targets/refresh", refreshOptions).await()
        val backend = loadBackendTargetMap()
        MonitorSourceLoadResult(
            sourcesByCode = mergeSourceMaps(local.sourcesByCode, backend.sourcesByCode),
            message = listOf(local.message, backend.message).joinToString("；"),
            targetsByCode = backend.targetsByCode
        )
    }.then {
        onResult(it)
        null
    }
}

private fun dynamicArray(value: dynamic): List<dynamic> {
    if (value == null) return emptyList()
    val length = value.length as? Int ?: return emptyList()
    return (0 until length).map { index -> value[index] }
}

private fun dynamicObjectKeys(value: dynamic): List<String> {
    if (value == null) return emptyList()
    val keys = js("Object.keys(value)")
    val length = keys.length as? Int ?: return emptyList()
    return (0 until length).map { index -> keys[index].toString() }
}

private fun codeFromDynamic(value: dynamic): String? {
    val text = value?.toString() ?: return null
    return normalizeStockCode(text)
}

private fun normalizeStockCode(value: String): String? {
    val digits = Regex("\\d{6}").find(value)?.value ?: return null
    return digits
}

private fun StockTick.bkCodes(): List<String> =
    bk.split(',', '，', ' ', ';')
        .map { it.trim().uppercase() }
        .filter { it.startsWith("BK") }
        .distinct()

private fun MonitorStock.actionScore(): Int {
    return when {
        samePrice(tick.price, tick.ztPrice) -> 90
        tick.price > 0.0 && tick.ztPrice > 0.0 && tick.price >= tick.ztPrice * 0.985 -> 72
        tick.chg >= 7.0 -> 56
        tick.chg <= -5.0 -> 48
        tick.chg >= 4.0 -> 34
        else -> 0
    } + if (source.contains("异动")) 8 else 0
}

private fun MonitorStock.actionLabel(): String {
    return when {
        samePrice(tick.price, tick.ztPrice) -> "封板"
        tick.price > 0.0 && tick.ztPrice > 0.0 && tick.price >= tick.ztPrice * 0.985 -> "临板"
        tick.chg >= 7.0 -> "强势"
        tick.chg <= -5.0 -> "急跌"
        tick.chg >= 4.0 -> "异动"
        else -> "观察"
    }
}

private fun AlertEvent.primaryEventType(): String {
    return eventTypes.firstOrNull().orEmpty().ifBlank {
        when {
            content.contains("炸板") -> "BREAK_LIMIT_UP"
            content.contains("涨停") -> "LIMIT_UP"
            content.contains("跌幅") -> "DROP"
            content.contains("涨幅") -> "SPIKE"
            content.contains("跌停") -> "LIMIT_DOWN"
            content.contains("翘板") -> "OPEN_LIMIT_DOWN"
            else -> "UNKNOWN"
        }
    }
}

private fun alertEventLabel(type: String): String {
    return when (type) {
        "LIMIT_UP" -> "涨停"
        "BREAK_LIMIT_UP" -> "炸板"
        "SPIKE" -> "急涨"
        "DROP" -> "急跌"
        "LIMIT_DOWN" -> "跌停"
        "OPEN_LIMIT_DOWN" -> "翘板"
        else -> "异动"
    }
}

private fun alertCount(alerts: List<AlertEvent>, type: String): Int {
    return if (type == "all") alerts.size else alerts.count { it.primaryEventType() == type }
}

private fun alertEventSummary(alerts: List<AlertEvent>): String {
    return "涨停 ${alertCount(alerts, "LIMIT_UP")} / 炸板 ${alertCount(alerts, "BREAK_LIMIT_UP")} / 急涨 ${alertCount(alerts, "SPIKE")} / 急跌 ${alertCount(alerts, "DROP")}"
}

private fun sourceSummary(
    backendTargetSources: List<String>,
    selectedSources: List<String>,
    targetCount: Int,
    status: String
): String {
    val labels = (backendTargetSources.map(::backendTargetName) + selectedSources.mapNotNull { id ->
        monitorSources.firstOrNull { it.id == id }?.name
    }).distinct()
    val sourceText = labels.takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: "未选择"
    return "已选来源：$sourceText · 当前监控池 $targetCount 只 · ${status.substringBefore("；").ifBlank { "已同步" }}"
}

private fun backendTargetName(id: String): String {
    return when (id) {
        "limit_up" -> "涨停"
        "limit_up_today" -> "今日涨停池"
        "limit_up_yesterday" -> "昨日涨停"
        "unusual_today" -> "异动"
        "follow_stock" -> "关注个股"
        "follow_bk" -> "关注板块"
        else -> id
    }
}

private fun notificationLabel(state: String): String {
    return when (state) {
        "granted" -> "已开启"
        "denied" -> "已关闭"
        "unsupported" -> "不支持"
        else -> "未授权"
    }
}

private fun notificationHelp(state: String): String {
    return when (state) {
        "granted" -> "浏览器弹窗可用"
        "denied" -> "地址栏允许通知后生效"
        "unsupported" -> "当前浏览器不可用"
        else -> "点击右上角开启"
    }
}

private fun samePrice(left: Double, right: Double): Boolean =
    kotlin.math.abs(left - right) < 0.001

private data class ClientAlertCandidate(
    val stock: StockTick,
    val events: List<ClientAlertSignal>
) {
    val eventTypes: List<String> get() = events.map { it.eventType }

    fun toAlert(
        allowedTypes: List<String>,
        sources: List<String>,
        replay: ZtReplayItem?
    ): AlertEvent {
        val allowed = events.filter { it.eventType in allowedTypes }
        val reasons = allowed.map { it.reason }
        val types = allowed.map { it.eventType }.distinct()
        return AlertEvent(
            code = stock.code,
            name = stock.name,
            title = "${stock.code}${stock.name}异动,涨跌幅${stock.chg.fmt()}%",
            content = reasons.joinToString(" "),
            chg = stock.chg,
            price = stock.price,
            time = stock.time,
            reasons = reasons,
            sources = sources,
            eventTypes = types,
            replay = replay
        )
    }
}

private data class ClientAlertSignal(
    val eventType: String,
    val reason: String
)

private class ClientMonitorTracker {
    private val cache = mutableListOf<StockTick>()
    private val speedStrategy = ClientSpeedMonitorStrategy()

    fun update(stock: StockTick): ClientAlertCandidate? {
        val events = mutableListOf<ClientAlertSignal>()
        val last = cache.lastOrNull()
        if (last != null) {
            if (samePrice(stock.ztPrice, stock.price) && !samePrice(last.price, last.ztPrice)) {
                events += ClientAlertSignal("LIMIT_UP", "[涨停]")
            }
            if (samePrice(last.ztPrice, last.price) && stock.price < stock.ztPrice) {
                events += ClientAlertSignal("BREAK_LIMIT_UP", "[炸板]")
            }
            if (samePrice(stock.dtPrice, stock.price) && !samePrice(last.price, last.dtPrice)) {
                events += ClientAlertSignal("LIMIT_DOWN", "[跌停]")
            }
            if (samePrice(last.dtPrice, last.price) && stock.price > stock.dtPrice) {
                events += ClientAlertSignal("OPEN_LIMIT_DOWN", "[翘板]")
            }
            events += speedStrategy.evaluate(cache, stock)
        }
        cache += stock
        if (cache.size > 80) cache.removeAt(0)
        return events.takeIf { it.isNotEmpty() }?.let { ClientAlertCandidate(stock, it) }
    }
}

private class ClientSpeedMonitorStrategy {
    private val windows = listOf(
        ClientSpeedWindow(0..15, 0.5),
        ClientSpeedWindow(15..60, 1.0),
        ClientSpeedWindow(61..90, 1.5),
        ClientSpeedWindow(91..180, 2.0)
    )

    fun evaluate(cache: List<StockTick>, current: StockTick): List<ClientAlertSignal> {
        if (cache.isEmpty()) return emptyList()
        return buildList {
            windows.forEach { window ->
                bestRiseSignal(cache, current, window)?.let { add(it) }
                bestDropSignal(cache, current, window)?.let { add(it) }
            }
        }
    }

    private fun bestRiseSignal(cache: List<StockTick>, current: StockTick, window: ClientSpeedWindow): ClientAlertSignal? {
        val anchor = bestAnchor(cache, current, window) { previous -> current.chg - previous.chg } ?: return null
        val seconds = elapsedSeconds(current, anchor)
        val delta = current.chg - anchor.chg
        if (delta < window.minDeltaChg) return null
        return ClientAlertSignal(
            eventType = "SPIKE",
            reason = "${seconds}秒内涨速+${delta.fmt()}% (${anchor.chg.fmt()}%→${current.chg.fmt()}%)"
        )
    }

    private fun bestDropSignal(cache: List<StockTick>, current: StockTick, window: ClientSpeedWindow): ClientAlertSignal? {
        val anchor = bestAnchor(cache, current, window) { previous -> previous.chg - current.chg } ?: return null
        val seconds = elapsedSeconds(current, anchor)
        val delta = anchor.chg - current.chg
        if (delta < window.minDeltaChg) return null
        return ClientAlertSignal(
            eventType = "DROP",
            reason = "${seconds}秒内跌速-${delta.fmt()}% (${anchor.chg.fmt()}%→${current.chg.fmt()}%)"
        )
    }

    private fun bestAnchor(
        cache: List<StockTick>,
        current: StockTick,
        window: ClientSpeedWindow,
        score: (StockTick) -> Double
    ): StockTick? {
        var best: StockTick? = null
        var bestScore = Double.NEGATIVE_INFINITY
        cache.forEach { previous ->
            if (elapsedSeconds(current, previous) !in window.secondsRange) return@forEach
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
}

private data class ClientSpeedWindow(
    val secondsRange: IntRange,
    val minDeltaChg: Double
)

private fun sourceIconClass(sourceName: String): String {
    return when {
        sourceName == "昨日涨停" -> AppStyles.limitYesterdayIcon
        sourceName == "今日涨停池" -> AppStyles.limitTodayIcon
        sourceName.startsWith("连") -> AppStyles.limitChainIcon
        sourceName == "涨停" -> AppStyles.limitTodayIcon
        sourceName == "异动" -> AppStyles.unusualIcon
        sourceName == "关注个股" -> AppStyles.customIcon
        sourceName.startsWith("异动") || sourceName == "今日异动" -> AppStyles.unusualIcon
        sourceName.contains("东方财富") -> AppStyles.eastMoneyIcon
        sourceName.contains("同花顺") -> AppStyles.thsIcon
        sourceName.contains("大智慧") -> AppStyles.dzhIcon
        sourceName.contains("财联社") -> AppStyles.clsIcon
        sourceName.contains("淘股吧") -> AppStyles.tgbIcon
        sourceName == "自定义" -> AppStyles.customIcon
        else -> AppStyles.sourceIcon
    }
}

private fun limitUpChainIcon(highDays: String): String? {
    if (highDays.isBlank() || highDays.contains("首板")) return null
    Regex("(\\d+)连板").find(highDays)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?.takeIf { it >= 2 }
        ?.let { return "连$it" }
    val match = Regex("^(\\d+)天(\\d+)板$").find(highDays) ?: return null
    val days = match.groupValues[1].toIntOrNull() ?: return null
    val boards = match.groupValues[2].toIntOrNull() ?: return null
    return boards.takeIf { it >= 2 && it == days }?.let { "连$it" }
}

private fun formatTime(time: Long): String {
    val date = js("new Date(time)")
    return date.toLocaleTimeString("zh-CN") as String
}

private fun formatSeconds(time: Long): String {
    val date = js("new Date(time * 1000)")
    return date.toLocaleTimeString("zh-CN") as String
}

private fun formatLimitUpTime(time: Long?): String {
    val value = time ?: return ""
    if (value <= 0L) return ""
    if (value > 999999L) return formatSeconds(value)
    val text = value.toString().padStart(6, '0')
    return "${text.substring(0, 2)}:${text.substring(2, 4)}:${text.substring(4, 6)}"
}

private fun todayDate(): String {
    val date = js("new Date()")
    val year = date.getFullYear()
    val month = (date.getMonth() + 1).toString().padStart(2, '0')
    val day = date.getDate().toString().padStart(2, '0')
    return "$year-$month-$day"
}

private fun isAfterKplLiveClose(): Boolean {
    val date = js("new Date()")
    val hour = date.getHours() as Int
    val minute = date.getMinutes() as Int
    return hour > 15 || (hour == 15 && minute >= 0)
}

private fun isTradingTime(): Boolean {
    val date = js("new Date()")
    val day = date.getDay() as Int
    if (day == 0 || day == 6) return false
    val hour = date.getHours() as Int
    val minute = date.getMinutes() as Int
    val minutes = hour * 60 + minute
    return minutes in (9 * 60 + 25)..(11 * 60 + 30) ||
        minutes in (13 * 60)..(15 * 60)
}

private fun Double.fmt(): String = asDynamic().toFixed(2) as String

object AppStyles : StyleSheet() {
    val page by style {
        minHeight(100.percent)
        background("#f4f6f8")
        color(Color("#20242a"))
        fontFamily("Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif")
    }

    val shell by style {
        maxWidth(1180.px)
        property("margin", "0 auto")
        padding(24.px)
        boxSizing("border-box")
    }

    val header by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        gap(16.px)
        margin(0.px, 0.px, 20.px, 0.px)
    }

    val actions by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(10.px)
    }

    val grid by style {
        display(DisplayStyle.Grid)
        gridTemplateColumns("repeat(auto-fit, minmax(320px, 1fr))")
        gap(16.px)
    }

    val dashboardGrid by style {
        display(DisplayStyle.Grid)
        property("grid-template-columns", "repeat(auto-fit, minmax(180px, 1fr))")
        gap(10.px)
        margin(0.px, 0.px, 12.px, 0.px)
    }

    val statusTile by style {
        background("#ffffff")
        borderRadius(8.px)
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        padding(12.px)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(4.px)
        boxSizing("border-box")
    }

    val statusLabel by style {
        color(Color("#66707c"))
        fontSize(12.px)
        fontWeight("700")
    }

    val statusValue by style {
        color(Color("#20242a"))
        fontSize(20.px)
        fontWeight("800")
    }

    val statusMeta by style {
        color(Color("#66707c"))
        fontSize(12.px)
    }

    val panel by style {
        background("#ffffff")
        borderRadius(8.px)
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        padding(16.px)
        boxSizing("border-box")
    }

    val marketPanel by style {
        property("overflow-x", "auto")
    }

    val sectionTitle by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        margin(0.px, 0.px, 12.px, 0.px)
    }

    val table by style {
        width(100.percent)
        property("min-width", "1380px")
        property("border-collapse", "collapse")
        fontSize(14.px)
    }

    val pagination by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        gap(10.px)
        margin(12.px, 0.px, 0.px, 0.px)
        color(Color("#66707c"))
        fontSize(13.px)
        property("flex-wrap", "wrap")
    }

    val pageButtons by style {
        display(DisplayStyle.Flex)
        gap(8.px)
    }

    val monitorActions by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.FlexEnd)
        gap(10.px)
        property("flex-wrap", "wrap")
    }

    val monitorIntervalControl by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(6.px)
        color(Color("#66707c"))
        fontSize(13.px)
    }

    val upText by style {
        color(Color("#c43c3c"))
        fontWeight("700")
    }

    val downText by style {
        color(Color("#12834a"))
        fontWeight("700")
    }

    val okPill by style {
        pill("#e2f5ea", "#127540")
    }

    val warnPill by style {
        pill("#fff3dc", "#9a6200")
    }

    val primaryButton by style {
        button("#20242a", "#ffffff")
    }

    val secondaryButton by style {
        button("#eceff3", "#20242a")
    }

    val alertList by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(10.px)
        property("overflow-y", "auto")
        flex(1)
        property("min-height", "0")
    }

    val alertPanel by style {
        height(260.px)
        margin(0.px, 0.px, 16.px, 0.px)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        property("overflow", "hidden")
    }

    val alertHeader by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.FlexStart)
        gap(12.px)
        margin(0.px, 0.px, 12.px, 0.px)
        property("flex-wrap", "wrap")
    }

    val filterBar by style {
        display(DisplayStyle.Flex)
        gap(6.px)
        property("flex-wrap", "wrap")
        justifyContent(JustifyContent.FlexEnd)
    }

    val filterButtonOn by style {
        button("#20242a", "#ffffff")
        padding(6.px, 9.px)
        fontSize(12.px)
    }

    val filterButtonOff by style {
        button("#f1f5f9", "#334155")
        padding(6.px, 9.px)
        fontSize(12.px)
    }

    val alertItem by style {
        border(1.px, LineStyle.Solid, Color("#e3e7ec"))
        borderRadius(8.px)
        padding(12.px)
        background("#fbfcfd")
    }

    val alertTop by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        gap(8.px)
    }

    val alertTitleRow by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(8.px)
    }

    val alertBadge by style {
        background("#20242a")
        color(Color.white)
        borderRadius(6.px)
        padding(3.px, 6.px)
        fontSize(12.px)
        fontWeight("800")
    }

    val alertTitle by style {
        fontWeight("700")
    }

    val timeText by style {
        color(Color("#737d89"))
        fontSize(12.px)
    }

    val emptyState by style {
        height(180.px)
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        color(Color("#7b8590"))
    }

    val manual by style {
        margin(16.px, 0.px, 0.px, 0.px)
        background("#ffffff")
        borderRadius(8.px)
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        padding(16.px)
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        gap(16.px)
        alignItems(AlignItems.Center)
        property("flex-wrap", "wrap")
    }

    val sourcePanel by style {
        margin(0.px, 0.px, 12.px, 0.px)
        background("#ffffff")
        borderRadius(8.px)
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        padding(16.px)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(12.px)
        boxSizing("border-box")
    }

    val sourceHeader by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        gap(12.px)
        property("flex-wrap", "wrap")
    }

    val sourceSummaryRow by style {
        display(DisplayStyle.Flex)
        gap(6.px)
        property("flex-wrap", "wrap")
        alignItems(AlignItems.Center)
    }

    val sourceButtons by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(8.px)
    }

    val sourceGroupRow by style {
        display(DisplayStyle.Grid)
        property("grid-template-columns", "64px 1fr")
        alignItems(AlignItems.Center)
        gap(10.px)
    }

    val sourceGroupTitle by style {
        color(Color("#66707c"))
        fontSize(13.px)
        fontWeight("700")
    }

    val sourceGroupButtons by style {
        display(DisplayStyle.Flex)
        gap(8.px)
        property("flex-wrap", "wrap")
    }

    val sourceLegend by style {
        display(DisplayStyle.Flex)
        gap(6.px)
        property("flex-wrap", "wrap")
    }

    val sourceButtonOn by style {
        sourceButtonBase()
        background("#20242a")
        color(Color.white)
        border(1.px, LineStyle.Solid, Color("#20242a"))
    }

    val sourceButtonOff by style {
        sourceButtonBase()
        background("#ffffff")
        color(Color("#20242a"))
        border(1.px, LineStyle.Solid, Color("#cfd6df"))
    }

    val sourceIconSet by style {
        display(DisplayStyle.Flex)
        gap(4.px)
        alignItems(AlignItems.Center)
        property("flex-wrap", "wrap")
    }

    val tableLinkButton by style {
        property("appearance", "none")
        border(0.px, LineStyle.Solid, Color("transparent"))
        background("transparent")
        padding(0.px)
        color(Color("#20242a"))
        fontSize(14.px)
        fontWeight("700")
        property("cursor", "pointer")
    }

    val bkChipSet by style {
        display(DisplayStyle.Flex)
        gap(4.px)
        property("flex-wrap", "wrap")
        alignItems(AlignItems.Center)
    }

    val bkChip by style {
        button("#f1f5f9", "#334155")
        padding(4.px, 6.px)
        fontSize(12.px)
    }

    val bkChipMore by style {
        button("#e8eef6", "#1f2937")
        padding(4.px, 6.px)
        fontSize(12.px)
    }

    val actionPill by style {
        background("#eef2f7")
        color(Color("#253040"))
        borderRadius(6.px)
        padding(4.px, 7.px)
        fontSize(12.px)
        fontWeight("800")
        property("white-space", "nowrap")
    }

    val followGroupTitle by style {
        margin(18.px, 0.px, 8.px, 0.px)
        fontSize(18.px)
    }

    val followActions by style {
        display(DisplayStyle.Flex)
        gap(8.px)
        property("flex-wrap", "wrap")
    }

    val sourceIcon by style {
        iconBase("#edf2f7", "#344054")
    }

    val eastMoneyIcon by style {
        iconBase("#e31b23", "#ffffff")
    }

    val thsIcon by style {
        iconBase("#f04438", "#ffffff")
    }

    val dzhIcon by style {
        iconBase("#0b63ce", "#ffffff")
    }

    val clsIcon by style {
        iconBase("#d92d20", "#ffffff")
    }

    val tgbIcon by style {
        iconBase("#f79009", "#ffffff")
    }

    val customIcon by style {
        iconBase("#475467", "#ffffff")
    }

    val limitYesterdayIcon by style {
        iconBase("#7c3aed", "#ffffff")
    }

    val limitTodayIcon by style {
        iconBase("#dc2626", "#ffffff")
    }

    val limitChainIcon by style {
        iconBase("#b91c1c", "#ffffff")
    }

    val unusualIcon by style {
        iconBase("#0f766e", "#ffffff")
    }

    val customCodesRow by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(10.px)
        property("flex-wrap", "wrap")
    }

    val form by style {
        display(DisplayStyle.Flex)
        gap(10.px)
        alignItems(AlignItems.Center)
        property("flex-wrap", "wrap")
    }

    val debugOutput by style {
        width(100.percent)
        background("#f7f9fb")
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        borderRadius(8.px)
        padding(12.px)
        color(Color("#334155"))
        fontSize(13.px)
        property("white-space", "pre-wrap")
        property("word-break", "break-word")
        boxSizing("border-box")
    }

    val replayLayout by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(16.px)
    }

    val replayList by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(12.px)
    }

    val replayCard by style {
        border(1.px, LineStyle.Solid, Color("#e3e7ec"))
        borderRadius(8.px)
        padding(14.px)
        background("#fbfcfd")
    }

    val replayMeta by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        gap(10.px)
        margin(0.px, 0.px, 10.px, 0.px)
        property("flex-wrap", "wrap")
    }

    val platePill by style {
        background("#eef4ff")
        color(Color("#1d4ed8"))
        borderRadius(999.px)
        padding(5.px, 9.px)
        fontSize(12.px)
        fontWeight("700")
    }

    val replayBody by style {
        display(DisplayStyle.Flex)
        gap(12.px)
        alignItems(AlignItems.FlexStart)
    }

    val avatar by style {
        width(36.px)
        height(36.px)
        borderRadius(8.px)
        property("object-fit", "cover")
        property("flex", "0 0 auto")
    }

    val replayContent by style {
        flex(1)
        property("min-width", "0")
    }

    val stockChips by style {
        display(DisplayStyle.Flex)
        gap(8.px)
        margin(10.px, 0.px, 0.px, 0.px)
        property("flex-wrap", "wrap")
    }

    val stockChipUp by style {
        stockChip("#fff1f1", "#c43c3c")
    }

    val stockChipDown by style {
        stockChip("#ecfdf3", "#12834a")
    }

    val errorBox by style {
        background("#fff3f3")
        color(Color("#b42318"))
        border(1.px, LineStyle.Solid, Color("#ffd6d6"))
        borderRadius(8.px)
        padding(12.px)
        margin(0.px, 0.px, 12.px, 0.px)
        fontSize(13.px)
    }

    val webFrame by style {
        width(100.percent)
        height(760.px)
        border(1.px, LineStyle.Solid, Color("#dfe5ec"))
        borderRadius(8.px)
        background("#ffffff")
    }

    init {
        "body" style {
            margin(0.px)
        }
        "h1" style {
            margin(0.px)
            fontSize(28.px)
        }
        "h2" style {
            margin(0.px)
            fontSize(17.px)
        }
        "p" style {
            margin(6.px, 0.px, 0.px, 0.px)
            color(Color("#66707c"))
        }
        "th, td" style {
            padding(12.px, 10.px)
            border(0.px)
            property("border-bottom", "1px solid #edf0f4")
            property("text-align", "left")
            property("white-space", "nowrap")
        }
        "th" style {
            color(Color("#66707c"))
            fontWeight("700")
            background("#f7f9fb")
        }
        "input" style {
            padding(10.px, 11.px)
            border(1.px, LineStyle.Solid, Color("#cfd6df"))
            borderRadius(8.px)
            fontSize(14.px)
            width(110.px)
            boxSizing("border-box")
        }
        ".$monitorIntervalControl input" style {
            width(58.px)
            height(32.px)
            padding(6.px)
        }
    }

    private fun StyleScope.pill(background: String, foreground: String) {
        background(background)
        color(Color(foreground))
        borderRadius(999.px)
        padding(8.px, 10.px)
        fontSize(13.px)
        fontWeight("700")
    }

    private fun StyleScope.button(background: String, foreground: String) {
        background(background)
        color(Color(foreground))
        border(0.px)
        borderRadius(8.px)
        padding(10.px, 13.px)
        fontSize(14.px)
        fontWeight("700")
        property("cursor", "pointer")
    }

    private fun StyleScope.stockChip(background: String, foreground: String) {
        background(background)
        color(Color(foreground))
        borderRadius(6.px)
        padding(5.px, 8.px)
        fontSize(12.px)
        fontWeight("700")
    }

    private fun StyleScope.sourceButtonBase() {
        borderRadius(8.px)
        padding(7.px, 10.px)
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(6.px)
        fontSize(13.px)
        fontWeight("700")
        property("cursor", "pointer")
    }

    private fun StyleScope.iconBase(backgroundColor: String, foregroundColor: String) {
        property("display", "inline-flex")
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        property("min-width", "20px")
        height(20.px)
        padding(0.px, 4.px)
        boxSizing("border-box")
        borderRadius(6.px)
        background(backgroundColor)
        color(Color(foregroundColor))
        fontSize(11.px)
        fontWeight("800")
        property("line-height", "20px")
        property("flex", "0 0 auto")
    }
}
