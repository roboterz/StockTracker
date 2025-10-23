package com.example.stocktracker.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.database.StockDatabase // Keep this import
import com.example.stocktracker.data.database.StockNameEntity
// *** 移除 StockNameDao 的单独导入，因为它现在通过 StockDatabase 访问 ***
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
import java.time.LocalDate

// ... (NavigationEvent, StockUiState remain the same) ...
sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 0.0,
    val isRefreshing: Boolean = false
) {
    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId } ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }
}


class StockViewModel(application: Application) : ViewModel() {
    private val db = StockDatabase.getDatabase(application) // *** 获取数据库实例 ***
    private val stockDao = db.stockDao()
    private val cashDao = db.cashDao()
    private val stockNameDao = db.stockNameDao() // *** 获取 StockNameDao 实例 ***

    // ... (StateFlows, SharedFlows, init block remain mostly the same) ...
    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()

    private val _priceDataFlow = MutableStateFlow<Map<String, YahooFinanceScraper.ScrapedData>>(emptyMap())
    private var isInitialLoad = true

    init {
        val holdingsFlow = stockDao.getAllStocksWithTransactions().map { list -> list.map { it.toUIModel() } }
        val cashFlow = cashDao.getAllCashTransactions().map { list -> list.map { it.toUIModel() } }

        viewModelScope.launch(Dispatchers.IO) {
            combine(holdingsFlow, cashFlow, _priceDataFlow) { holdingsFromDb, cashTransactions, priceDataMap ->
                val cashBalance = cashTransactions.sumOf {
                    if (it.type == CashTransactionType.DEPOSIT) it.amount else -it.amount
                }

                val finalHoldings = holdingsFromDb.map { dbHolding ->
                    priceDataMap[dbHolding.id]?.let { prices ->
                        val dailyPL = (prices.currentPrice - prices.previousClose) * dbHolding.totalQuantity
                        val dailyPLPercent = if (prices.previousClose != 0.0) (prices.currentPrice - prices.previousClose) / prices.previousClose * 100 else 0.0
                        dbHolding.copy(
                            currentPrice = prices.currentPrice,
                            dailyPL = dailyPL,
                            dailyPLPercent = dailyPLPercent
                            // *** 确保名称不会被网络请求覆盖（如果本地有的话）***
                            // name = priceDataMap[dbHolding.id]?.name ?: dbHolding.name // Optional: keep existing name if network fails?
                        )
                    } ?: dbHolding
                }

                _uiState.update { it.copy(holdings = finalHoldings, cashBalance = cashBalance) }

                if (isInitialLoad && finalHoldings.isNotEmpty()) {
                    isInitialLoad = false
                    refreshData()
                }
            }
                .catch { throwable ->
                    Log.e("StockViewModel", "Error in combine flow", throwable)
                    _toastEvents.emit("数据加载时发生错误")
                }
                .collect()
        }
    }


    // ... (refreshData remains mostly the same, ensuring names aren't overwritten unnecessarily) ...
    fun refreshData() {
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val holdingsToRefresh = _uiState.value.holdings.filter { it.totalQuantity > 0 }

            try {
                if (holdingsToRefresh.isEmpty()) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@launch
                }

                val deferredJobs = holdingsToRefresh.map { holding ->
                    async(Dispatchers.IO) {
                        // *** 在刷新时也尝试从数据库获取名称，以防 StockHolding 对象中的名称过时 ***
                        val dbName = stockNameDao.getChineseNameByTicker(holding.id.uppercase())
                        val priceData = YahooFinanceScraper.fetchStockData(holding.id)
                        val finalName = dbName ?: priceData?.name ?: holding.name // 优先使用数据库名称

                        val firstTransactionDate = holding.transactions.minOfOrNull { it.date }
                        val dividendHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchDividendHistory(holding.id, firstTransactionDate) else null
                        val splitHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchSplitHistory(holding.id, firstTransactionDate) else null

                        // *** 将最终确定的名称传递下去 ***
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
                                dbWriteJobs.add(launch(Dispatchers.IO) { saveOrUpdateTransactionInternal(dividendTransaction, data.holding.id, "", data.holding.name) })
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
                            dbWriteJobs.add(launch(Dispatchers.IO) { saveOrUpdateTransactionInternal(splitTransaction, data.holding.id, "", data.holding.name) })
                        }
                    }
                }

                dbWriteJobs.joinAll()

                results.forEach { data ->
                    data.priceData?.let {
                        successfulFetches++
                        // *** 更新股票时也更新名称 ***
                        stockDao.updateStock(data.holding.toEntity().copy(currentPrice = it.currentPrice, name = data.holding.name))
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


    // *** 修改 fetchInitialStockData 函数 ***
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


    // ... (selectStock, prepareNewTransaction, prepareEditTransaction remain the same) ...
    fun selectStock(stockId: String) {
        _uiState.update { it.copy(selectedStockId = stockId) }
    }

    fun prepareNewTransaction(stockId: String? = null) {
        _uiState.update { it.copy(selectedStockId = stockId, transactionToEditId = null) }
    }

    fun prepareEditTransaction(transactionId: String) {
        _uiState.update { it.copy(transactionToEditId = transactionId) }
    }


    // *** 修改 saveOrUpdateTransaction 和 Internal 函数以确保使用正确的名称 ***
    fun saveOrUpdateTransaction(
        transaction: Transaction,
        stockId: String?, // Ticker of existing stock being edited, or null if adding new
        newStockIdentifier: String, // Ticker entered by user if adding new
        stockName: String // Name (either from DB/fetch or entered by user)
    ) {
        viewModelScope.launch {
            // *** 确保传递的 stockName 是最终确定的名称 ***
            saveOrUpdateTransactionInternal(transaction, stockId, newStockIdentifier, stockName)
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    private suspend fun saveOrUpdateTransactionInternal(
        transaction: Transaction,
        stockId: String?,
        newStockIdentifier: String,
        stockName: String // *** 接收最终确定的名称 ***
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
            // *** 更新股票时使用传入的 stockName ***
            stockDao.updateStock(existingStock.copy(name = stockName))
            stockDao.insertTransaction(transaction.toEntity(idToProcess))
        } else {
            // *** 创建新股票时使用传入的 stockName ***
            val newStock = StockHolding(
                id = idToProcess, name = stockName, ticker = idToProcess, // Use idToProcess also for ticker initially
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
            val cashType = if (amount > 0) CashTransactionType.DEPOSIT else CashTransactionType.WITHDRAWAL
            val cashTransaction = CashTransaction(
                date = transaction.date,
                type = cashType,
                amount = kotlin.math.abs(amount), // Amount should always be positive
                stockTransactionId = transaction.id // Link to the stock transaction
            )
            cashDao.insertCashTransaction(cashTransaction.toEntity())
        }
    }


    // ... (addCashTransaction, deleteTransaction remain the same) ...
    fun addCashTransaction(amount: Double, type: CashTransactionType) {
        viewModelScope.launch(Dispatchers.IO) {
            if (amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            val cashTransaction = CashTransaction(date = LocalDate.now(), type = type, amount = amount)
            cashDao.insertCashTransaction(cashTransaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            stockDao.deleteTransactionById(transactionId)
            cashDao.deleteByStockTransactionId(transactionId) // Also delete linked cash transaction
            _navigationEvents.emit(NavigationEvent.NavigateBack) // Navigate back after deletion
        }
    }

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

