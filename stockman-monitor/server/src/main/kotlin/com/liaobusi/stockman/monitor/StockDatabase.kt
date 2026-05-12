package com.liaobusi.stockman.monitor

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

const val FULL_MARKET_STOCK_MIN_COUNT = 5000

class StockDatabase(private val dbPath: Path = defaultDatabasePath()) {
    private val jdbcUrl: String
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        Files.createDirectories(dbPath.parent)
        Class.forName("org.sqlite.JDBC")
        jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
    }

    fun initialize(seeds: List<StockSeed>) {
        connection().use { conn ->
            conn.createStatement().use { statement ->
                statement.executeUpdate("PRAGMA journal_mode=WAL")
                statement.executeUpdate("PRAGMA foreign_keys=ON")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS stock (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        price REAL NOT NULL,
                        chg REAL NOT NULL,
                        amplitude REAL NOT NULL,
                        turnoverRate REAL NOT NULL,
                        highest REAL NOT NULL,
                        lowest REAL NOT NULL,
                        circulationMarketValue REAL NOT NULL,
                        toMarketTime INTEGER NOT NULL,
                        openPrice REAL NOT NULL,
                        yesterdayClosePrice REAL NOT NULL,
                        ztPrice REAL NOT NULL DEFAULT -1.0,
                        dtPrice REAL NOT NULL DEFAULT -1.0,
                        averagePrice REAL NOT NULL DEFAULT -1.0,
                        bk TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS historystock (
                        code TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        closePrice REAL NOT NULL,
                        openPrice REAL NOT NULL,
                        highest REAL NOT NULL,
                        lowest REAL NOT NULL,
                        chg REAL NOT NULL,
                        amplitude REAL NOT NULL,
                        turnoverRate REAL NOT NULL,
                        ztPrice REAL NOT NULL DEFAULT -1.0,
                        dtPrice REAL NOT NULL DEFAULT -1.0,
                        yesterdayClosePrice REAL NOT NULL DEFAULT -1.0,
                        averagePrice REAL NOT NULL DEFAULT -1.0,
                        PRIMARY KEY (code, date)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_historystock_date ON historystock(date)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_historystock_code_date ON historystock(code, date)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sync_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS history_sync_result (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        source TEXT NOT NULL,
                        rowCount INTEGER NOT NULL,
                        message TEXT NOT NULL DEFAULT '',
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                migrateHistorySyncResultTable(conn)
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_sync_result_status_date ON history_sync_result(status, endDate)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS monitor_config (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        enabled INTEGER NOT NULL,
                        targetSources TEXT NOT NULL,
                        customCodes TEXT NOT NULL,
                        cooldownSeconds INTEGER NOT NULL,
                        tradingTimeOnly INTEGER NOT NULL,
                        browserNotifyEnabled INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                runCatching {
                    statement.executeUpdate("ALTER TABLE monitor_config ADD COLUMN customBkCodes TEXT NOT NULL DEFAULT ''")
                }
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS monitor_alert (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        code TEXT NOT NULL,
                        name TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        price REAL NOT NULL,
                        chg REAL NOT NULL,
                        eventTypes TEXT NOT NULL,
                        reasons TEXT NOT NULL,
                        sources TEXT NOT NULL,
                        replayJson TEXT NOT NULL DEFAULT '',
                        triggeredAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        snapshotJson TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_monitor_alert_time ON monitor_alert(triggeredAt)")
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_monitor_alert_code_time ON monitor_alert(code, triggeredAt)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS limit_up_pool (
                        date INTEGER NOT NULL,
                        code TEXT NOT NULL,
                        name TEXT NOT NULL,
                        firstLimitUpTime INTEGER NOT NULL,
                        lastLimitUpTime TEXT NOT NULL,
                        highDays TEXT NOT NULL,
                        limitUpType TEXT NOT NULL,
                        reasonType TEXT NOT NULL,
                        openNum INTEGER NOT NULL,
                        turnoverRate REAL NOT NULL,
                        latest REAL NOT NULL,
                        changeRate REAL NOT NULL,
                        PRIMARY KEY(date, code)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_limit_up_pool_code_date ON limit_up_pool(code, date)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS monitor_follow (
                        code TEXT NOT NULL,
                        type INTEGER NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        stickyOnTop INTEGER NOT NULL DEFAULT 1,
                        color INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(code, type)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS bk (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS bk_stock (
                        bkCode TEXT NOT NULL,
                        stockCode TEXT NOT NULL,
                        PRIMARY KEY(bkCode, stockCode)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bk_stock_stock ON bk_stock(stockCode)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS unusual_action_history (
                        time INTEGER NOT NULL,
                        type INTEGER NOT NULL,
                        comment TEXT NOT NULL,
                        stocks TEXT NOT NULL,
                        bk TEXT,
                        PRIMARY KEY(time, type)
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_unusual_action_time_type ON unusual_action_history(time, type)")
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS zt_replay (
                        date INTEGER NOT NULL,
                        code TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        groupName TEXT NOT NULL,
                        expound TEXT NOT NULL,
                        time TEXT NOT NULL DEFAULT '--:--:--',
                        groupName2 TEXT NOT NULL DEFAULT '',
                        reason2 TEXT NOT NULL DEFAULT '',
                        expound2 TEXT NOT NULL DEFAULT '',
                        expound3 TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(date, code)
                    )
                    """.trimIndent()
                )
            }

            if (tableCount(conn, "stock") == 0) {
                seedCurrentStocks(conn, seeds)
            }
            if (tableCount(conn, "historystock") == 0 && tableCount(conn, "stock") <= seeds.size) {
                seedHistoryStocks(conn, seeds)
            }
            if (tableCount(conn, "history_sync_result") == 0) {
                backfillHistorySyncResults(conn)
            }
            reconcileHistorySyncResultCoverage(conn)
        }
    }

    fun getStocks(): List<StockTick> = connection().use { conn ->
        conn.prepareStatement("SELECT code, name, price, chg, ztPrice, dtPrice, bk FROM stock ORDER BY code").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockTick(
                                code = rs.getString("code"),
                                name = rs.getString("name"),
                                price = rs.getDouble("price"),
                                chg = rs.getDouble("chg"),
                                ztPrice = rs.getDouble("ztPrice"),
                                dtPrice = rs.getDouble("dtPrice"),
                                bk = rs.getString("bk")
                            )
                        )
                    }
                }
            }
        }
    }

    fun stockCount(): Int = connection().use { conn -> tableCount(conn, "stock") }

    fun stockRefs(): List<StockRef> = connection().use { conn ->
        conn.prepareStatement("SELECT code, name, toMarketTime FROM stock ORDER BY code").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockRef(
                                code = rs.getString("code"),
                                name = rs.getString("name"),
                                toMarketTime = rs.getInt("toMarketTime")
                            )
                        )
                    }
                }
            }
        }
    }

    fun stockRefsByCodes(codes: List<String>): List<StockRef> {
        if (codes.isEmpty()) return emptyList()
        return connection().use { conn ->
            val placeholders = codes.joinToString(",") { "?" }
            conn.prepareStatement("SELECT code, name, toMarketTime FROM stock WHERE code IN ($placeholders) ORDER BY code").use { ps ->
                codes.forEachIndexed { index, code -> ps.setString(index + 1, code) }
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StockRef(
                                    code = rs.getString("code"),
                                    name = rs.getString("name"),
                                    toMarketTime = rs.getInt("toMarketTime")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun stockRefsPendingHistorySync(endDate: Int, minRows: Int): List<StockRef> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT s.code, s.name, s.toMarketTime
            FROM stock s
            LEFT JOIN history_sync_result r
                ON r.code = s.code
            WHERE r.code IS NULL
                OR r.status <> 'SUCCESS'
                OR r.rowCount <= 0
                OR r.endDate < ?
            ORDER BY s.code
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, endDate)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockRef(
                                code = rs.getString("code"),
                                name = rs.getString("name"),
                                toMarketTime = rs.getInt("toMarketTime")
                            )
                        )
                    }
                }
            }
        }
    }

    fun stockRefsMissingRecentHistory(minDate: Int): List<StockRef> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT s.code, s.name, s.toMarketTime, MAX(h.date) AS max_history_date
            FROM stock s
            LEFT JOIN historystock h ON h.code = s.code
            GROUP BY s.code, s.name, s.toMarketTime
            HAVING max_history_date IS NULL OR max_history_date < ?
            ORDER BY s.code
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, minDate)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockRef(
                                code = rs.getString("code"),
                                name = rs.getString("name"),
                                toMarketTime = rs.getInt("toMarketTime")
                            )
                        )
                    }
                }
            }
        }
    }

    fun upsertTick(seed: StockSeed, tick: StockTick) {
        connection().use { conn ->
            conn.autoCommit = false
            updateDailyStock(conn, seed, tick)
            upsertTodayHistory(conn, seed, tick)
            conn.commit()
        }
    }

    fun replaceStocksFromSync(
        stocks: List<SyncedStock>,
        date: Int,
        clearBeforeInsert: Boolean = false,
        source: String = "unknown",
        preserveExistingBk: Boolean = false
    ) {
        require(stocks.size > FULL_MARKET_STOCK_MIN_COUNT) { "daily stock snapshot is incomplete: ${stocks.size}" }
        connection().use { conn ->
            conn.autoCommit = false
            try {
                if (clearBeforeInsert) {
                    conn.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM stock")
                    }
                }
                stocks.forEach { stock ->
                    updateDailyStock(conn, stock, preserveExistingBk)
                }
                reconcileHistorySyncResultCoverage(conn)
                setMeta(conn, "last_stock_sync_date", date.toString())
                setMeta(conn, "last_stock_sync_time", System.currentTimeMillis().toString())
                setMeta(conn, "last_stock_sync_count", stocks.size.toString())
                setMeta(conn, "last_stock_sync_source", source)
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    fun hasEastMoneyRealtimeSlot(slot: String): Boolean {
        return connection().use { conn ->
            getMeta(conn, "last_eastmoney_realtime_slot") == slot
        }
    }

    fun markEastMoneyRealtimeSlot(slot: String) {
        connection().use { conn ->
            setMeta(conn, "last_eastmoney_realtime_slot", slot)
        }
    }

    fun markDailyStockSlot(slot: String) {
        connection().use { conn ->
            setMeta(conn, "last_stock_sync_slot", slot)
        }
    }

    fun lastRealtimeToHistoryDate(): Int? {
        return connection().use { conn ->
            getMeta(conn, "last_realtime_to_history_date")?.toIntOrNull()
        }
    }

    fun markRealtimeToHistoryDate(date: Int) {
        connection().use { conn ->
            setMeta(conn, "last_realtime_to_history_date", date.toString())
        }
    }

    fun upsertHistoryFromSync(histories: List<SyncedHistoryStock>, source: String, requestedStocks: Int): HistorySyncStatus {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                histories.forEach { upsertHistory(conn, it) }
                val distinctCodes = histories.map { it.code }.distinct().size
                val syncDate = histories.maxOfOrNull { it.date }
                setMeta(conn, "last_history_sync_time", System.currentTimeMillis().toString())
                if (syncDate != null) setMeta(conn, "last_history_sync_date", syncDate.toString())
                setMeta(conn, "last_history_sync_count", histories.size.toString())
                setMeta(conn, "last_history_sync_stock_count", distinctCodes.toString())
                setMeta(conn, "last_history_sync_requested_stock_count", requestedStocks.toString())
                setMeta(conn, "last_history_sync_source", source)
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return historySyncStatus()
    }

    fun upsertHistoryForCode(histories: List<SyncedHistoryStock>, source: String, requestedStocks: Int): HistorySyncStatus {
        if (histories.isEmpty()) return historySyncStatus()
        connection().use { conn ->
            conn.autoCommit = false
            try {
                histories.forEach { upsertHistory(conn, it) }
                val syncDate = histories.maxOfOrNull { it.date }
                setMeta(conn, "last_history_sync_time", System.currentTimeMillis().toString())
                if (syncDate != null) setMeta(conn, "last_history_sync_date", syncDate.toString())
                setMeta(conn, "last_history_sync_count", tableCount(conn, "historystock").toString())
                setMeta(conn, "last_history_sync_stock_count", distinctHistoryStockCount(conn).toString())
                setMeta(conn, "last_history_sync_requested_stock_count", requestedStocks.toString())
                setMeta(conn, "last_history_sync_source", source)
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return historySyncStatus()
    }

    fun upsertHistoryFromCurrentStocks(date: Int, source: String): HistorySyncStatus {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val stockCount = tableCount(conn, "stock")
                conn.prepareStatement(
                    """
                    INSERT INTO historystock (
                        code, date, closePrice, openPrice, highest, lowest, chg, amplitude,
                        turnoverRate, ztPrice, dtPrice, yesterdayClosePrice, averagePrice
                    )
                    SELECT
                        code,
                        ?,
                        price,
                        openPrice,
                        highest,
                        lowest,
                        chg,
                        amplitude,
                        turnoverRate,
                        ztPrice,
                        dtPrice,
                        yesterdayClosePrice,
                        averagePrice
                    FROM stock
                    WHERE 1 = 1
                    ON CONFLICT(code, date) DO UPDATE SET
                        closePrice = excluded.closePrice,
                        openPrice = excluded.openPrice,
                        highest = excluded.highest,
                        lowest = excluded.lowest,
                        chg = excluded.chg,
                        amplitude = excluded.amplitude,
                        turnoverRate = excluded.turnoverRate,
                        ztPrice = excluded.ztPrice,
                        dtPrice = excluded.dtPrice,
                        yesterdayClosePrice = excluded.yesterdayClosePrice,
                        averagePrice = excluded.averagePrice
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, date)
                    ps.executeUpdate()
                }
                upsertHistorySyncResultsFromHistory(conn, source, date)
                setMeta(conn, "last_history_sync_time", System.currentTimeMillis().toString())
                setMeta(conn, "last_history_sync_date", date.toString())
                setMeta(conn, "last_history_sync_count", tableCount(conn, "historystock").toString())
                setMeta(conn, "last_history_sync_stock_count", stockCount.toString())
                setMeta(conn, "last_history_sync_requested_stock_count", stockCount.toString())
                setMeta(conn, "last_history_sync_source", source)
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return historySyncStatus()
    }

    fun upsertHistorySyncResult(result: HistoryCodeSyncResult) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO history_sync_result (
                    code, name, status, source, rowCount, message, startDate, endDate, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    name = excluded.name,
                    status = excluded.status,
                    source = excluded.source,
                    rowCount = excluded.rowCount,
                    message = excluded.message,
                    startDate = excluded.startDate,
                    endDate = excluded.endDate,
                    updatedAt = excluded.updatedAt
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, result.code)
                ps.setString(2, result.name)
                ps.setString(3, result.status.name)
                ps.setString(4, result.source)
                ps.setInt(5, result.rowCount)
                ps.setString(6, result.message)
                ps.setInt(7, result.startDate)
                ps.setInt(8, result.endDate)
                ps.setInt(9, result.endDate)
                ps.executeUpdate()
            }
        }
    }

    private fun upsertHistorySyncResultsFromHistory(conn: Connection, source: String, updatedAt: Int) {
        conn.prepareStatement(
            """
            INSERT INTO history_sync_result (
                code, name, status, source, rowCount, message, startDate, endDate, updatedAt
            )
            SELECT
                s.code,
                s.name,
                'SUCCESS',
                ?,
                COUNT(h.date),
                '',
                COALESCE(MIN(h.date), 0),
                COALESCE(MAX(h.date), 0),
                ?
            FROM stock s
            LEFT JOIN historystock h ON h.code = s.code
            GROUP BY s.code, s.name
            ON CONFLICT(code) DO UPDATE SET
                name = excluded.name,
                status = excluded.status,
                source = excluded.source,
                rowCount = excluded.rowCount,
                message = excluded.message,
                startDate = excluded.startDate,
                endDate = excluded.endDate,
                updatedAt = excluded.updatedAt
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, source)
            ps.setInt(2, updatedAt)
            ps.executeUpdate()
        }
    }

    fun syncStatus(): SyncStatus = connection().use { conn ->
        SyncStatus(
            database = path(),
            stockCount = tableCount(conn, "stock"),
            historyCount = tableCount(conn, "historystock"),
            lastStockSyncDate = getMeta(conn, "last_stock_sync_date")?.toIntOrNull(),
            lastStockSyncTime = getMeta(conn, "last_stock_sync_time")?.toLongOrNull(),
            lastStockSyncCount = getMeta(conn, "last_stock_sync_count")?.toIntOrNull(),
            lastStockSyncSource = getMeta(conn, "last_stock_sync_source"),
            lastStockSyncSlot = getMeta(conn, "last_stock_sync_slot")
        )
    }

    fun historySyncStatus(): HistorySyncStatus = connection().use { conn ->
        HistorySyncStatus(
            database = path(),
            historyCount = tableCount(conn, "historystock"),
            pendingHistorySyncCount = pendingHistorySyncCount(conn, currentHistoryTargetDate()),
            lastHistorySyncTime = getMeta(conn, "last_history_sync_time")?.toLongOrNull(),
            lastHistorySyncDate = getMeta(conn, "last_history_sync_date")?.toIntOrNull(),
            lastHistorySyncCount = getMeta(conn, "last_history_sync_count")?.toIntOrNull(),
            lastHistorySyncStockCount = getMeta(conn, "last_history_sync_stock_count")?.toIntOrNull(),
            lastHistorySyncRequestedStockCount = getMeta(conn, "last_history_sync_requested_stock_count")?.toIntOrNull(),
            lastHistorySyncSource = getMeta(conn, "last_history_sync_source")
        )
    }

    fun monitorConfig(): MonitorConfig = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM monitor_config WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use defaultMonitorConfig()
                MonitorConfig(
                    enabled = rs.getInt("enabled") == 1,
                    targetSources = csvToList(rs.getString("targetSources")).ifEmpty { defaultMonitorConfig().targetSources },
                    customCodes = csvToList(rs.getString("customCodes")).mapNotNull { normalizeCode(it) },
                    customBkCodes = csvToList(runCatching { rs.getString("customBkCodes") }.getOrNull()),
                    cooldownSeconds = rs.getInt("cooldownSeconds").coerceIn(5, 3600),
                    tradingTimeOnly = rs.getInt("tradingTimeOnly") == 1,
                    browserNotifyEnabled = rs.getInt("browserNotifyEnabled") == 1,
                    updatedAt = rs.getLong("updatedAt")
                )
            }
        }
    }

    fun saveMonitorConfig(request: MonitorConfigRequest): MonitorConfig {
        val current = monitorConfig()
        val next = current.copy(
            enabled = request.enabled ?: current.enabled,
            targetSources = request.targetSources?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?: current.targetSources,
            customCodes = request.customCodes?.mapNotNull { normalizeCode(it) }?.distinct() ?: current.customCodes,
            customBkCodes = request.customBkCodes?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?: current.customBkCodes,
            cooldownSeconds = request.cooldownSeconds?.coerceIn(5, 3600) ?: current.cooldownSeconds,
            tradingTimeOnly = request.tradingTimeOnly ?: current.tradingTimeOnly,
            browserNotifyEnabled = request.browserNotifyEnabled ?: current.browserNotifyEnabled,
            updatedAt = System.currentTimeMillis()
        )
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO monitor_config (
                    id, enabled, targetSources, customCodes, cooldownSeconds,
                    tradingTimeOnly, browserNotifyEnabled, updatedAt, customBkCodes
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    enabled = excluded.enabled,
                    targetSources = excluded.targetSources,
                    customCodes = excluded.customCodes,
                    cooldownSeconds = excluded.cooldownSeconds,
                    tradingTimeOnly = excluded.tradingTimeOnly,
                    browserNotifyEnabled = excluded.browserNotifyEnabled,
                    updatedAt = excluded.updatedAt,
                    customBkCodes = excluded.customBkCodes
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, if (next.enabled) 1 else 0)
                ps.setString(2, next.targetSources.joinToString(","))
                ps.setString(3, next.customCodes.joinToString(","))
                ps.setInt(4, next.cooldownSeconds)
                ps.setInt(5, if (next.tradingTimeOnly) 1 else 0)
                ps.setInt(6, if (next.browserNotifyEnabled) 1 else 0)
                ps.setLong(7, next.updatedAt)
                ps.setString(8, next.customBkCodes.joinToString(","))
                ps.executeUpdate()
            }
        }
        return next
    }

    fun upsertLimitUpPool(items: List<LimitUpPoolItem>, source: String): SyncWriteResult {
        if (items.isEmpty()) return SyncWriteResult(currentHistoryTargetDate(), 0, source, "no limit-up rows")
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO limit_up_pool (
                        date, code, name, firstLimitUpTime, lastLimitUpTime, highDays,
                        limitUpType, reasonType, openNum, turnoverRate, latest, changeRate
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(date, code) DO UPDATE SET
                        name = excluded.name,
                        firstLimitUpTime = excluded.firstLimitUpTime,
                        lastLimitUpTime = excluded.lastLimitUpTime,
                        highDays = excluded.highDays,
                        limitUpType = excluded.limitUpType,
                        reasonType = excluded.reasonType,
                        openNum = excluded.openNum,
                        turnoverRate = excluded.turnoverRate,
                        latest = excluded.latest,
                        changeRate = excluded.changeRate
                    """.trimIndent()
                ).use { ps ->
                    items.forEach { item ->
                        ps.setInt(1, item.date)
                        ps.setString(2, item.code)
                        ps.setString(3, item.name)
                        ps.setLong(4, item.firstLimitUpTime)
                        ps.setString(5, item.lastLimitUpTime)
                        ps.setString(6, item.highDays)
                        ps.setString(7, item.limitUpType)
                        ps.setString(8, item.reasonType)
                        ps.setInt(9, item.openNum)
                        ps.setDouble(10, item.turnoverRate)
                        ps.setDouble(11, item.latest)
                        ps.setDouble(12, item.changeRate)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                val date = items.maxOf { it.date }
                setMeta(conn, "last_limit_up_pool_date", date.toString())
                setMeta(conn, "last_limit_up_pool_count", items.size.toString())
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return SyncWriteResult(items.maxOf { it.date }, items.size, source, "limit-up pool synced")
    }

    fun upsertZtReplay(items: List<ZtReplayItem>, source: String): SyncWriteResult {
        if (items.isEmpty()) return SyncWriteResult(currentHistoryTargetDate(), 0, source, "no replay rows")
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO zt_replay (
                        date, code, reason, groupName, expound, time,
                        groupName2, reason2, expound2, expound3
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(date, code) DO UPDATE SET
                        reason = excluded.reason,
                        groupName = excluded.groupName,
                        expound = excluded.expound,
                        time = excluded.time,
                        groupName2 = excluded.groupName2,
                        reason2 = excluded.reason2,
                        expound2 = excluded.expound2,
                        expound3 = excluded.expound3
                    """.trimIndent()
                ).use { ps ->
                    items.forEach { item ->
                        ps.setInt(1, item.date)
                        ps.setString(2, item.code)
                        ps.setString(3, item.reason)
                        ps.setString(4, item.groupName)
                        ps.setString(5, item.expound)
                        ps.setString(6, item.time)
                        ps.setString(7, item.groupName2)
                        ps.setString(8, item.reason2)
                        ps.setString(9, item.expound2)
                        ps.setString(10, item.expound3)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                val date = items.maxOf { it.date }
                setMeta(conn, "last_zt_replay_date", date.toString())
                setMeta(conn, "last_zt_replay_count", items.size.toString())
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return SyncWriteResult(items.maxOf { it.date }, items.size, source, "zt replay synced")
    }

    fun monitorTargets(config: MonitorConfig = monitorConfig(), date: Int = currentRealtimeSnapshotDate()): MonitorTargetsResponse {
        val sourcesByCode = linkedMapOf<String, MutableSet<String>>()
        val namesByCode = linkedMapOf<String, String>()
        connection().use { conn ->
            val limitUpReferenceDate = effectiveYesterdayLimitUpDate(conn, date)
            if ("follow_stock" in config.targetSources) {
                addFollowStockTargets(conn, sourcesByCode, namesByCode)
            }
            if ("follow_bk" in config.targetSources) {
                addFollowBkTargets(conn, config.customBkCodes, sourcesByCode, namesByCode)
            }
            val useMergedLimitUp = "limit_up" in config.targetSources
            if (useMergedLimitUp || "limit_up_today" in config.targetSources) {
                addLimitUpTargets(conn, date, "今日涨停池", sourcesByCode, namesByCode)
            }
            if (useMergedLimitUp || "limit_up_yesterday" in config.targetSources) {
                if (limitUpReferenceDate != null && limitUpReferenceDate != date) {
                    addLimitUpTargets(conn, limitUpReferenceDate, "昨日涨停", sourcesByCode, namesByCode)
                }
            }
            config.customCodes.forEach { code ->
                namesByCode[code] = stockName(conn, code).ifBlank { code }
                sourcesByCode.getOrPut(code) { linkedSetOf() }.add("自选")
            }
            if ("unusual_today" in config.targetSources) {
                addUnusualTargets(conn, date, sourcesByCode, namesByCode)
            }
            val targets = sourcesByCode.map { (code, sources) ->
                MonitorTarget(
                    code = code,
                    name = namesByCode[code].orEmpty().ifBlank { code },
                    sources = sources.toList(),
                    limitUp = limitUpPool(conn, date, code) ?: limitUpReferenceDate?.let { limitUpPool(conn, it, code) },
                    replay = ztReplay(conn, date, code) ?: limitUpReferenceDate?.let { ztReplay(conn, it, code) }
                )
            }.sortedBy { it.code }
            return MonitorTargetsResponse(date = date, targets = targets)
        }
    }

    fun insertMonitorAlert(alert: AlertEvent): AlertEvent {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO monitor_alert (
                    code, name, title, content, price, chg, eventTypes, reasons,
                    sources, replayJson, triggeredAt, createdAt, snapshotJson
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, alert.code)
                ps.setString(2, alert.name)
                ps.setString(3, alert.title)
                ps.setString(4, alert.content)
                ps.setDouble(5, alert.price)
                ps.setDouble(6, alert.chg)
                ps.setString(7, alert.eventTypes.joinToString(","))
                ps.setString(8, alert.reasons.joinToString(" | "))
                ps.setString(9, alert.sources.joinToString(","))
                ps.setString(10, alert.replay?.let { json.encodeToString(it) }.orEmpty())
                ps.setLong(11, alert.time)
                ps.setLong(12, System.currentTimeMillis())
                ps.setString(13, json.encodeToString(alert))
                ps.executeUpdate()
            }
        }
        return alert
    }

    fun upsertMonitorFollow(request: MonitorFollowRequest): MonitorTarget {
        val code = if (request.type == 1) normalizeCode(request.code) ?: request.code.trim() else request.code.trim()
        require(code.isNotBlank()) { "code is required" }
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO monitor_follow (code, type, name, stickyOnTop, color, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(code, type) DO UPDATE SET
                    name = excluded.name,
                    stickyOnTop = excluded.stickyOnTop,
                    color = excluded.color,
                    updatedAt = excluded.updatedAt
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, code)
                ps.setInt(2, request.type)
                ps.setString(3, request.name)
                ps.setInt(4, request.stickyOnTop)
                ps.setInt(5, request.color)
                ps.setLong(6, System.currentTimeMillis())
                ps.executeUpdate()
            }
            return MonitorTarget(
                code = code,
                name = request.name.ifBlank { if (request.type == 1) stockName(conn, code).ifBlank { code } else bkName(conn, code).ifBlank { code } },
                sources = listOf(if (request.type == 1) "自选" else "自选板块")
            )
        }
    }

    fun monitorFollows(): List<MonitorFollowItem> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT
                f.code,
                f.type,
                COALESCE(NULLIF(f.name, ''), CASE WHEN f.type = 1 THEN s.name ELSE b.name END, f.code) AS name,
                f.stickyOnTop,
                f.color,
                f.updatedAt
            FROM monitor_follow f
            LEFT JOIN stock s ON s.code = f.code AND f.type = 1
            LEFT JOIN bk b ON b.code = f.code AND f.type = 2
            ORDER BY f.type ASC, f.stickyOnTop DESC, f.updatedAt DESC, f.code ASC
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MonitorFollowItem(
                                code = rs.getString("code"),
                                type = rs.getInt("type"),
                                name = rs.getString("name"),
                                stickyOnTop = rs.getInt("stickyOnTop"),
                                color = rs.getInt("color"),
                                updatedAt = rs.getLong("updatedAt")
                            )
                        )
                    }
                }
            }
        }
    }

    fun removeMonitorFollow(code: String, type: Int): Int {
        return connection().use { conn ->
            conn.prepareStatement("DELETE FROM monitor_follow WHERE code = ? AND type = ?").use { ps ->
                ps.setString(1, code.trim())
                ps.setInt(2, type)
                ps.executeUpdate()
            }
        }
    }

    fun upsertBkStocks(request: MonitorBkStocksRequest): SyncWriteResult {
        val bkCode = request.bkCode.trim()
        require(bkCode.isNotBlank()) { "bkCode is required" }
        val codes = request.stockCodes.mapNotNull { normalizeCode(it) }.distinct()
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO bk (code, name, updatedAt) VALUES (?, ?, ?)
                    ON CONFLICT(code) DO UPDATE SET name = excluded.name, updatedAt = excluded.updatedAt
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, bkCode)
                    ps.setString(2, request.bkName.ifBlank { bkCode })
                    ps.setLong(3, System.currentTimeMillis())
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM bk_stock WHERE bkCode = ?").use { ps ->
                    ps.setString(1, bkCode)
                    ps.executeUpdate()
                }
                conn.prepareStatement("INSERT OR REPLACE INTO bk_stock (bkCode, stockCode) VALUES (?, ?)").use { ps ->
                    codes.forEach { code ->
                        ps.setString(1, bkCode)
                        ps.setString(2, code)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
        return SyncWriteResult(currentHistoryTargetDate(), codes.size, "manual", "bk stocks saved")
    }

    fun insertUnusualAction(request: MonitorUnusualRequest): SyncWriteResult {
        val codes = request.stocks.split(',').mapNotNull { normalizeCode(it) }.distinct()
        require(codes.isNotEmpty()) { "stocks is required" }
        val type = request.type.coerceIn(1, 6)
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO unusual_action_history (time, type, comment, stocks, bk)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(time, type) DO UPDATE SET
                    comment = excluded.comment,
                    stocks = excluded.stocks,
                    bk = excluded.bk
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, request.time)
                ps.setInt(2, type)
                ps.setString(3, request.comment)
                ps.setString(4, codes.joinToString(","))
                ps.setString(5, request.bk)
                ps.executeUpdate()
            }
        }
        return SyncWriteResult(currentHistoryTargetDate(), codes.size, "manual", "unusual action saved")
    }

    fun upsertUnusualActions(items: List<MonitorUnusualRequest>, source: String): SyncWriteResult {
        val rows = items.mapNotNull { item ->
            val codes = item.stocks.split(',').mapNotNull { normalizeCode(it) }.distinct()
            if (codes.isEmpty() || item.time <= 0) null else item.copy(stocks = codes.joinToString(","))
        }.distinctBy { it.time to it.type }
        if (rows.isEmpty()) return SyncWriteResult(currentRealtimeSnapshotDate(), 0, source, "no unusual rows")
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO unusual_action_history (time, type, comment, stocks, bk)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(time, type) DO UPDATE SET
                    comment = excluded.comment,
                    stocks = excluded.stocks,
                    bk = excluded.bk
                """.trimIndent()
            ).use { ps ->
                rows.forEach { row ->
                    ps.setLong(1, row.time)
                    ps.setInt(2, row.type.coerceIn(1, 6))
                    ps.setString(3, row.comment)
                    ps.setString(4, row.stocks)
                    ps.setString(5, row.bk)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return SyncWriteResult(currentRealtimeSnapshotDate(), rows.size, source, "unusual synced")
    }

    fun monitorAlerts(code: String? = null, date: Int? = null, limit: Int = 100): List<AlertEvent> {
        val safeLimit = limit.coerceIn(1, 500)
        val startEnd = date?.let { epochMillisRangeForDate(it) }
        val where = buildList {
            if (!code.isNullOrBlank()) add("code = ?")
            if (startEnd != null) add("triggeredAt >= ? AND triggeredAt <= ?")
        }.joinToString(" AND ").ifBlank { "1 = 1" }
        return connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT *
                FROM monitor_alert
                WHERE $where
                ORDER BY triggeredAt DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                var i = 1
                if (!code.isNullOrBlank()) ps.setString(i++, code)
                if (startEnd != null) {
                    ps.setLong(i++, startEnd.first)
                    ps.setLong(i++, startEnd.second)
                }
                ps.setInt(i, safeLimit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val replayJson = rs.getString("replayJson").orEmpty()
                            add(
                                AlertEvent(
                                    code = rs.getString("code"),
                                    name = rs.getString("name"),
                                    title = rs.getString("title"),
                                    content = rs.getString("content"),
                                    price = rs.getDouble("price"),
                                    chg = rs.getDouble("chg"),
                                    time = rs.getLong("triggeredAt"),
                                    reasons = rs.getString("reasons").orEmpty().split(" | ").filter { it.isNotBlank() },
                                    sources = csvToList(rs.getString("sources")),
                                    eventTypes = csvToList(rs.getString("eventTypes")),
                                    replay = replayJson.takeIf { it.isNotBlank() }?.let {
                                        runCatching { json.decodeFromString<ZtReplayItem>(it) }.getOrNull()
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun monitorAlertCount(): Int = connection().use { conn -> tableCount(conn, "monitor_alert") }

    private fun pendingHistorySyncCount(conn: Connection, endDate: Int): Int {
        return conn.prepareStatement(
            """
            SELECT COUNT(*)
            FROM stock s
            LEFT JOIN history_sync_result r
                ON r.code = s.code
            WHERE r.code IS NULL
                OR r.status <> 'SUCCESS'
                OR r.rowCount <= 0
                OR r.endDate < ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, endDate)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun currentHistoryTargetDate(): Int {
        return ChinaMarketCalendar.currentHistoryTargetDate(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
    }

    private fun currentRealtimeSnapshotDate(): Int {
        return ChinaMarketCalendar.currentRealtimeSnapshotDate(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
    }

    fun tableNames(): List<String> = listOf(
        "stock",
        "historystock",
        "history_sync_result",
        "limit_up_pool",
        "zt_replay",
        "monitor_follow",
        "bk",
        "bk_stock",
        "unusual_action_history",
        "monitor_config",
        "monitor_alert",
        "sync_meta"
    )

    fun dailyLines(code: String, limit: Int): DailyLineResponse = connection().use { conn ->
        val safeLimit = limit.coerceIn(1, 500)
        val stockName = conn.prepareStatement("SELECT name FROM stock WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else "" }
        }
        conn.prepareStatement(
            """
            SELECT date, closePrice, openPrice, highest, lowest, chg, averagePrice
            FROM historystock
            WHERE code = ?
            ORDER BY date DESC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, code)
            ps.setInt(2, safeLimit)
            ps.executeQuery().use { rs ->
                val rows = buildList {
                    while (rs.next()) {
                        add(
                            DailyLine(
                                date = rs.getInt("date"),
                                closePrice = rs.getDouble("closePrice"),
                                openPrice = rs.getDouble("openPrice"),
                                highest = rs.getDouble("highest"),
                                lowest = rs.getDouble("lowest"),
                                chg = rs.getDouble("chg"),
                                averagePrice = rs.getDouble("averagePrice")
                            )
                        )
                    }
                }.asReversed()
                DailyLineResponse(code = code, name = stockName, lines = rows)
            }
        }
    }

    fun queryTable(name: String, limit: Int, offset: Int): DbTable {
        require(name in tableNames()) { "Unsupported table: $name" }
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = max(0, offset)
        return connection().use { conn ->
            val total = tableCount(conn, name)
            val orderBy = when (name) {
                "historystock" -> " ORDER BY date DESC, code ASC"
                "history_sync_result" -> " ORDER BY endDate DESC, code ASC"
                "limit_up_pool" -> " ORDER BY date DESC, firstLimitUpTime ASC, code ASC"
                "zt_replay" -> " ORDER BY date DESC, time ASC, code ASC"
                "monitor_follow" -> " ORDER BY type ASC, stickyOnTop DESC, updatedAt DESC"
                "bk" -> " ORDER BY code ASC"
                "bk_stock" -> " ORDER BY bkCode ASC, stockCode ASC"
                "unusual_action_history" -> " ORDER BY time DESC, type ASC"
                "monitor_alert" -> " ORDER BY triggeredAt DESC, id DESC"
                "stock" -> " ORDER BY code ASC"
                "sync_meta" -> " ORDER BY key ASC"
                else -> ""
            }
            conn.prepareStatement("SELECT * FROM $name$orderBy LIMIT ? OFFSET ?").use { ps ->
                ps.setInt(1, safeLimit)
                ps.setInt(2, safeOffset)
                ps.executeQuery().use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    val rows = buildList {
                        while (rs.next()) {
                            add(columns.associateWith { column -> rs.getObject(column)?.toString() ?: "" })
                        }
                    }
                    DbTable(name, total, columns, rows)
                }
            }
        }
    }

    fun path(): String = dbPath.toAbsolutePath().toString()

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl)

    private fun tableCount(conn: Connection, table: String): Int {
        return conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun distinctHistoryStockCount(conn: Connection): Int {
        return conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(DISTINCT code) FROM historystock").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun addLimitUpTargets(
        conn: Connection,
        date: Int,
        source: String,
        sourcesByCode: MutableMap<String, MutableSet<String>>,
        namesByCode: MutableMap<String, String>
    ) {
        conn.prepareStatement("SELECT code, name FROM limit_up_pool WHERE date = ? ORDER BY firstLimitUpTime ASC").use { ps ->
            ps.setInt(1, date)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val code = rs.getString("code")
                    namesByCode[code] = rs.getString("name")
                    sourcesByCode.getOrPut(code) { linkedSetOf() }.add(source)
                }
            }
        }
    }

    private fun addFollowStockTargets(
        conn: Connection,
        sourcesByCode: MutableMap<String, MutableSet<String>>,
        namesByCode: MutableMap<String, String>
    ) {
        conn.prepareStatement(
            """
            SELECT f.code, COALESCE(NULLIF(f.name, ''), s.name, f.code) AS name
            FROM monitor_follow f
            LEFT JOIN stock s ON s.code = f.code
            WHERE f.type = 1
            ORDER BY f.stickyOnTop DESC, f.updatedAt DESC, f.code ASC
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val code = rs.getString("code")
                    namesByCode[code] = rs.getString("name")
                    sourcesByCode.getOrPut(code) { linkedSetOf() }.add("自选")
                }
            }
        }
    }

    private fun addFollowBkTargets(
        conn: Connection,
        customBkCodes: List<String>,
        sourcesByCode: MutableMap<String, MutableSet<String>>,
        namesByCode: MutableMap<String, String>
    ) {
        val bkCodes = linkedSetOf<String>()
        conn.prepareStatement("SELECT code FROM monitor_follow WHERE type = 2 ORDER BY updatedAt DESC").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) bkCodes.add(rs.getString("code"))
            }
        }
        bkCodes.addAll(customBkCodes)
        bkCodes.forEach { bkCode ->
            val bkLabel = bkName(conn, bkCode).ifBlank { bkCode }
            conn.prepareStatement(
                """
                SELECT bs.stockCode, COALESCE(s.name, bs.stockCode) AS name
                FROM bk_stock bs
                LEFT JOIN stock s ON s.code = bs.stockCode
                WHERE bs.bkCode = ?
                ORDER BY bs.stockCode ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, bkCode)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val code = rs.getString("stockCode")
                        namesByCode[code] = rs.getString("name")
                        sourcesByCode.getOrPut(code) { linkedSetOf() }.add("板块:$bkLabel")
                    }
                }
            }
        }
    }

    private fun addUnusualTargets(
        conn: Connection,
        date: Int,
        sourcesByCode: MutableMap<String, MutableSet<String>>,
        namesByCode: MutableMap<String, String>
    ) {
        val range = epochSecondsRangeForDate(date)
        conn.prepareStatement(
            """
            SELECT stocks, type, bk
            FROM unusual_action_history
            WHERE time >= ? AND time <= ? AND type IN (1, 2, 3, 4)
            ORDER BY time DESC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, range.first)
            ps.setLong(2, range.second)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val label = when (rs.getInt("type")) {
                        2 -> "异动:财联社"
                        3 -> "异动:开盘啦"
                        4 -> "异动:同花顺"
                        else -> "异动"
                    }
                    csvToList(rs.getString("stocks")).mapNotNull { normalizeCode(it) }.forEach { code ->
                        namesByCode[code] = stockName(conn, code).ifBlank { code }
                        sourcesByCode.getOrPut(code) { linkedSetOf() }.add(label)
                    }
                }
            }
        }
    }

    private fun previousLimitUpDate(conn: Connection, beforeOrAtDate: Int): Int? {
        return conn.prepareStatement("SELECT MAX(date) FROM limit_up_pool WHERE date < ?").use { ps ->
            ps.setInt(1, beforeOrAtDate)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() && it > 0 } else null
            }
        }
    }

    private fun effectiveYesterdayLimitUpDate(conn: Connection, date: Int): Int? {
        val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
        val inTradingSession = ChinaMarketCalendar.isTradingDay(now.toLocalDate()) &&
            !now.toLocalTime().isBefore(LocalTime.of(9, 15)) &&
            now.toLocalTime().isBefore(LocalTime.of(15, 0))
        if (!inTradingSession && hasLimitUpPool(conn, date)) return date
        return previousLimitUpDate(conn, date)
    }

    private fun hasLimitUpPool(conn: Connection, date: Int): Boolean {
        return conn.prepareStatement("SELECT 1 FROM limit_up_pool WHERE date = ? LIMIT 1").use { ps ->
            ps.setInt(1, date)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun stockName(conn: Connection, code: String): String {
        return conn.prepareStatement("SELECT name FROM stock WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else "" }
        }
    }

    private fun limitUpPool(conn: Connection, date: Int, code: String): LimitUpPoolItem? {
        return conn.prepareStatement("SELECT * FROM limit_up_pool WHERE date = ? AND code = ? LIMIT 1").use { ps ->
            ps.setInt(1, date)
            ps.setString(2, code)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                LimitUpPoolItem(
                    date = rs.getInt("date"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    firstLimitUpTime = rs.getLong("firstLimitUpTime"),
                    lastLimitUpTime = rs.getString("lastLimitUpTime"),
                    highDays = rs.getString("highDays"),
                    limitUpType = rs.getString("limitUpType"),
                    reasonType = rs.getString("reasonType"),
                    openNum = rs.getInt("openNum"),
                    turnoverRate = rs.getDouble("turnoverRate"),
                    latest = rs.getDouble("latest"),
                    changeRate = rs.getDouble("changeRate")
                )
            }
        }
    }

    private fun bkName(conn: Connection, code: String): String {
        return conn.prepareStatement("SELECT name FROM bk WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else "" }
        }
    }

    private fun ztReplay(conn: Connection, date: Int, code: String): ZtReplayItem? {
        return conn.prepareStatement("SELECT * FROM zt_replay WHERE date = ? AND code = ? LIMIT 1").use { ps ->
            ps.setInt(1, date)
            ps.setString(2, code)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                ZtReplayItem(
                    date = rs.getInt("date"),
                    code = rs.getString("code"),
                    reason = rs.getString("reason"),
                    groupName = rs.getString("groupName"),
                    expound = rs.getString("expound"),
                    time = rs.getString("time"),
                    groupName2 = rs.getString("groupName2"),
                    reason2 = rs.getString("reason2"),
                    expound2 = rs.getString("expound2"),
                    expound3 = rs.getString("expound3")
                )
            }
        }
    }

    private fun defaultMonitorConfig(): MonitorConfig = MonitorConfig()

    private fun csvToList(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalizeCode(value: String): String? =
        Regex("\\d{6}").find(value)?.value

    private fun epochMillisRangeForDate(date: Int): Pair<Long, Long> {
        val day = LocalDate.parse(date.toString(), DateTimeFormatter.BASIC_ISO_DATE)
        val start = day.atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(CHINA_ZONE).toInstant().toEpochMilli() - 1
        return start to end
    }

    private fun epochSecondsRangeForDate(date: Int): Pair<Long, Long> {
        val millis = epochMillisRangeForDate(date)
        return millis.first / 1000 to millis.second / 1000
    }

    private fun migrateHistorySyncResultTable(conn: Connection) {
        val columns = conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(history_sync_result)").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString("name"))
                }
            }
        }
        if ("targetDate" !in columns && "startedAt" !in columns && "endedAt" !in columns &&
            "startDate" in columns && "endDate" in columns
        ) return

        conn.createStatement().use { statement ->
            statement.executeUpdate("DROP TABLE IF EXISTS history_sync_result_new")
            statement.executeUpdate(
                """
                CREATE TABLE history_sync_result_new (
                    code TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    source TEXT NOT NULL,
                    rowCount INTEGER NOT NULL,
                    message TEXT NOT NULL DEFAULT '',
                    startDate INTEGER NOT NULL,
                    endDate INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val hasOldRows = tableCount(conn, "history_sync_result") > 0
            if (hasOldRows) {
                statement.executeUpdate(
                    """
                    INSERT INTO history_sync_result_new (
                        code, name, status, source, rowCount, message, startDate, endDate, updatedAt
                    )
                    SELECT
                        r.code,
                        r.name,
                        r.status,
                        r.source,
                        r.rowCount,
                        r.message,
                        COALESCE((SELECT MIN(h.date) FROM historystock h WHERE h.code = r.code), 0),
                        COALESCE((SELECT MAX(h.date) FROM historystock h WHERE h.code = r.code), r.targetDate, 0),
                        COALESCE(r.updatedAt, r.endedAt, r.targetDate, 0)
                    FROM history_sync_result r
                    """.trimIndent()
                )
            }
            statement.executeUpdate("DROP TABLE history_sync_result")
            statement.executeUpdate("ALTER TABLE history_sync_result_new RENAME TO history_sync_result")
        }
    }

    private fun seedCurrentStocks(conn: Connection, seeds: List<StockSeed>) {
        seeds.forEach { seed ->
            updateDailyStock(conn, seed, seed.tick(seed.baseChg))
        }
    }

    private fun updateDailyStock(conn: Connection, stock: SyncedStock, preserveExistingBk: Boolean) {
        conn.prepareStatement(
            """
            INSERT INTO stock (
                code, name, price, chg, amplitude, turnoverRate, highest, lowest,
                circulationMarketValue, toMarketTime, openPrice, yesterdayClosePrice,
                ztPrice, dtPrice, averagePrice, bk
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                name = excluded.name,
                price = excluded.price,
                chg = excluded.chg,
                amplitude = excluded.amplitude,
                turnoverRate = excluded.turnoverRate,
                highest = excluded.highest,
                lowest = excluded.lowest,
                circulationMarketValue = excluded.circulationMarketValue,
                toMarketTime = excluded.toMarketTime,
                openPrice = excluded.openPrice,
                yesterdayClosePrice = excluded.yesterdayClosePrice,
                ztPrice = excluded.ztPrice,
                dtPrice = excluded.dtPrice,
                averagePrice = excluded.averagePrice,
                bk = CASE
                    WHEN ? THEN CASE WHEN bk <> '' THEN bk ELSE excluded.bk END
                    ELSE excluded.bk
                END
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, stock.code)
            ps.setString(2, stock.name)
            ps.setDouble(3, stock.price)
            ps.setDouble(4, stock.chg)
            ps.setDouble(5, stock.amplitude)
            ps.setDouble(6, stock.turnoverRate)
            ps.setDouble(7, stock.highest)
            ps.setDouble(8, stock.lowest)
            ps.setDouble(9, stock.circulationMarketValue)
            ps.setInt(10, stock.toMarketTime)
            ps.setDouble(11, stock.openPrice)
            ps.setDouble(12, stock.yesterdayClosePrice)
            ps.setDouble(13, stock.ztPrice)
            ps.setDouble(14, stock.dtPrice)
            ps.setDouble(15, stock.averagePrice)
            ps.setString(16, stock.bk)
            ps.setBoolean(17, preserveExistingBk)
            ps.executeUpdate()
        }
    }

    private fun seedHistoryStocks(conn: Connection, seeds: List<StockSeed>) {
        val tradingDays = recentTradingDaysInRetentionWindow()
        seeds.forEachIndexed { seedIndex, seed ->
            var previousClose = seed.yesterdayClose * (1 - tradingDays.size * 0.0008)
            tradingDays.forEachIndexed { index, date ->
                val trend = sin((index + seedIndex * 6) / 9.0) * 1.4
                val noise = Random(seed.code.hashCode() * 31 + index).nextDouble(-0.75, 0.75)
                val chg = percent((trend + noise).coerceIn(-8.8, 9.6))
                val close = money(previousClose * (1 + chg / 100))
                val open = money(previousClose * (1 + Random(seed.code.hashCode() + index).nextDouble(-0.018, 0.018)))
                val high = money(max(open, close) * (1 + Random(index + seedIndex).nextDouble(0.002, 0.026)))
                val low = money(min(open, close) * (1 - Random(index * 7 + seedIndex).nextDouble(0.002, 0.024)))
                val amplitude = percent(((high - low) / previousClose) * 100)
                val turnover = percent(1.2 + Random(seed.code.hashCode() - index).nextDouble(0.0, 5.5))
                val average = money((open + close + high + low) / 4)
                upsertHistory(
                    conn = conn,
                    code = seed.code,
                    date = date,
                    closePrice = close,
                    openPrice = open,
                    highest = high,
                    lowest = low,
                    chg = chg,
                    amplitude = amplitude,
                    turnoverRate = turnover,
                    ztPrice = money(previousClose * (1 + seed.limitRate)),
                    dtPrice = money(previousClose * (1 - seed.limitRate)),
                    yesterdayClosePrice = money(previousClose),
                    averagePrice = average
                )
                previousClose = close
            }
        }
    }

    private fun updateDailyStock(conn: Connection, seed: StockSeed, tick: StockTick) {
        conn.prepareStatement(
            """
            INSERT INTO stock (
                code, name, price, chg, amplitude, turnoverRate, highest, lowest,
                circulationMarketValue, toMarketTime, openPrice, yesterdayClosePrice,
                ztPrice, dtPrice, averagePrice, bk
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                name = excluded.name,
                price = excluded.price,
                chg = excluded.chg,
                amplitude = excluded.amplitude,
                turnoverRate = excluded.turnoverRate,
                highest = excluded.highest,
                lowest = excluded.lowest,
                circulationMarketValue = excluded.circulationMarketValue,
                toMarketTime = excluded.toMarketTime,
                openPrice = excluded.openPrice,
                yesterdayClosePrice = excluded.yesterdayClosePrice,
                ztPrice = excluded.ztPrice,
                dtPrice = excluded.dtPrice,
                averagePrice = excluded.averagePrice,
                bk = excluded.bk
            """.trimIndent()
        ).use { ps ->
            val open = money(seed.yesterdayClose * (1 + (seed.baseChg / 2) / 100))
            val high = money(max(open, tick.price) * 1.012)
            val low = money(min(open, tick.price) * 0.992)
            val amplitude = percent(((high - low) / seed.yesterdayClose) * 100)
            val average = money((open + tick.price + high + low) / 4)
            ps.setString(1, seed.code)
            ps.setString(2, seed.name)
            ps.setDouble(3, tick.price)
            ps.setDouble(4, tick.chg)
            ps.setDouble(5, amplitude)
            ps.setDouble(6, percent(2.0 + kotlin.math.abs(tick.chg) / 2))
            ps.setDouble(7, high)
            ps.setDouble(8, low)
            ps.setDouble(9, seed.circulationMarketValue)
            ps.setInt(10, seed.toMarketTime)
            ps.setDouble(11, open)
            ps.setDouble(12, seed.yesterdayClose)
            ps.setDouble(13, seed.ztPrice)
            ps.setDouble(14, seed.dtPrice)
            ps.setDouble(15, average)
            ps.setString(16, seed.bk)
            ps.executeUpdate()
        }
    }

    private fun upsertTodayHistory(conn: Connection, seed: StockSeed, tick: StockTick) {
        val date = ChinaMarketCalendar.currentHistoryTargetDate()
        val open = money(seed.yesterdayClose * (1 + (seed.baseChg / 2) / 100))
        val high = money(max(open, tick.price) * 1.012)
        val low = money(min(open, tick.price) * 0.992)
        upsertHistory(
            conn = conn,
            code = seed.code,
            date = date,
            closePrice = tick.price,
            openPrice = open,
            highest = high,
            lowest = low,
            chg = tick.chg,
            amplitude = percent(((high - low) / seed.yesterdayClose) * 100),
            turnoverRate = percent(2.0 + kotlin.math.abs(tick.chg) / 2),
            ztPrice = seed.ztPrice,
            dtPrice = seed.dtPrice,
            yesterdayClosePrice = seed.yesterdayClose,
            averagePrice = money((open + tick.price + high + low) / 4)
        )
    }

    private fun upsertHistory(conn: Connection, history: SyncedHistoryStock) {
        upsertHistory(
            conn = conn,
            code = history.code,
            date = history.date,
            closePrice = history.closePrice,
            openPrice = history.openPrice,
            highest = history.highest,
            lowest = history.lowest,
            chg = history.chg,
            amplitude = history.amplitude,
            turnoverRate = history.turnoverRate,
            ztPrice = history.ztPrice,
            dtPrice = history.dtPrice,
            yesterdayClosePrice = history.yesterdayClosePrice,
            averagePrice = history.averagePrice
        )
    }

    private fun backfillHistorySyncResults(conn: Connection) {
        val now = LocalDate.now(CHINA_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
        conn.prepareStatement(
            """
            INSERT INTO history_sync_result (
                code, name, status, source, rowCount, message, startDate, endDate, updatedAt
            )
            SELECT
                h.code,
                COALESCE(s.name, ''),
                'SUCCESS',
                'Backfill',
                COUNT(*),
                '',
                MIN(h.date),
                MAX(h.date),
                ?
            FROM historystock h
            LEFT JOIN stock s ON s.code = h.code
            GROUP BY h.code
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, now)
            ps.executeUpdate()
        }
    }

    private fun reconcileHistorySyncResultCoverage(conn: Connection) {
        val today = LocalDate.now(CHINA_ZONE).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
        conn.createStatement().use { statement ->
            statement.executeUpdate(
                """
                DELETE FROM history_sync_result
                WHERE code NOT IN (SELECT code FROM stock)
                """.trimIndent()
            )
        }
        conn.prepareStatement(
            """
            INSERT INTO history_sync_result (
                code, name, status, source, rowCount, message, startDate, endDate, updatedAt
            )
            SELECT
                s.code,
                s.name,
                'PENDING',
                '',
                0,
                'pending history sync',
                0,
                0,
                ?
            FROM stock s
            LEFT JOIN history_sync_result r ON r.code = s.code
            WHERE r.code IS NULL
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, today)
            ps.executeUpdate()
        }
    }

    private fun upsertHistory(
        conn: Connection,
        code: String,
        date: Int,
        closePrice: Double,
        openPrice: Double,
        highest: Double,
        lowest: Double,
        chg: Double,
        amplitude: Double,
        turnoverRate: Double,
        ztPrice: Double,
        dtPrice: Double,
        yesterdayClosePrice: Double,
        averagePrice: Double
    ) {
        conn.prepareStatement(
            """
            INSERT INTO historystock (
                code, date, closePrice, openPrice, highest, lowest, chg, amplitude,
                turnoverRate, ztPrice, dtPrice, yesterdayClosePrice, averagePrice
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(code, date) DO UPDATE SET
                closePrice = excluded.closePrice,
                openPrice = excluded.openPrice,
                highest = excluded.highest,
                lowest = excluded.lowest,
                chg = excluded.chg,
                amplitude = excluded.amplitude,
                turnoverRate = excluded.turnoverRate,
                ztPrice = excluded.ztPrice,
                dtPrice = excluded.dtPrice,
                yesterdayClosePrice = excluded.yesterdayClosePrice,
                averagePrice = excluded.averagePrice
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, code)
            ps.setInt(2, date)
            ps.setDouble(3, closePrice)
            ps.setDouble(4, openPrice)
            ps.setDouble(5, highest)
            ps.setDouble(6, lowest)
            ps.setDouble(7, chg)
            ps.setDouble(8, amplitude)
            ps.setDouble(9, turnoverRate)
            ps.setDouble(10, ztPrice)
            ps.setDouble(11, dtPrice)
            ps.setDouble(12, yesterdayClosePrice)
            ps.setDouble(13, averagePrice)
            ps.executeUpdate()
        }
    }

    private fun recentTradingDaysInRetentionWindow(): List<Int> {
        val formatter = DateTimeFormatter.BASIC_ISO_DATE
        val days = ArrayDeque<Int>()
        val latest = latestClosedTradingDate()
        val oldest = latest.minusDays((DEFAULT_SEED_HISTORY_DAYS - 1).toLong())
        var date = latest
        while (!date.isBefore(oldest)) {
            if (ChinaMarketCalendar.isTradingDay(date)) {
                days.addFirst(date.format(formatter).toInt())
            }
            date = date.minusDays(1)
        }
        return days.toList()
    }

    private fun latestClosedTradingDate(): LocalDate {
        val target = ChinaMarketCalendar.currentHistoryTargetDate(LocalDateTime.now(CHINA_ZONE))
        return LocalDate.parse(target.toString(), DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun setMeta(conn: Connection, key: String, value: String) {
        conn.prepareStatement(
            """
            INSERT INTO sync_meta(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    private fun getMeta(conn: Connection, key: String): String? {
        return conn.prepareStatement("SELECT value FROM sync_meta WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("value") else null
            }
        }
    }

    companion object {
        private const val DEFAULT_SEED_HISTORY_DAYS = 210
        private val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

private fun defaultDatabasePath(): Path {
    System.getProperty("stockman.db.path")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    System.getenv("STOCKMAN_DB_PATH")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    val cwd = Path.of("").toAbsolutePath().normalize()
    return if (cwd.fileName?.toString() == "server") {
        cwd.resolve("data").resolve("stockman-monitor.db")
    } else {
        cwd.resolve("server").resolve("data").resolve("stockman-monitor.db")
    }
}

@kotlinx.serialization.Serializable
data class DbTable(
    val name: String,
    val total: Int,
    val columns: List<String>,
    val rows: List<Map<String, String>>
)

@kotlinx.serialization.Serializable
data class DbTables(
    val database: String,
    val tables: List<String>
)

@kotlinx.serialization.Serializable
data class SyncStatus(
    val database: String,
    val stockCount: Int,
    val historyCount: Int,
    val lastStockSyncDate: Int?,
    val lastStockSyncTime: Long?,
    val lastStockSyncCount: Int?,
    val lastStockSyncSource: String?,
    val lastStockSyncSlot: String?
)

@kotlinx.serialization.Serializable
data class DailyLine(
    val date: Int,
    val closePrice: Double,
    val openPrice: Double,
    val highest: Double,
    val lowest: Double,
    val chg: Double,
    val averagePrice: Double
)

@kotlinx.serialization.Serializable
data class DailyLineResponse(
    val code: String,
    val name: String,
    val lines: List<DailyLine>
)

@kotlinx.serialization.Serializable
data class HistorySyncStatus(
    val database: String,
    val historyCount: Int,
    val pendingHistorySyncCount: Int,
    val lastHistorySyncTime: Long?,
    val lastHistorySyncDate: Int?,
    val lastHistorySyncCount: Int?,
    val lastHistorySyncStockCount: Int?,
    val lastHistorySyncRequestedStockCount: Int?,
    val lastHistorySyncSource: String?
)

data class StockRef(
    val code: String,
    val name: String,
    val toMarketTime: Int
)

data class SyncedStock(
    val code: String,
    val name: String,
    val price: Double,
    val chg: Double,
    val amplitude: Double,
    val turnoverRate: Double,
    val highest: Double,
    val lowest: Double,
    val circulationMarketValue: Double,
    val toMarketTime: Int,
    val openPrice: Double,
    val yesterdayClosePrice: Double,
    val ztPrice: Double,
    val dtPrice: Double,
    val averagePrice: Double,
    val bk: String
) {
    fun toHistory(date: Int): SyncedHistoryStock = SyncedHistoryStock(
        code = code,
        date = date,
        closePrice = price,
        openPrice = openPrice,
        highest = highest,
        lowest = lowest,
        chg = chg,
        amplitude = amplitude,
        turnoverRate = turnoverRate,
        ztPrice = ztPrice,
        dtPrice = dtPrice,
        yesterdayClosePrice = yesterdayClosePrice,
        averagePrice = averagePrice
    )
}

data class SyncedHistoryStock(
    val code: String,
    val date: Int,
    val closePrice: Double,
    val openPrice: Double,
    val highest: Double,
    val lowest: Double,
    val chg: Double,
    val amplitude: Double,
    val turnoverRate: Double,
    val ztPrice: Double,
    val dtPrice: Double,
    val yesterdayClosePrice: Double,
    val averagePrice: Double
)

enum class HistoryCodeSyncStatus {
    SUCCESS,
    FAILED,
    PENDING
}

data class HistoryCodeSyncResult(
    val code: String,
    val name: String,
    val status: HistoryCodeSyncStatus,
    val source: String,
    val rowCount: Int,
    val message: String,
    val startDate: Int,
    val endDate: Int
)

@kotlinx.serialization.Serializable
data class HistorySyncProgress(
    val running: Boolean = false,
    val stopRequested: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val currentCode: String = "",
    val currentName: String = "",
    val currentSource: String = "",
    val lastMessage: String = "",
    val startedAt: Long? = null,
    val endedAt: Long? = null
)
