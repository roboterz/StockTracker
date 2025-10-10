package com.example.stocktracker

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stocktracker.ui.screens.AddOrEditTransactionScreen
import com.example.stocktracker.ui.screens.PortfolioScreen
import com.example.stocktracker.ui.screens.StockDetailScreen
import com.example.stocktracker.ui.theme.StockTrackerTheme
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory


// --- 主程序入口 (Main Activity) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockTrackerTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val viewModel: StockViewModel = viewModel(
                        factory = StockViewModelFactory(context.applicationContext as Application)
                    )
                    StockApp(viewModel)
                }
            }
        }
    }
}

// --- App导航和状态管理 (App Navigation & State) ---
enum class Screen {
    Portfolio,
    Details,
    AddOrEditTransaction
}

@Composable
fun StockApp(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState.currentScreen) {
        Screen.Portfolio -> PortfolioScreen(
            holdings = uiState.holdings,
            onStockClick = { stock -> viewModel.selectStock(stock.id) },
            onAddClick = { viewModel.prepareNewTransaction() }
        )
        Screen.Details -> {
            StockDetailScreen(
                stock = uiState.selectedStock,
                onBack = { viewModel.navigateBack() },
                onAddTransaction = { viewModel.prepareNewTransaction(uiState.selectedStockId) },
                onTransactionClick = { transaction -> viewModel.prepareEditTransaction(transaction.id) }
            )
        }
        Screen.AddOrEditTransaction -> {
            AddOrEditTransactionScreen(
                stock = uiState.selectedStock,
                transactionToEdit = uiState.transactionToEdit,
                onBack = { viewModel.navigateBack() },
                onSave = { transaction, stockId, newStockId ->
                    viewModel.saveOrUpdateTransaction(transaction, stockId, newStockId)
                },
                onDelete = { transactionId, stockId ->
                    viewModel.deleteTransaction(transactionId, stockId)
                }
            )
        }
    }
}

