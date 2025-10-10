package com.example.stocktracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.Screen
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.database.StockDatabase
import com.example.stocktracker.data.toEntity
import com.example.stocktracker.data.toUIModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// --- ViewModel ---

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val currentScreen: Screen = Screen.Portfolio,
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null
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

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun selectStock(stockId: String) {
        _uiState.update { it.copy(selectedStockId = stockId, currentScreen = Screen.Details) }
    }

    fun prepareNewTransaction(stockId: String? = null) {
        _uiState.update {
            it.copy(
                selectedStockId = stockId, // Bug fix: Explicitly set or clear the stock ID
                transactionToEditId = null,
                currentScreen = Screen.AddOrEditTransaction
            )
        }
    }

    fun prepareEditTransaction(transactionId: String) {
        _uiState.update {
            it.copy(
                transactionToEditId = transactionId,
                currentScreen = Screen.AddOrEditTransaction
            )
        }
    }

    fun navigateBack() {
        val currentState = _uiState.value
        val newScreen = when (currentState.currentScreen) {
            Screen.AddOrEditTransaction -> if (currentState.selectedStockId != null) Screen.Details else Screen.Portfolio
            Screen.Details -> Screen.Portfolio
            else -> Screen.Portfolio
        }
        _uiState.update { it.copy(currentScreen = newScreen, transactionToEditId = null) }
    }

    fun saveOrUpdateTransaction(transaction: Transaction, stockId: String?, newStockIdentifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val idToProcess = (stockId ?: newStockIdentifier).uppercase()
            if (idToProcess.isBlank()) return@launch

            val existingStock = _uiState.value.holdings.find { it.id.equals(idToProcess, ignoreCase = true) }

            if (existingStock != null) {
                // It's an existing stock, just insert or update the transaction
                dao.insertTransaction(transaction.toEntity(existingStock.id))
            } else {
                // It's a new stock
                val newStock = StockHolding(
                    id = idToProcess,
                    name = newStockIdentifier,
                    ticker = "NASDAQ:$idToProcess",
                    currentPrice = transaction.price,
                    transactions = emptyList()
                )
                dao.insertStock(newStock.toEntity())
                dao.insertTransaction(transaction.toEntity(newStock.id))
            }
            navigateBack()
        }
    }

    fun deleteTransaction(transactionId: String, stockId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTransactionById(transactionId)
            navigateBack()
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
