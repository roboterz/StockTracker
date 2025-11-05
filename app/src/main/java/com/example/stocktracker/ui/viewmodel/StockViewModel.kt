package com.example.stocktracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.database.PortfolioSettingsEntity
import com.example.stocktracker.data.database.StockDatabase
import com.example.stocktracker.data.database.StockNameEntity
import com.example.stocktracker.data.toEntity
import com.example.stocktracker.data.toUIModel
import com.example.stocktracker.scraper.YahooFinanceScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.UUID
import kotlin.math.absoluteValue

// ... (NavigationEvent, TimeRange, StockUiState remain the same) ...
sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

// *** 新增：图表时间范围的枚举 ***
enum class TimeRange {
    FIVE_DAY, ONE_MONTH, THREE_MONTH, SIX_MONTH, ONE_YEAR, FIVE_YEAR, ALL
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 0.0,
    val isRefreshing: Boolean = false,
    val portfolioName: String = "我的投资组合", // *** 新增：投资组合名称 ***
    val cashTransactions: List<CashTransaction> = emptyList(), // *** 新增：现金交易列表 ***
    val closedPositions: List<StockHolding> = emptyList(), // *** 新增：平仓列表 ***
    val cashTransactionToEditId: String? = null, // *** 新增：要编辑的现金交易ID ***

    // *** 新增：图表相关状态 ***
    val chartTimeRange: TimeRange = TimeRange.ONE_MONTH,
    val isChartLoading: Boolean = false,
    val portfolioChartData: List<ChartDataPoint> = emptyList()
) {
    // *** 新增：图表数据点的数据类 ***
    data class ChartDataPoint(val date: LocalDate, val value: Double)

    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId }
            ?: closedPositions.find { it.id == selectedStockId } // <-- 修复：同时搜索 closedPositions 列表
            ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }

    // *** 新增：获取要编辑的现金交易 ***
    val cashTransactionToEdit: CashTransaction?
        get() = cashTransactions.find { it.id == cashTransactionToEditId }
}


class StockViewModel(application: Application) : ViewModel() {
    // ... (property declarations and init block remain the same) ...
    private val appContext = application.applicationContext // *** 新增：获取应用上下文 ***
    private val db = StockDatabase.getDatabase(application)
    private val stockDao = db.stockDao()
    private val cashDao = db.cashDao()
    private val stockNameDao = db.stockNameDao()
    private val portfolioSettingsDao = db.portfolioSettingsDao() // *** 新增 DAO 引用 ***

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()


    private val _priceDataFlow = MutableStateFlow<Map<String, YahooFinanceScraper.ScrapedData>>(emptyMap())
    private var isInitialLoad = true

    // *** 新增：用于历史价格的缓存 ***
    private val historicalPriceCache = mutableMapOf<String, Map<LocalDate, Double>>()
    private var chartCalculationJob: Job? = null


    init {
        val holdingsFlow = stockDao.getAllStocksWithTransactions().map { list -> list.map { it.toUIModel() } }
// ... (init block logic remains the same) ...
        val cashFlow = cashDao.getAllCashTransactions().map { list -> list.map { it.toUIModel() } }
        val nameFlow = portfolioSettingsDao.getPortfolioName().map { it ?: "我的投资组合" } // *** 新增：投资组合名称流 ***

        viewModelScope.launch(Dispatchers.IO) {
            combine(holdingsFlow, cashFlow, _priceDataFlow, nameFlow) { holdingsFromDb, cashTransactions, priceDataMap, portfolioName ->
                val cashBalance = cashTransactions.sumOf {
                    if (it.type == CashTransactionType.DEPOSIT || it.type == CashTransactionType.SELL || it.type == CashTransactionType.DIVIDEND) it.amount else -it.amount
                }

                // ... (holdings calculation logic remains the same) ...
                // *** 1. 拆分活动持仓和已平仓位 ***
                //    活动持仓：当前数量 > 0
                //    已平仓位：当前数量 <= 0 并且 曾经有过交易 (总盈亏 != 0 或 总卖出 != 0)
                val activeHoldingsFromDb = holdingsFromDb.filter { it.totalQuantity > 0 }
                val closedPositionsFromDb = holdingsFromDb.filter {
                    it.totalQuantity <= 0 && (it.totalPL != 0.0 || it.totalSoldValue != 0.0)
                }.sortedByDescending { it.transactions.maxOfOrNull { t -> t.date } } // 按最后交易日期排序

                // *** 2. 只为活动持仓计算每日盈亏 ***
                val finalActiveHoldings = activeHoldingsFromDb.map { dbHolding ->
                    priceDataMap[dbHolding.id]?.let { prices ->
                        val today = LocalDate.now()

                        // 1. 获取昨日收盘时的持股数量 (隔夜仓位)
                        val overnightQuantity = dbHolding.getQuantityOnDate(today.minusDays(1))

                        // 2. 昨日收盘时的隔夜持仓总价值
                        val overnightValueAtClose = overnightQuantity * prices.previousClose

                        // 3. 计算当日交易的净现金投入（买入为正，卖出为负）。只计算 BUY/SELL 交易。
                        var netCashInvestedToday = 0.0
                        val todayTransactions = dbHolding.transactions.filter { it.date == today }

                        for (t in todayTransactions) {
                            when (t.type) {
                                TransactionType.BUY -> {
                                    // 买入: 现金流出 (投资增加)
                                    netCashInvestedToday += (t.quantity * t.price) + t.fee
                                }
                                TransactionType.SELL -> {
                                    // 卖出: 现金流入 (投资减少)
                                    netCashInvestedToday -= (t.quantity * t.price) - t.fee
                                }
                                else -> { /* 忽略 DIVIDEND/SPLIT */ }
                            }
                        }

                        // 4. 当前持仓的总市值
                        val currentMarketValue = dbHolding.totalQuantity * prices.currentPrice

                        // 5. 当日总盈亏 ($)
                        // Daily PL = Current Market Value - Overnight Value at Close - Net Cash Invested Today
                        // 这里的 Net Cash Invested Today 专指 BUY/SELL 产生的净投入/产出，排除了分红。
                        val dailyPL = currentMarketValue - overnightValueAtClose - netCashInvestedToday

                        // 6. 额外处理当日分红收入，将其加回 DailyPL (分红是当日利润的一部分)
                        val todayDividend = todayTransactions
                            .filter { it.type == TransactionType.DIVIDEND }
                            .sumOf { it.quantity * it.price }

                        val finalDailyPL = dailyPL + todayDividend

                        // 7. 当日盈亏 (%) 的计算基数
                        // 基数 = 昨日市值 + 当日净买入成本（如果净买入 > 0）
                        val finalBasis: Double = when {
                            overnightValueAtClose != 0.0 -> overnightValueAtClose
                            // 如果昨日没有仓位，则使用当日净投入的绝对值作为基数
                            netCashInvestedToday != 0.0 -> netCashInvestedToday.absoluteValue
                            else -> 0.0
                        }

                        val dailyPLPercent = if (finalBasis != 0.0) finalDailyPL / finalBasis * 100 else 0.0
                        // *** 修正结束 ***

                        dbHolding.copy(
                            currentPrice = prices.currentPrice,
                            dailyPL = finalDailyPL, // 使用包含分红的最终当日盈亏
                            dailyPLPercent = dailyPLPercent
                            // name = priceDataMap[dbHolding.id]?.name ?: dbHolding.name // Optional: keep existing name if network fails?
                        )
                    } ?: dbHolding
                }


                _uiState.update {
                    it.copy(
                        holdings = finalActiveHoldings,
                        cashBalance = cashBalance,
                        portfolioName = portfolioName, // *** 更新 portfolioName ***
                        cashTransactions = cashTransactions.sortedByDescending { t -> t.date }, // *** 更新现金交易 ***
                        closedPositions = closedPositionsFromDb // *** 更新平仓列表 ***
                    )
                }

                if (isInitialLoad) {
                    isInitialLoad = false
                    // *** 修改：在初始加载时，同时刷新当前数据和默认图表 ***
                    if (finalActiveHoldings.isNotEmpty()) {
                        refreshData()
                    }
                    // 触发默认图表加载（例如 1M）
                    updatePortfolioChart(TimeRange.ONE_MONTH)
                }
            }
                .catch { throwable ->
                    Log.e("StockViewModel", "Error in combine flow", throwable)
                    _toastEvents.emit("数据加载时发生错误")
                }
                .collect()
        }
    }


    // ... (refreshData remains mostly the same) ...
    fun refreshData() {
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            // *** 修正：现在刷新所有活动持仓 ***
            val holdingsToRefresh = _uiState.value.holdings
            try {
                if (holdingsToRefresh.isEmpty()) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@launch
                }

                val deferredJobs = holdingsToRefresh.map { holding ->
                    async(Dispatchers.IO) {
                        // 在刷新时也尝试从数据库获取名称，以防 StockHolding 对象中的名称过时
                        val dbName = stockNameDao.getChineseNameByTicker(holding.id.uppercase())
                        val priceData = YahooFinanceScraper.fetchStockData(holding.id)
                        val finalName = dbName ?: priceData?.name ?: holding.name // 优先使用数据库名称

                        val firstTransactionDate = holding.transactions.minOfOrNull { it.date }
                        val dividendHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchDividendHistory(holding.id, firstTransactionDate) else null
                        val splitHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchSplitHistory(holding.id, firstTransactionDate) else null

                        // 将最终确定的名称传递下去
                        object {
                            val holding = holding.copy(name = finalName) // 更新持有对象的名称
                            val priceData = priceData?.copy(name = finalName) // 更新价格数据中的名称
                            val dividendHistory = dividendHistory
                            val splitHistory = splitHistory
                        }
                    }
                }

                val results = deferredJobs.awaitAll()
                var successfulFetches = 0
                val newPriceData = mutableMapOf<String, YahooFinanceScraper.ScrapedData>()

                val dbWriteJobs = mutableListOf<Job>()
                results.forEach { data ->
                    // ... (dividend and split handling remains the same) ...
                    data.dividendHistory?.forEach { dividendInfo ->
                        val alreadyExists = data.holding.transactions.any { it.type == TransactionType.DIVIDEND && it.date == dividendInfo.date }
                        if (!alreadyExists) {
                            val sharesOnDate = data.holding.getQuantityOnDate(dividendInfo.date)
                            if (sharesOnDate > 0) {
                                val dividendTransaction = Transaction(date = dividendInfo.date, type = TransactionType.DIVIDEND, quantity = sharesOnDate, price = dividendInfo.dividend)
                                dbWriteJobs.add(launch(Dispatchers.IO) { saveOrUpdateTransactionInternal(dividendTransaction, data.holding.id, "", data.holding.name, data.priceData?.exchangeName) })
                            }
                        }
                    }

                    data.splitHistory?.forEach { splitInfo ->
                        val alreadyExists = data.holding.transactions.any { it.type == TransactionType.SPLIT && it.date == splitInfo.date }
                        if (!alreadyExists) {
                            val splitTransaction = Transaction(
                                date = splitInfo.date,
                                type = TransactionType.SPLIT,
                                quantity = splitInfo.numerator,
                                price = splitInfo.denominator
                            )
                            dbWriteJobs.add(launch(Dispatchers.IO) { saveOrUpdateTransactionInternal(splitTransaction, data.holding.id, "", data.holding.name, data.priceData?.exchangeName) })
                        }
                    }
                }

                dbWriteJobs.joinAll()

                results.forEach { data ->
                    data.priceData?.let {
                        successfulFetches++
                        // *** 关键修复：格式化交易所名称并创建显示代码 ***
                        val formattedExchange = YahooFinanceScraper.formatExchangeName(it.exchangeName)
                        val displayTicker = "$formattedExchange:${data.holding.id}"

                        // 更新股票时也更新名称和 ticker
                        stockDao.updateStock(data.holding.toEntity().copy(
                            currentPrice = it.currentPrice,
                            name = data.holding.name,
                            ticker = displayTicker // *** 存储格式化后的 Ticker ***
                        ))
                        newPriceData[data.holding.id] = it
                    }
                }

                _priceDataFlow.update { it + newPriceData }

                // ... (toast messages remain the same) ...
                if (successfulFetches == 0 && holdingsToRefresh.isNotEmpty()) {
                    _toastEvents.emit("未能获取任何股票的最新价格")
                } else if (successfulFetches < holdingsToRefresh.size) {
                    _toastEvents.emit("未能获取部分股票的最新价格")
                }

            } catch (e: Exception) {
                Log.e("StockViewModel", "Failed to refresh data", e)
                _toastEvents.emit("刷新失败，请检查网络连接")
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    // ... (Portfolio Chart calculation methods remain the same) ...
    // *** 新增：图表计算相关方法 ***

    /**
     * 公共方法，由 UI 调用以更新图表。
     */
    fun updatePortfolioChart(timeRange: TimeRange) {
        val TAG = "StockViewModel"

        // 如果已在计算，先取消
        chartCalculationJob?.cancel()
        chartCalculationJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isChartLoading = true, chartTimeRange = timeRange) }
                val chartData = calculatePortfolioHistory(timeRange)
                _uiState.update { it.copy(portfolioChartData = chartData, isChartLoading = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Chart calculation cancelled.")
                    throw e // Re-throw cancellation
                }
                Log.e(TAG, "Failed to calculate portfolio history", e)
                _toastEvents.emit("图表数据计算失败")
                _uiState.update { it.copy(portfolioChartData = emptyList(), isChartLoading = false) }
            }
        }
    }

    /**
     * 核心计算逻辑：计算投资组合的历史盈亏率。
     */
    private suspend fun calculatePortfolioHistory(timeRange: TimeRange): List<StockUiState.ChartDataPoint> = withContext(Dispatchers.Default) {
        val allStockTransactions = stockDao.getAllStocksWithTransactions().first().map { it.toUIModel() }
        val allCashTransactions = cashDao.getAllCashTransactions().first().map { it.toUIModel() }

        if (allStockTransactions.isEmpty() && allCashTransactions.isEmpty()) {
            return@withContext emptyList() // 没有交易，返回空图表
        }

        val today = LocalDate.now()
        val firstTransactionDate = allStockTransactions.flatMap { it.transactions }.minOfOrNull { it.date }
        val firstCashDate = allCashTransactions.minOfOrNull { it.date }

        val portfolioStartDate = when {
            firstTransactionDate != null && firstCashDate != null -> if (firstTransactionDate.isBefore(firstCashDate)) firstTransactionDate else firstCashDate
            firstTransactionDate != null -> firstTransactionDate
            firstCashDate != null -> firstCashDate
            else -> today // 理论上不会到这里
        }

        val startDate = getStartDateForTimeRange(timeRange, portfolioStartDate, today)
        val tickers = allStockTransactions.map { it.id }.distinct()

        // 1. 并发获取所有需要的历史价格
        val priceCache = fetchAndCacheHistoricalPrices(tickers, startDate)

        // 2. 准备一个迭代器，从 startDate 遍历到 today
        val chartDataPoints = mutableListOf<StockUiState.ChartDataPoint>()
        var currentDate = startDate
        var lastValidPriceMap = tickers.associateWith { 0.0 } // 缓存每个股票的最后有效价格

        while (currentDate.isBefore(today) || currentDate.isEqual(today)) {
            var totalMarketValue = 0.0
            var totalNetInvestment = 0.0

            // 2a. 计算当日的现金余额和总投入
            val cashTransactionsUpToDate = allCashTransactions.filter { !it.date.isAfter(currentDate) }
            val cashBalance = cashTransactionsUpToDate.sumOf {
                if (it.type == CashTransactionType.DEPOSIT || it.type == CashTransactionType.SELL || it.type == CashTransactionType.DIVIDEND) it.amount else -it.amount
            }
            // 总投入 = 存入 - 取出
            totalNetInvestment = cashTransactionsUpToDate.filter { it.type == CashTransactionType.DEPOSIT || it.type == CashTransactionType.WITHDRAWAL }
                .sumOf { if (it.type == CashTransactionType.DEPOSIT) it.amount else -it.amount }


            // 2b. 计算当日的股票市值和总投入
            for (stock in allStockTransactions) {
                val transactionsUpToDate = stock.transactions.filter { !it.date.isAfter(currentDate) }

                var quantityHeld = 0.0
                transactionsUpToDate.sortedBy { it.date }.forEach { t ->
                    when (t.type) {
                        TransactionType.BUY -> quantityHeld += t.quantity
                        TransactionType.SELL -> quantityHeld -= t.quantity
                        TransactionType.SPLIT -> {
                            val ratio = t.quantity / t.price
                            quantityHeld *= ratio
                        }
                        else -> { /* 忽略分红等 */ }
                    }
                }

                // 累加股票的净成本到总投入
                val stockNetCost = transactionsUpToDate
                    .filter { it.type == TransactionType.BUY || it.type == TransactionType.SELL }
                    .sumOf { if (it.type == TransactionType.BUY) (it.quantity * it.price + it.fee) else -(it.quantity * it.price - it.fee) }

                totalNetInvestment += stockNetCost

                // 2c. 获取当日价格并计算市值
                val stockPriceMap = priceCache[stock.id]
                val priceOnDate = stockPriceMap?.get(currentDate)

                val priceToUse = if (priceOnDate != null) {
                    lastValidPriceMap = lastValidPriceMap.plus(stock.id to priceOnDate) // 更新缓存
                    priceOnDate
                } else {
                    lastValidPriceMap[stock.id] ?: 0.0 // 使用缓存的最后有效价格
                }

                totalMarketValue += quantityHeld * priceToUse
            }

            // 2d. 计算当日 P/L %
            val totalAssets = totalMarketValue + cashBalance
            val plAmount = totalAssets - totalNetInvestment
            val plRate = if (totalNetInvestment > 0) (plAmount / totalNetInvestment) * 100.0 else 0.0

            chartDataPoints.add(StockUiState.ChartDataPoint(currentDate, plRate))
            currentDate = currentDate.plusDays(1)
        }

        return@withContext chartDataPoints
    }

    /**
     * 并发获取并缓存所有需要的历史价格数据。
     */
    private suspend fun fetchAndCacheHistoricalPrices(tickers: List<String>, startDate: LocalDate): Map<String, Map<LocalDate, Double>> {
        val priceCache = mutableMapOf<String, Map<LocalDate, Double>>()
        tickers.map { ticker ->
            viewModelScope.async(Dispatchers.IO) {
                // 检查缓存
                if (!historicalPriceCache.containsKey(ticker) || historicalPriceCache[ticker]!!.keys.minOrNull()?.isAfter(startDate) == true) {
                    // 缓存未命中或缓存数据不够旧，重新获取
                    val data = YahooFinanceScraper.fetchHistoricalData(ticker, startDate)
                    val priceMap = data.associate { it.date to it.closePrice }
                    historicalPriceCache[ticker] = priceMap // 存入缓存
                    ticker to priceMap
                } else {
                    // 缓存命中
                    ticker to (historicalPriceCache[ticker] ?: emptyMap())
                }
            }
        }.awaitAll().forEach { (ticker, prices) ->
            priceCache[ticker] = prices
        }
        return priceCache
    }

    /**
     * 根据 TimeRange 计算开始日期。
     */
    private fun getStartDateForTimeRange(timeRange: TimeRange, portfolioStartDate: LocalDate, today: LocalDate): LocalDate {
        val calculatedDate = when (timeRange) {
            TimeRange.FIVE_DAY -> today.minusDays(5)
            TimeRange.ONE_MONTH -> today.minusMonths(1)
            TimeRange.THREE_MONTH -> today.minusMonths(3)
            TimeRange.SIX_MONTH -> today.minusMonths(6)
            TimeRange.ONE_YEAR -> today.minusYears(1)
            TimeRange.FIVE_YEAR -> today.minusYears(5)
            TimeRange.ALL -> portfolioStartDate
        }
        // 确保开始日期不早于投资组合的实际开始日期
        return if (calculatedDate.isBefore(portfolioStartDate)) portfolioStartDate else calculatedDate
    }

    // *** 结束图表计算 ***


    // ... (fetchInitialStockData, selectStock, prepareNewTransaction, prepareEditTransaction, saveOrUpdateTransaction, saveOrUpdateTransactionInternal, savePortfolioName, addCashTransaction, updateCashTransaction, deleteCashTransaction, deleteTransaction, exportDatabase, importDatabase remain the same) ...
    suspend fun fetchInitialStockData(ticker: String): YahooFinanceScraper.ScrapedData? {
        val upperCaseTicker = ticker.uppercase()
        return withContext(Dispatchers.IO) {
            // 1. 先查数据库获取中文名
            val chineseNameFromDb = stockNameDao.getChineseNameByTicker(upperCaseTicker)
            Log.d("StockViewModel", "DB lookup for $upperCaseTicker: Name found = ${chineseNameFromDb != null}")

            // 2. 从网络获取价格数据（可能包含名称）
            val scrapedData = YahooFinanceScraper.fetchStockData(upperCaseTicker)
            Log.d("StockViewModel", "Network fetch for $upperCaseTicker: Data = $scrapedData")


            if (scrapedData != null) {
                // 优先使用数据库中的中文名
                val finalName = chineseNameFromDb ?: scrapedData.name

                // 如果数据库没有名字，但网络请求成功获取了名字，则存入数据库
                if (chineseNameFromDb == null && scrapedData.name != upperCaseTicker && scrapedData.name.isNotBlank()) {
                    try {
                        // 在后台插入，不阻塞主流程
                        launch {
                            stockNameDao.insertAll(listOf(
                                StockNameEntity(
                                    upperCaseTicker,
                                    scrapedData.name
                                )
                            ))
                            Log.d("StockViewModel", "Saved newly fetched name for $upperCaseTicker to DB.")
                        }
                    } catch (e: Exception) {
                        Log.e("StockViewModel", "Failed to save fetched name for $upperCaseTicker to DB", e)
                    }
                }

                Log.d("StockViewModel", "Final name for $upperCaseTicker: $finalName")
                // 返回包含最终名称和价格的数据
                scrapedData.copy(name = finalName)
            } else {
                // 如果网络请求失败，返回 null
                Log.w("StockViewModel", "Network fetch failed for $upperCaseTicker.")
                null
            }
        }
    }

    fun selectStock(stockId: String) {
        _uiState.update { it.copy(selectedStockId = stockId) }
    }

    fun prepareNewTransaction(stockId: String? = null) {
        _uiState.update { it.copy(selectedStockId = stockId, transactionToEditId = null) }
    }

    fun prepareEditTransaction(transactionId: String) {
        _uiState.update { it.copy(transactionToEditId = transactionId) }
    }


    // ... (saveOrUpdateTransaction and Internal function remain the same) ...
    fun saveOrUpdateTransaction(
        transaction: Transaction,
        stockId: String?, // Ticker of existing stock being edited, or null if adding new
        newStockIdentifier: String, // Ticker entered by user if adding new
        stockName: String, // Name (either from DB/fetch or entered by user)
        exchangeName: String? // *** 新增：交易所代码 ***
    ) {
        viewModelScope.launch {
            // 确保传递的 stockName 是最终确定的名称
            saveOrUpdateTransactionInternal(transaction, stockId, newStockIdentifier, stockName, exchangeName) // *** 传递交易所代码 ***
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    private suspend fun saveOrUpdateTransactionInternal(
        transaction: Transaction,
        stockId: String?,
        newStockIdentifier: String,
        stockName: String, // 接收最终确定的名称
        exchangeName: String? // *** 新增：交易所代码 ***
    ) {
        val idToProcess = (stockId ?: newStockIdentifier).uppercase()
        if (idToProcess.isBlank()) return

        val originalTransaction = _uiState.value.transactionToEdit
        if (originalTransaction != null) {
            // Ensure cash transaction linked to the original stock transaction is removed before inserting new one
            cashDao.deleteByStockTransactionId(originalTransaction.id)
        }


        val existingStock = stockDao.getStockById(idToProcess)
        if (existingStock != null) {
            // 更新股票时使用传入的 stockName
            stockDao.updateStock(existingStock.copy(name = stockName))
            stockDao.insertTransaction(transaction.toEntity(idToProcess))
        } else {
            // *** 关键修复：格式化交易所名称并创建显示代码 ***
            val formattedExchange = YahooFinanceScraper.formatExchangeName(exchangeName ?: "")
            val displayTicker = if (formattedExchange.isNotBlank()) "$formattedExchange:$idToProcess" else idToProcess

            // 创建新股票时使用传入的 stockName 和 displayTicker
            val newStock = StockHolding(
                id = idToProcess, name = stockName,
                ticker = displayTicker, // *** 使用格式化后的代码 ***
                currentPrice = transaction.price, transactions = emptyList() // Initial price guess
            )
            stockDao.insertStock(newStock.toEntity())
            stockDao.insertTransaction(transaction.toEntity(newStock.id))
        }

        // --- Cash Transaction Logic ---
        // Ensure cash transaction reflects the correct stock transaction ID and details
        if (transaction.type == TransactionType.SPLIT) {
            return // No cash impact for splits
        }

        val amount = when (transaction.type) {
            TransactionType.BUY -> -((transaction.quantity * transaction.price) + transaction.fee)
            TransactionType.SELL -> (transaction.quantity * transaction.price) - transaction.fee
            TransactionType.DIVIDEND -> transaction.quantity * transaction.price // Dividends are per share, price holds dividend rate
            else -> 0.0 // Should not happen due to SPLIT check above
        }

        if (amount != 0.0) {
            val cashType = if (amount > 0) CashTransactionType.SELL else CashTransactionType.BUY
            val cashTransaction = CashTransaction(
                date = transaction.date,
                type = cashType,
                amount = kotlin.math.abs(amount), // Amount should always be positive
                stockTransactionId = transaction.id // Link to the stock transaction
            )
            cashDao.insertCashTransaction(cashTransaction.toEntity())
        }
    }

    // *** 新增：保存投资组合名称的函数 ***
    fun savePortfolioName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isNotBlank()) {
                portfolioSettingsDao.insert(PortfolioSettingsEntity(name = name))
            } else {
                _toastEvents.emit("投资组合名称不能为空")
            }
        }
    }


    // --- 现金交易相关 ---

    // *** 新增：准备用于编辑的现金交易 ***
    fun prepareEditCashTransaction(transactionId: String) {
        _uiState.update { it.copy(cashTransactionToEditId = transactionId) }
    }

    // *** 新增：准备用于添加新现金交易（清除编辑状态） ***
    fun prepareNewCashTransaction() {
        _uiState.update { it.copy(cashTransactionToEditId = null) }
    }

    // ... (addCashTransaction remains the same) ...
    // *** 修改：增加 date 参数 ***
    fun addCashTransaction(amount: Double, type: CashTransactionType, date: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) {
            if (amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            // *** 修改：使用传入的 date ***
            val cashTransaction = CashTransaction(date = date, type = type, amount = amount)
            cashDao.insertCashTransaction(cashTransaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    // *** 新增：更新现金交易 ***
    fun updateCashTransaction(transaction: CashTransaction) {
        viewModelScope.launch(Dispatchers.IO) {
            if (transaction.amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            // 使用 insert (带 REPLACE 策略) 来更新
            cashDao.insertCashTransaction(transaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    // *** 新增：删除现金交易 ***
    fun deleteCashTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 检查此现金交易是否关联了股票交易
            val transaction = _uiState.value.cashTransactions.find { it.id == transactionId }
            if (transaction?.stockTransactionId != null) {
                _toastEvents.emit("无法删除：此现金记录已关联到股票交易。")
            } else {
                cashDao.deleteCashTransactionById(transactionId)
                _navigationEvents.emit(NavigationEvent.NavigateBack)
            }
        }
    }


    /**
    // ... (deleteTransaction remains the same) ...
     * 删除指定的交易记录，并根据交易类型清理相关的自动生成记录（分红/拆股/合股）。
     */
    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stock = _uiState.value.selectedStock
            val transactionToDelete = stock.transactions.find { it.id == transactionId }

            if (transactionToDelete != null) {
                // 1. 删除交易本身和关联的现金记录
                stockDao.deleteTransactionById(transactionId)
                cashDao.deleteByStockTransactionId(transactionId)

                // 2. 如果删除的是 Buy 或 Sell 这种核心交易，则需要清理所有自动生成的特殊交易
                if (transactionToDelete.type == TransactionType.BUY || transactionToDelete.type == TransactionType.SELL) {
                    val specialTypes = listOf(TransactionType.DIVIDEND, TransactionType.SPLIT)

                    // 删除该股票所有类型为 DIVIDEND 或 SPLIT 的交易记录
                    val transactionsToRemove = stock.transactions.filter {
                        it.type in specialTypes && it.id != transactionId
                    }

                    transactionsToRemove.forEach { t ->
                        stockDao.deleteTransactionById(t.id)
                        // 分红交易会生成关联的现金记录，需要删除
                        cashDao.deleteByStockTransactionId(t.id)
                    }

                    Log.d("StockViewModel", "Deleted ${transactionsToRemove.size} dividend/split transactions for ${stock.id}.")

                    // 由于删除了核心交易和所有特殊交易，需要再次刷新价格以重新获取特殊交易
                    launch { refreshData() }
                }

                // 3. 如果删除后该股票没有剩余交易记录，则删除该股票持有实体
                val remainingTransactions = stockDao.getTransactionsByStockId(stock.id)
                if (remainingTransactions.isEmpty()) {
                    stockDao.deleteStockById(stock.id)
                    _uiState.update { it.copy(selectedStockId = null) } // 清理选中状态
                }

                _navigationEvents.emit(NavigationEvent.NavigateBack) // Navigate back after deletion
            }
        }
    }

    // *** 新增：CSV 导入功能 ***
    fun importTransactionsFromCsv(uri: Uri, stockId: String) {
        viewModelScope.launch(Dispatchers.IO) {


            val TAG = "importCSV"

            try {
                _uiState.update { it.copy(isRefreshing = true) }

                // 1. 获取股票信息，用于保存交易
                val stockData = fetchInitialStockData(stockId)
                if (stockData == null) {
                    _toastEvents.emit("导入失败：无法获取 $stockId 的股票信息")
                    return@launch
                }

                val stockName = stockData.name
                val exchangeName = stockData.exchangeName
                var importedCount = 0
                var skippedCount = 0

                // 2. 读取文件
                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->

                        reader.readLine() // 跳过标题行

                        var line: String? = reader.readLine()
                        while (line != null) {
                            val parts = line.split(",").map { it.trim().removeSurrounding("\"") }
                            if (parts.size < 4) {
                                Log.w(TAG, "Skipping malformed CSV line: $line")
                                skippedCount++
                                line = reader.readLine()
                                continue
                            }

                            try {
                                val date = LocalDate.parse(parts[0], DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                val type = if (parts[1] == "买入") TransactionType.BUY else TransactionType.SELL
                                val quantity = parts[2].toDoubleOrNull()
                                val price = parts[3].toDoubleOrNull()
                                // 您的 CSV 似乎没有手续费，默认为 0
                                val fee = 0.0

                                if (quantity != null && price != null) {
                                    val newTransaction = Transaction(
                                        id = UUID.randomUUID().toString(),
                                        date = date,
                                        type = type,
                                        quantity = quantity,
                                        price = price,
                                        fee = fee
                                    )
                                    // 调用内部保存方法
                                    saveOrUpdateTransactionInternal(newTransaction, stockId, stockId, stockName, exchangeName)
                                    importedCount++
                                } else {
                                    Log.w(TAG, "Skipping line with invalid number: $line")
                                    skippedCount++
                                }
                            } catch (e: DateTimeParseException) {
                                Log.e(TAG, "Skipping line with invalid date: $line", e)
                                skippedCount++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing line: $line", e)
                                skippedCount++
                            }
                            line = reader.readLine()
                        }
                    }
                }

                _toastEvents.emit("导入完成：成功 $importedCount 条，跳过 $skippedCount 条")
                // 3. 刷新数据
                refreshData()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to import CSV", e)
                _toastEvents.emit("导入失败：${e.message}")
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }
    // *** 新增结束 ***


    // --- 数据库导出/导入功能 ---

// ... (exportDatabase and importDatabase remain the same) ...
    /**
     * 将数据库导出到用户指定的 Uri (SAF)。
     */
    fun exportDatabase(targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // *** 关键修复：在导出之前强制执行 WAL 检查点，确保数据完整性 ***
                StockDatabase.runCheckpoint(appContext)

                val filesCopied = StockDatabase.exportDatabase(appContext, targetUri)
                if (filesCopied > 0) {
                    _toastEvents.emit("数据库备份成功！文件已保存。")
                } else {
                    _toastEvents.emit("备份失败：未找到主数据库文件。")
                }
            } catch (e: Exception) {
                Log.e("StockViewModel", "Database export failed", e)
                _toastEvents.emit("备份失败：${e.localizedMessage}")
            }
        }
    }

    /**
     * 从用户指定的 Uri (SAF) 导入数据库。
     * 导入后会强制刷新所有数据。
     */
    fun importDatabase(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isRefreshing = true) } // 导入过程中显示加载状态
                val filesCopied = StockDatabase.importDatabase(appContext, sourceUri)

                if (filesCopied > 0) {
                    // 重新加载所有数据：清空价格数据缓存并等待 Flow 重新触发
                    _priceDataFlow.update { emptyMap() }
                    _toastEvents.emit("数据库恢复成功！正在重新加载数据...")
                    // 强制刷新所有价格，确保 UI 数据一致性
                    refreshData()
                } else {
                    _toastEvents.emit("恢复失败：未找到备份文件或文件内容为空。")
                }
            } catch (e: Exception) {
                Log.e("StockViewModel", "Database import failed", e)
                _toastEvents.emit("恢复失败：${e.localizedMessage}")
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }
    // --- 新增结束 ---
}

// ... (StockViewModelFactory remains the same) ...
class StockViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StockViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StockViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}