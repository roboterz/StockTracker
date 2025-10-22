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
import com.example.stocktracker.data.database.StockDatabase
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
import java.time.LocalDate

// --- 用于从ViewModel发送到UI的单次导航事件 ---
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
    private val stockDao = StockDatabase.getDatabase(application).stockDao()
    private val cashDao = StockDatabase.getDatabase(application).cashDao()

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
                        val priceData = YahooFinanceScraper.fetchStockData(holding.id)
                        val firstTransactionDate = holding.transactions.minOfOrNull { it.date }
                        val dividendHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchDividendHistory(holding.id, firstTransactionDate) else null
                        // 新增：获取拆股历史
                        val splitHistory = if (firstTransactionDate != null) YahooFinanceScraper.fetchSplitHistory(holding.id, firstTransactionDate) else null

                        // 将所有数据打包
                        object {
                            val holding = holding
                            val priceData = priceData
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
                    // 处理分红
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

                    // 处理拆股/合股
                    data.splitHistory?.forEach { splitInfo ->
                        val alreadyExists = data.holding.transactions.any { it.type == TransactionType.SPLIT && it.date == splitInfo.date }
                        if (!alreadyExists) {
                            val splitTransaction = Transaction(
                                date = splitInfo.date,
                                type = TransactionType.SPLIT,
                                quantity = splitInfo.numerator.toInt(), // 分子
                                price = splitInfo.denominator          // 分母
                            )
                            dbWriteJobs.add(launch(Dispatchers.IO) { saveOrUpdateTransactionInternal(splitTransaction, data.holding.id, "", data.holding.name) })
                        }
                    }
                }

                dbWriteJobs.joinAll()

                results.forEach { data ->
                    data.priceData?.let {
                        successfulFetches++
                        stockDao.updateStock(data.holding.toEntity().copy(currentPrice = it.currentPrice))
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
        stockName: String
    ) {
        viewModelScope.launch {
            saveOrUpdateTransactionInternal(transaction, stockId, newStockIdentifier, stockName)
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    private suspend fun saveOrUpdateTransactionInternal(
        transaction: Transaction,
        stockId: String?,
        newStockIdentifier: String,
        stockName: String
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
            val newStock = StockHolding(
                id = idToProcess, name = stockName, ticker = idToProcess,
                currentPrice = transaction.price, transactions = emptyList()
            )
            stockDao.insertStock(newStock.toEntity())
            stockDao.insertTransaction(transaction.toEntity(newStock.id))
        }

        // 拆股/合股事件不影响现金
        if (transaction.type == TransactionType.SPLIT) {
            return
        }

        val cashTransaction = when (transaction.type) {
            TransactionType.BUY -> CashTransaction(date = transaction.date, type = CashTransactionType.WITHDRAWAL, amount = (transaction.quantity * transaction.price) + transaction.fee, stockTransactionId = transaction.id)
            TransactionType.SELL -> CashTransaction(date = transaction.date, type = CashTransactionType.DEPOSIT, amount = (transaction.quantity * transaction.price) - transaction.fee, stockTransactionId = transaction.id)
            TransactionType.DIVIDEND -> CashTransaction(date = transaction.date, type = CashTransactionType.DEPOSIT, amount = transaction.quantity * transaction.price, stockTransactionId = transaction.id)
            else -> null
        }
        cashTransaction?.let { cashDao.insertCashTransaction(it.toEntity()) }
    }

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
            cashDao.deleteByStockTransactionId(transactionId)
            _navigationEvents.emit(NavigationEvent.NavigateBack)
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

