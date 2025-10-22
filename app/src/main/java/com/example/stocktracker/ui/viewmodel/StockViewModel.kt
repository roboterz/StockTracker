package com.example.stocktracker.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.database.StockDatabase
import com.example.stocktracker.data.toEntity
import com.example.stocktracker.data.toUIModel
import com.example.stocktracker.scraper.YahooFinanceScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

// --- 用于从ViewModel发送到UI的单次导航事件 ---
sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 5936.35,
    val isRefreshing: Boolean = false
) {
    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId } ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }
}

class StockViewModel(application: Application) : ViewModel() {
    private val dao = StockDatabase.getDatabase(application).stockDao()

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllStocksWithTransactions()
                .map { list -> list.map { it.toUIModel() } }
                .catch { throwable ->
                    Log.e("StockViewModel", "Error collecting from DB", throwable)
                    _toastEvents.emit("无法从数据库加载数据")
                }
                .collect { holdings ->
                    _uiState.update { it.copy(holdings = holdings) }
                    if (holdings.isNotEmpty()) {
                        refreshData()
                    }
                }
        }
    }

    /**
     * 刷新所有持仓股票的价格数据。
     */
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
                val entitiesToUpdate = mutableListOf<com.example.stocktracker.data.database.StockHoldingEntity>()

                _uiState.value.holdings.forEach { holding ->
                    val scrapedData = scrapedDataMap[holding.id]
                    if (scrapedData != null) {
                        successfulFetches++
                        val dailyPL = (scrapedData.currentPrice - scrapedData.previousClose) * holding.totalQuantity
                        val dailyPLPercent = if (scrapedData.previousClose != 0.0) (scrapedData.currentPrice - scrapedData.previousClose) / scrapedData.previousClose * 100 else 0.0

                        val updatedHolding = holding.copy(
                            currentPrice = scrapedData.currentPrice,
                            dailyPL = dailyPL,
                            dailyPLPercent = dailyPLPercent
                        )
                        entitiesToUpdate.add(updatedHolding.toEntity())
                    }
                }

                if (entitiesToUpdate.isNotEmpty()) {
                    entitiesToUpdate.forEach { dao.updateStock(it) }
                }

                // 新增：检查是否有任何抓取成功
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

            val existingStock = _uiState.value.holdings.find { it.id.equals(idToProcess, ignoreCase = true) }

            if (existingStock != null) {
                val updatedStockEntity = existingStock.toEntity().copy(name = stockName)
                dao.updateStock(updatedStockEntity)
                dao.insertTransaction(transaction.toEntity(existingStock.id))
            } else {
                val newStock = StockHolding(
                    id = idToProcess,
                    name = stockName,
                    ticker = "NASDAQ:$idToProcess",
                    currentPrice = transaction.price,
                    transactions = emptyList()
                )
                dao.insertStock(newStock.toEntity())
                dao.insertTransaction(transaction.toEntity(newStock.id))
            }

            _navigationEvents.emit(NavigationEvent.NavigateBack)
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTransactionById(transactionId)
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

