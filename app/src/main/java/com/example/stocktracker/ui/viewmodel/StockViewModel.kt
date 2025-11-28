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
import java.util.UUID
import kotlin.math.absoluteValue

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

enum class TimeRange {
    FIVE_DAY, ONE_MONTH, THREE_MONTH, SIX_MONTH, ONE_YEAR, FIVE_YEAR, ALL
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 0.0,
    val isRefreshing: Boolean = false,
    val portfolioName: String = "我的投资组合",
    val cashTransactions: List<CashTransaction> = emptyList(),
    val closedPositions: List<StockHolding> = emptyList(),
    val cashTransactionToEditId: String? = null,

    val chartTimeRange: TimeRange = TimeRange.ONE_MONTH,
    val isChartLoading: Boolean = false,
    val portfolioChartData: List<ChartDataPoint> = emptyList(),
    // *** 新增：基准指数（如 NASDAQ）的数据 ***
    val benchmarkChartData: List<ChartDataPoint> = emptyList()
) {
    data class ChartDataPoint(val date: LocalDate, val value: Double)

    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId }
            ?: closedPositions.find { it.id == selectedStockId }
            ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }

    val cashTransactionToEdit: CashTransaction?
        get() = cashTransactions.find { it.id == cashTransactionToEditId }
}


class StockViewModel(application: Application) : ViewModel() {
    private val appContext = application.applicationContext
    private val db = StockDatabase.getDatabase(application)
    private val stockDao = db.stockDao()
    private val cashDao = db.cashDao()
    private val stockNameDao = db.stockNameDao()
    private val portfolioSettingsDao = db.portfolioSettingsDao()

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()


    private val _priceDataFlow = MutableStateFlow<Map<String, YahooFinanceScraper.ScrapedData>>(emptyMap())
    private var isInitialLoad = true

    private val historicalPriceCache = mutableMapOf<String, Map<LocalDate, Double>>()
    private var chartCalculationJob: Job? = null

    // *** 定义基准指数代码: NASDAQ Composite ***
    private val BENCHMARK_TICKER = "^IXIC"

    private val TAG = "StockViewModel"

    init {
        val holdingsFlow = stockDao.getAllStocksWithTransactions().map { list -> list.map { it.toUIModel() } }
        val cashFlow = cashDao.getAllCashTransactions().map { list -> list.map { it.toUIModel() } }
        val nameFlow = portfolioSettingsDao.getPortfolioName().map { it ?: "我的投资组合" }

        viewModelScope.launch(Dispatchers.IO) {
            combine(holdingsFlow, cashFlow, _priceDataFlow, nameFlow) { holdingsFromDb, cashTransactions, priceDataMap, portfolioName ->
                val cashBalance = cashTransactions.sumOf {
                    when (it.type) {
                        CashTransactionType.DEPOSIT,
                        CashTransactionType.SELL,
                        CashTransactionType.DIVIDEND -> it.amount
                        CashTransactionType.WITHDRAWAL,
                        CashTransactionType.BUY -> -it.amount
                        CashTransactionType.SPLIT -> 0.0
                    }
                }

                val activeHoldingsFromDb = holdingsFromDb.filter { it.totalQuantity > 0 }
                val closedPositionsFromDb = holdingsFromDb.filter {
                    it.totalQuantity <= 0 && (it.totalPL != 0.0 || it.totalSoldValue != 0.0)
                }.sortedByDescending { it.transactions.maxOfOrNull { t -> t.date } }

                val finalActiveHoldings = activeHoldingsFromDb.map { dbHolding ->
                    priceDataMap[dbHolding.id]?.let { prices ->
                        val today = LocalDate.now()
                        val overnightQuantity = dbHolding.getQuantityOnDate(today.minusDays(1))
                        val overnightValueAtClose = overnightQuantity * prices.previousClose

                        var netCashInvestedToday = 0.0
                        val todayTransactions = dbHolding.transactions.filter { it.date == today }

                        for (t in todayTransactions) {
                            when (t.type) {
                                TransactionType.BUY -> {
                                    netCashInvestedToday += (t.quantity * t.price) + t.fee
                                }
                                TransactionType.SELL -> {
                                    netCashInvestedToday -= (t.quantity * t.price) - t.fee
                                }
                                else -> { }
                            }
                        }

                        val currentMarketValue = dbHolding.totalQuantity * prices.currentPrice
                        val dailyPL = currentMarketValue - overnightValueAtClose - netCashInvestedToday
                        val todayDividend = todayTransactions
                            .filter { it.type == TransactionType.DIVIDEND }
                            .sumOf { it.quantity * it.price }

                        val finalDailyPL = dailyPL + todayDividend
                        val finalBasis: Double = when {
                            overnightValueAtClose != 0.0 -> overnightValueAtClose
                            netCashInvestedToday != 0.0 -> netCashInvestedToday.absoluteValue
                            else -> 0.0
                        }

                        val dailyPLPercent = if (finalBasis != 0.0) finalDailyPL / finalBasis * 100 else 0.0

                        dbHolding.copy(
                            currentPrice = prices.currentPrice,
                            dailyPL = finalDailyPL,
                            dailyPLPercent = dailyPLPercent
                        )
                    } ?: dbHolding
                }


                _uiState.update {
                    it.copy(
                        holdings = finalActiveHoldings,
                        cashBalance = cashBalance,
                        portfolioName = portfolioName,
                        cashTransactions = cashTransactions.sortedByDescending { t -> t.date },
                        closedPositions = closedPositionsFromDb
                    )
                }

                if (isInitialLoad) {
                    isInitialLoad = false
                    if (finalActiveHoldings.isNotEmpty()) {
                        refreshData()
                    }
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


    fun refreshData() {
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val holdingsToRefresh = _uiState.value.holdings
            try {
                if (holdingsToRefresh.isEmpty()) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@launch
                }

                val deferredJobs = holdingsToRefresh.map { holding ->
                    async(Dispatchers.IO) {
                        val dbName = stockNameDao.getChineseNameByTicker(holding.id.uppercase())
                        val priceData = YahooFinanceScraper.fetchStockData(holding.id)
                        val finalName = dbName ?: priceData?.name ?: holding.name

                        val firstTransactionDate = holding.transactions.minOfOrNull { it.date }
                        val dividendHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchDividendHistory(holding.id, firstTransactionDate) else null
                        val splitHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchSplitHistory(holding.id, firstTransactionDate) else null

                        object {
                            val holding = holding.copy(name = finalName)
                            val priceData = priceData?.copy(name = finalName)
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
                    data.dividendHistory?.forEach { dividendInfo ->
                        val alreadyExists = data.holding.transactions.any { it.type == TransactionType.DIVIDEND && it.date == dividendInfo.date }
                        if (!alreadyExists) {
                            val sharesOnDate = data.holding.getQuantityOnDate(dividendInfo.date.minusDays(1))
                            if (sharesOnDate.absoluteValue > 1e-9) {
                                val dividendTransaction = Transaction(
                                    date = dividendInfo.date,
                                    type = TransactionType.DIVIDEND,
                                    quantity = sharesOnDate,
                                    price = dividendInfo.dividend
                                )
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
                        val formattedExchange = YahooFinanceScraper.formatExchangeName(it.exchangeName)
                        val displayTicker = "$formattedExchange:${data.holding.id}"
                        stockDao.updateStock(data.holding.toEntity().copy(
                            currentPrice = it.currentPrice,
                            name = data.holding.name,
                            ticker = displayTicker
                        ))
                        newPriceData[data.holding.id] = it
                    }
                }

                _priceDataFlow.update { it + newPriceData }

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

    fun updatePortfolioChart(timeRange: TimeRange) {
        chartCalculationJob?.cancel()
        chartCalculationJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isChartLoading = true, chartTimeRange = timeRange) }
                // *** 修改：calculatePortfolioHistory 现在返回两个列表 ***
                val (portfolioData, benchmarkData) = calculatePortfolioHistory(timeRange)
                _uiState.update {
                    it.copy(
                        portfolioChartData = portfolioData,
                        benchmarkChartData = benchmarkData, // 更新基准数据
                        isChartLoading = false
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Chart calculation cancelled.")
                    throw e
                }
                Log.e(TAG, "Failed to calculate portfolio history", e)
                _toastEvents.emit("图表数据计算失败")
                _uiState.update { it.copy(portfolioChartData = emptyList(), benchmarkChartData = emptyList(), isChartLoading = false) }
            }
        }
    }

    /**
     * 核心计算逻辑：计算投资组合的历史盈亏率，以及基准指数的涨跌幅。
     * *** 修改返回值：包含 Portfolio 和 Benchmark 数据的 Pair ***
     */
    private suspend fun calculatePortfolioHistory(timeRange: TimeRange): Pair<List<StockUiState.ChartDataPoint>, List<StockUiState.ChartDataPoint>> = withContext(Dispatchers.Default) {
        val allStockTransactions = stockDao.getAllStocksWithTransactions().first().map { it.toUIModel() }
        val allCashTransactions = cashDao.getAllCashTransactions().first().map { it.toUIModel() }

        if (allStockTransactions.isEmpty() && allCashTransactions.isEmpty()) {
            return@withContext Pair(emptyList(), emptyList())
        }

        val today = LocalDate.now()
        val firstTransactionDate = allStockTransactions.flatMap { it.transactions }.minOfOrNull { it.date }
        val firstCashDate = allCashTransactions.minOfOrNull { it.date }

        val portfolioStartDate = when {
            firstTransactionDate != null && firstCashDate != null -> if (firstTransactionDate.isBefore(firstCashDate)) firstTransactionDate else firstCashDate
            firstTransactionDate != null -> firstTransactionDate
            firstCashDate != null -> firstCashDate
            else -> today
        }

        val startDate = getStartDateForTimeRange(timeRange, portfolioStartDate, today)
        // *** 1. 准备 Ticker 列表，加入基准指数 ***
        val portfolioTickers = allStockTransactions.map { it.id }.distinct()
        val allTickers = portfolioTickers + BENCHMARK_TICKER

        // *** 2. 并发获取所有需要的历史价格（包括基准） ***
        val priceCache = fetchAndCacheHistoricalPrices(allTickers, startDate)

        val chartDataPoints = mutableListOf<StockUiState.ChartDataPoint>()
        val benchmarkDataPoints = mutableListOf<StockUiState.ChartDataPoint>()

        var currentDate = startDate
        var lastValidPriceMap = portfolioTickers.associateWith { 0.0 }

        // 基准指数处理
        val benchmarkPrices = priceCache[BENCHMARK_TICKER] ?: emptyMap()
        // 找到基准指数在起始日（或之后最近一日）的价格作为 0% 基线
        var benchmarkBaseline = -1.0

        // 预先找到基准价格，避免在循环中重复搜索
        var tempDate = startDate
        while (benchmarkBaseline == -1.0 && (tempDate.isBefore(today) || tempDate.isEqual(today))) {
            benchmarkPrices[tempDate]?.let {
                benchmarkBaseline = it
            }
            tempDate = tempDate.plusDays(1)
        }

        // 重置日期开始循环
        currentDate = startDate

        while (currentDate.isBefore(today) || currentDate.isEqual(today)) {
            // --- 计算投资组合 P/L ---
            var totalMarketValue = 0.0
            var totalNetInvestment = 0.0

            val cashTransactionsUpToDate = allCashTransactions.filter { !it.date.isAfter(currentDate) }
            val cashBalance = cashTransactionsUpToDate.sumOf {
                if (it.type == CashTransactionType.DEPOSIT || it.type == CashTransactionType.SELL || it.type == CashTransactionType.DIVIDEND) it.amount else -it.amount
            }

            totalNetInvestment = cashTransactionsUpToDate.filter { it.type == CashTransactionType.DEPOSIT || it.type == CashTransactionType.WITHDRAWAL }
                .sumOf { if (it.type == CashTransactionType.DEPOSIT) it.amount else -it.amount }

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
                        else -> { }
                    }
                }

                val stockNetCost = transactionsUpToDate
                    .filter { it.type == TransactionType.BUY || it.type == TransactionType.SELL }
                    .sumOf { if (it.type == TransactionType.BUY) (it.quantity * it.price + it.fee) else -(it.quantity * it.price - it.fee) }

                totalNetInvestment += stockNetCost

                val stockPriceMap = priceCache[stock.id]
                val priceOnDate = stockPriceMap?.get(currentDate)

                val priceToUse = if (priceOnDate != null) {
                    lastValidPriceMap = lastValidPriceMap.plus(stock.id to priceOnDate)
                    priceOnDate
                } else {
                    lastValidPriceMap[stock.id] ?: 0.0
                }

                totalMarketValue += quantityHeld * priceToUse
            }

            val totalAssets = totalMarketValue + cashBalance
            val plAmount = totalAssets - totalNetInvestment
            val plRate = if (totalNetInvestment > 0) (plAmount / totalNetInvestment) * 100.0 else 0.0

            chartDataPoints.add(StockUiState.ChartDataPoint(currentDate, plRate))

            // --- 计算基准指数涨跌幅 ---
            val benchmarkPrice = benchmarkPrices[currentDate]
            if (benchmarkPrice != null) {
                // 如果是第一天有数据，设置基线
                if (benchmarkBaseline == -1.0) benchmarkBaseline = benchmarkPrice

                val changePercent = if (benchmarkBaseline > 0) {
                    (benchmarkPrice - benchmarkBaseline) / benchmarkBaseline * 100.0
                } else 0.0
                benchmarkDataPoints.add(StockUiState.ChartDataPoint(currentDate, changePercent))
            } else {
                // 如果当天没有基准数据（例如非交易日），沿用上一个值，或者跳过
                if (benchmarkDataPoints.isNotEmpty()) {
                    benchmarkDataPoints.add(StockUiState.ChartDataPoint(currentDate, benchmarkDataPoints.last().value))
                }
            }

            currentDate = currentDate.plusDays(1)
        }

        return@withContext Pair(chartDataPoints, benchmarkDataPoints)
    }

    private suspend fun fetchAndCacheHistoricalPrices(tickers: List<String>, startDate: LocalDate): Map<String, Map<LocalDate, Double>> {
        val priceCache = mutableMapOf<String, Map<LocalDate, Double>>()
        tickers.map { ticker ->
            viewModelScope.async(Dispatchers.IO) {
                if (!historicalPriceCache.containsKey(ticker) || historicalPriceCache[ticker]!!.keys.minOrNull()?.isAfter(startDate) == true) {
                    val data = YahooFinanceScraper.fetchHistoricalData(ticker, startDate)
                    val priceMap = data.associate { it.date to it.closePrice }
                    historicalPriceCache[ticker] = priceMap
                    ticker to priceMap
                } else {
                    ticker to (historicalPriceCache[ticker] ?: emptyMap())
                }
            }
        }.awaitAll().forEach { (ticker, prices) ->
            priceCache[ticker] = prices
        }
        return priceCache
    }

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
        return if (calculatedDate.isBefore(portfolioStartDate)) portfolioStartDate else calculatedDate
    }

    suspend fun fetchInitialStockData(ticker: String): YahooFinanceScraper.ScrapedData? {
        val upperCaseTicker = ticker.uppercase()
        return withContext(Dispatchers.IO) {
            val chineseNameFromDb = stockNameDao.getChineseNameByTicker(upperCaseTicker)
            val scrapedData = YahooFinanceScraper.fetchStockData(upperCaseTicker)

            if (scrapedData != null) {
                val finalName = chineseNameFromDb ?: scrapedData.name
                if (chineseNameFromDb == null && scrapedData.name != upperCaseTicker && scrapedData.name.isNotBlank()) {
                    try {
                        launch {
                            stockNameDao.insertAll(listOf(
                                StockNameEntity(
                                    upperCaseTicker,
                                    scrapedData.name
                                )
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("StockViewModel", "Failed to save fetched name for $upperCaseTicker to DB", e)
                    }
                }
                scrapedData.copy(name = finalName)
            } else {
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


    fun saveOrUpdateTransaction(
        transaction: Transaction,
        stockId: String?,
        newStockIdentifier: String,
        stockName: String,
        exchangeName: String?
    ) {
        viewModelScope.launch {
            saveOrUpdateTransactionInternal(transaction, stockId, newStockIdentifier, stockName, exchangeName)
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    private suspend fun saveOrUpdateTransactionInternal(
        transaction: Transaction,
        stockId: String?,
        newStockIdentifier: String,
        stockName: String,
        exchangeName: String?
    ) {
        val idToProcess = (stockId ?: newStockIdentifier).uppercase()
        if (idToProcess.isBlank()) return

        val originalTransaction = _uiState.value.transactionToEdit
        if (originalTransaction != null) {
            cashDao.deleteByStockTransactionId(originalTransaction.id)
        }


        val existingStock = stockDao.getStockById(idToProcess)
        if (existingStock != null) {
            stockDao.updateStock(existingStock.copy(name = stockName))
            stockDao.insertTransaction(transaction.toEntity(idToProcess))
        } else {
            val formattedExchange = YahooFinanceScraper.formatExchangeName(exchangeName ?: "")
            val displayTicker = if (formattedExchange.isNotBlank()) "$formattedExchange:$idToProcess" else idToProcess

            val newStock = StockHolding(
                id = idToProcess, name = stockName,
                ticker = displayTicker,
                currentPrice = transaction.price, transactions = emptyList()
            )
            stockDao.insertStock(newStock.toEntity())
            stockDao.insertTransaction(transaction.toEntity(newStock.id))
        }

        if (transaction.type == TransactionType.SPLIT) {
            return
        }

        val (finalAmount, finalCashType) = when (transaction.type) {
            TransactionType.BUY -> {
                val totalCost = (transaction.quantity * transaction.price) + transaction.fee
                Pair(totalCost, CashTransactionType.BUY)
            }
            TransactionType.SELL -> {
                val netProceeds = (transaction.quantity * transaction.price) - transaction.fee
                Pair(netProceeds, CashTransactionType.SELL)
            }
            TransactionType.DIVIDEND -> {
                val dividendAmount = transaction.quantity * transaction.price
                if (dividendAmount >= 0) {
                    Pair(dividendAmount, CashTransactionType.DIVIDEND)
                } else {
                    Pair(dividendAmount.absoluteValue, CashTransactionType.WITHDRAWAL)
                }
            }
            else -> Pair(0.0, CashTransactionType.DEPOSIT)
        }

        if (finalAmount.absoluteValue > 1e-9) {
            val cashTransaction = CashTransaction(
                date = transaction.date,
                type = finalCashType,
                amount = finalAmount.absoluteValue,
                stockTransactionId = transaction.id
            )
            cashDao.insertCashTransaction(cashTransaction.toEntity())
        }
    }

    fun savePortfolioName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isNotBlank()) {
                portfolioSettingsDao.insert(PortfolioSettingsEntity(name = name))
            } else {
                _toastEvents.emit("投资组合名称不能为空")
            }
        }
    }

    fun prepareEditCashTransaction(transactionId: String) {
        _uiState.update { it.copy(cashTransactionToEditId = transactionId) }
    }

    fun prepareNewCashTransaction() {
        _uiState.update { it.copy(cashTransactionToEditId = null) }
    }

    fun addCashTransaction(amount: Double, type: CashTransactionType, date: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) {
            if (amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            val cashTransaction = CashTransaction(date = date, type = type, amount = amount)
            cashDao.insertCashTransaction(cashTransaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    fun updateCashTransaction(transaction: CashTransaction) {
        viewModelScope.launch(Dispatchers.IO) {
            if (transaction.amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            cashDao.insertCashTransaction(transaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    fun deleteCashTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val transaction = _uiState.value.cashTransactions.find { it.id == transactionId }
            if (transaction?.stockTransactionId != null) {
                _toastEvents.emit("无法删除：此现金记录已关联到股票交易。")
            } else {
                cashDao.deleteCashTransactionById(transactionId)
                _navigationEvents.emit(NavigationEvent.NavigateBack)
            }
        }
    }


    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stock = _uiState.value.selectedStock
            val transactionToDelete = stock.transactions.find { it.id == transactionId }

            if (transactionToDelete != null) {
                stockDao.deleteTransactionById(transactionId)
                cashDao.deleteByStockTransactionId(transactionId)

                if (transactionToDelete.type == TransactionType.BUY || transactionToDelete.type == TransactionType.SELL) {
                    val specialTypes = listOf(TransactionType.DIVIDEND, TransactionType.SPLIT)

                    val transactionsToRemove = stock.transactions.filter {
                        it.type in specialTypes && it.id != transactionId
                    }

                    transactionsToRemove.forEach { t ->
                        stockDao.deleteTransactionById(t.id)
                        cashDao.deleteByStockTransactionId(t.id)
                    }
                    launch { refreshData() }
                }

                val remainingTransactions = stockDao.getTransactionsByStockId(stock.id)
                if (remainingTransactions.isEmpty()) {
                    stockDao.deleteStockById(stock.id)
                    _uiState.update { it.copy(selectedStockId = null) }
                }

                _navigationEvents.emit(NavigationEvent.NavigateBack)
            }
        }
    }

    fun importTransactionsFromCsv(uri: Uri, stockId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isRefreshing = true) }

                val stockData = fetchInitialStockData(stockId)
                if (stockData == null) {
                    _toastEvents.emit("导入失败：无法获取 $stockId 的股票信息")
                    return@launch
                }

                val stockName = stockData.name
                val exchangeName = stockData.exchangeName
                var importedCount = 0
                var skippedCount = 0

                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->

                        reader.readLine()

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
                refreshData()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to import CSV", e)
                _toastEvents.emit("导入失败：${e.message}")
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }


    fun exportDatabase(targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
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

    fun importDatabase(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isRefreshing = true) }
                val filesCopied = StockDatabase.importDatabase(appContext, sourceUri)

                if (filesCopied > 0) {
                    _priceDataFlow.update { emptyMap() }
                    _toastEvents.emit("数据库恢复成功！正在重新加载数据...")
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
}

class StockViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StockViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StockViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}