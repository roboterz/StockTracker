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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.time.LocalDate

// --- 用于从ViewModel发送到UI的单次导航事件 ---
sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 0.0, // 初始值设为0
    val isRefreshing: Boolean = false
) {
    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId } ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }
}

class StockViewModel(application: Application) : ViewModel() {
    private val stockDao = StockDatabase.getDatabase(application).stockDao()
    private val cashDao = StockDatabase.getDatabase(application).cashDao() // 新增

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()

    private var isInitialLoad = true

    init {
        // 合并持仓和现金流
        val holdingsFlow = stockDao.getAllStocksWithTransactions().map { list -> list.map { it.toUIModel() } }
        val cashFlow = cashDao.getAllCashTransactions().map { list -> list.map { it.toUIModel() } }

        viewModelScope.launch(Dispatchers.IO) {
            combine(holdingsFlow, cashFlow) { holdings, cashTransactions ->
                // 计算现金余额
                val cashBalance = cashTransactions.sumOf {
                    if (it.type == CashTransactionType.DEPOSIT) it.amount else -it.amount
                }
                Pair(holdings, cashBalance)
            }
                .catch { throwable ->
                    Log.e("StockViewModel", "Error collecting from DB", throwable)
                    _toastEvents.emit("无法从数据库加载数据")
                }
                .collect { (holdingsFromDb, cashBalance) ->
                    val currentHoldingsMap = _uiState.value.holdings.associateBy { it.id }
                    val mergedHoldings = holdingsFromDb.map { dbHolding ->
                        currentHoldingsMap[dbHolding.id]?.let { existingUiHolding ->
                            dbHolding.copy(
                                dailyPL = existingUiHolding.dailyPL,
                                dailyPLPercent = existingUiHolding.dailyPLPercent
                            )
                        } ?: dbHolding
                    }

                    _uiState.update { it.copy(holdings = mergedHoldings, cashBalance = cashBalance) }

                    if (isInitialLoad && mergedHoldings.isNotEmpty()) {
                        isInitialLoad = false
                        refreshData()
                    }
                }
        }
    }


    fun refreshData() {
        if (_uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            var successfulFetches = 0
            val activeHoldings = _uiState.value.holdings.filter { it.totalQuantity > 0 }

            try {
                if (activeHoldings.isEmpty()) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@launch
                }

                val deferredData = activeHoldings.map { holding ->
                    async(Dispatchers.IO) {
                        Log.d("StockViewModel", "Fetching data for ${holding.id}")
                        val data = YahooFinanceScraper.fetchStockData(holding.id)
                        Log.d("StockViewModel", "Data for ${holding.id}: $data")
                        holding.id to data
                    }
                }

                val scrapedDataMap = deferredData.awaitAll().toMap()

                val updatedHoldingsList = _uiState.value.holdings.map { holding ->
                    scrapedDataMap[holding.id]?.let { scrapedData ->
                        successfulFetches++
                        val dailyPL = (scrapedData.currentPrice - scrapedData.previousClose) * holding.totalQuantity
                        val dailyPLPercent = if (scrapedData.previousClose != 0.0) (scrapedData.currentPrice - scrapedData.previousClose) / scrapedData.previousClose * 100 else 0.0

                        holding.copy(
                            currentPrice = scrapedData.currentPrice,
                            dailyPL = dailyPL,
                            dailyPLPercent = dailyPLPercent
                        )
                    } ?: holding
                }

                _uiState.update { it.copy(holdings = updatedHoldingsList) }

                val entitiesToUpdate = updatedHoldingsList
                    .filter { scrapedDataMap.containsKey(it.id) }
                    .map { it.toEntity() }

                if (entitiesToUpdate.isNotEmpty()) {
                    entitiesToUpdate.forEach { stockDao.updateStock(it) }
                }

                if (successfulFetches == 0 && activeHoldings.isNotEmpty()) {
                    _toastEvents.emit("未能获取任何股票的最新价格")
                } else if (successfulFetches < activeHoldings.size) {
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
        _uiState.update {
            it.copy(
                selectedStockId = stockId,
                transactionToEditId = null
            )
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            val idToProcess = (stockId ?: newStockIdentifier).uppercase()
            if (idToProcess.isBlank()) return@launch

            // 如果是编辑操作，先删除旧的关联现金交易
            val originalTransaction = _uiState.value.transactionToEdit
            if (originalTransaction != null) {
                cashDao.deleteByStockTransactionId(originalTransaction.id)
            }

            // 保存股票和交易记录
            val existingStock = _uiState.value.holdings.find { it.id.equals(idToProcess, ignoreCase = true) }

            if (existingStock != null) {
                val updatedStockEntity = existingStock.toEntity().copy(name = stockName)
                stockDao.updateStock(updatedStockEntity)
                stockDao.insertTransaction(transaction.toEntity(existingStock.id))
            } else {
                val newStock = StockHolding(
                    id = idToProcess,
                    name = stockName,
                    ticker = "NASDAQ:$idToProcess",
                    currentPrice = transaction.price,
                    transactions = emptyList()
                )
                stockDao.insertStock(newStock.toEntity())
                stockDao.insertTransaction(transaction.toEntity(newStock.id))
            }

            // 根据新的股票交易创建新的现金交易
            val cashTransaction = when (transaction.type) {
                TransactionType.BUY -> CashTransaction(
                    date = transaction.date,
                    type = CashTransactionType.WITHDRAWAL,
                    amount = (transaction.quantity * transaction.price) + transaction.fee,
                    stockTransactionId = transaction.id
                )
                TransactionType.SELL -> CashTransaction(
                    date = transaction.date,
                    type = CashTransactionType.DEPOSIT,
                    amount = (transaction.quantity * transaction.price) - transaction.fee,
                    stockTransactionId = transaction.id
                )
                TransactionType.DIVIDEND -> CashTransaction(
                    date = transaction.date,
                    type = CashTransactionType.DEPOSIT,
                    amount = transaction.quantity * transaction.price, // 对于分红，price 代表每股股息
                    stockTransactionId = transaction.id
                )
            }
            cashDao.insertCashTransaction(cashTransaction.toEntity())


            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    // 新增：保存现金交易
    fun addCashTransaction(amount: Double, type: CashTransactionType) {
        viewModelScope.launch(Dispatchers.IO) {
            if (amount <= 0) {
                _toastEvents.emit("金额必须大于0")
                return@launch
            }
            val cashTransaction = CashTransaction(
                date = LocalDate.now(),
                type = type,
                amount = amount
            )
            cashDao.insertCashTransaction(cashTransaction.toEntity())
            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 先删除股票交易
            stockDao.deleteTransactionById(transactionId)
            // 再删除关联的现金交易
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

