package com.example.stocktracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.database.StockDatabase
import com.example.stocktracker.data.toEntity
import com.example.stocktracker.data.toUIModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// --- 用于从ViewModel发送到UI的单次导航事件 ---
sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null,
    val cashBalance: Double = 5936.35 // 新增现金余额字段
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

    // SharedFlow用于发送一次性的导航事件
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()


    init {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllStocksWithTransactions()
                .map { list -> list.map { it.toUIModel() } }
                .catch { throwable ->
                    // Handle error
                }
                .collect { holdings ->
                    _uiState.update { it.copy(holdings = holdings) }
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
                // 更新现有股票的名称
                val updatedStockEntity = existingStock.toEntity().copy(name = stockName)
                dao.insertStock(updatedStockEntity) // OnConflictStrategy.REPLACE会处理更新
                // 插入或更新交易
                dao.insertTransaction(transaction.toEntity(existingStock.id))
            } else {
                // 这是一个新股票
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
            // 发送导航事件，而不是直接控制UI
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

