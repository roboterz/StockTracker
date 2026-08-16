package com.example.stocktracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.Portfolio
import com.example.stocktracker.data.PortfolioSummary
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.database.PortfolioEntity
import com.example.stocktracker.data.database.StockDatabase
import com.example.stocktracker.data.toUIModel
import com.example.stocktracker.scraper.YahooFinanceScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.absoluteValue

class PortfolioListViewModel(application: Application) : AndroidViewModel(application) {
    private val db = StockDatabase.getDatabase(application)
    private val portfolioDao = db.portfolioDao()
    private val stockDao = db.stockDao()
    private val cashDao = db.cashDao()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()

    private val _priceDataFlow = MutableStateFlow<Map<String, YahooFinanceScraper.ScrapedData>>(emptyMap())

    init {
        // 监听持仓变化，当股票列表发生变化时自动刷新价格
        viewModelScope.launch {
            var lastTickers = emptySet<String>()
            portfolioDao.getPortfoliosWithHoldings().collect { portfolios ->
                val currentTickers = portfolios.flatMap { it.holdings }.map { it.stock.id }.toSet()
                if (currentTickers != lastTickers || (currentTickers.isNotEmpty() && _priceDataFlow.value.isEmpty())) {
                    lastTickers = currentTickers
                    refreshAll()
                }
            }
        }
    }

    val summaries: StateFlow<List<PortfolioSummary>> = combine(
        portfolioDao.getPortfoliosWithHoldings(),
        cashDao.getCashTransactionsByAllPortfolios(),
        _priceDataFlow
    ) { portfoliosWithHoldings, allCash, priceDataMap ->
        val cashMap = allCash.groupBy { it.portfolioId }
        
        portfoliosWithHoldings.map { pWithH ->
            val portfolioId = pWithH.portfolio.id
            val holdings = pWithH.holdings.map { it.toUIModel() }
            val portfolioCash = cashMap[portfolioId] ?: emptyList()
            
            val cashBalance = portfolioCash.sumOf {
                when (it.type) {
                    CashTransactionType.DEPOSIT,
                    CashTransactionType.SELL,
                    CashTransactionType.DIVIDEND -> it.amount
                    CashTransactionType.WITHDRAWAL,
                    CashTransactionType.BUY -> -it.amount
                    CashTransactionType.SPLIT -> 0.0
                }
            }
            
            calculateSummary(pWithH.portfolio.toUIModel(), holdings, cashBalance, priceDataMap)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    private fun calculateSummary(
        portfolio: Portfolio,
        holdings: List<com.example.stocktracker.data.StockHolding>,
        cashBalance: Double,
        priceDataMap: Map<String, YahooFinanceScraper.ScrapedData>
    ): PortfolioSummary {
        var totalStockValue = 0.0
        var totalDailyPL = 0.0
        var totalPL = 0.0
        var totalInvestment = 0.0

        val today = LocalDate.now()

        holdings.forEach { holding ->
            val tickerId = holding.id
            val prices = priceDataMap[tickerId]
            val currentPrice = prices?.currentPrice ?: holding.currentPrice
            val marketValue = holding.totalQuantity * currentPrice
            
            totalStockValue += marketValue
            
            // Daily PL
            if (prices != null) {
                val overnightQuantity = holding.getQuantityOnDate(today.minusDays(1))
                val hasTransactionsToday = holding.transactions.any { it.date == today }
                
                if (overnightQuantity != 0.0 || hasTransactionsToday) {
                    val overnightValueAtClose = overnightQuantity * prices.previousClose
                    
                    var netCashInvestedToday = 0.0
                    holding.transactions.filter { it.date == today }.forEach { t ->
                        if (t.type == TransactionType.BUY) netCashInvestedToday += (t.quantity * t.price) + t.fee
                        if (t.type == TransactionType.SELL) netCashInvestedToday -= (t.quantity * t.price) - t.fee
                    }
                    
                    val dailyPL = marketValue - overnightValueAtClose - netCashInvestedToday
                    val todayDividend = holding.transactions.filter { it.date == today && it.type == TransactionType.DIVIDEND }.sumOf { it.quantity * it.price }
                    totalDailyPL += (dailyPL + todayDividend)
                }
            }
            
            val updatedHolding = holding.copy(currentPrice = currentPrice)
            totalPL += updatedHolding.totalPL
            totalInvestment += holding.totalCostOfAllBuys
        }

        val totalPortfolioValue = totalStockValue + cashBalance
        val totalDailyPLPercent = if (totalStockValue - totalDailyPL != 0.0) (totalDailyPL / (totalStockValue - totalDailyPL)) * 100 else 0.0
        val totalPLPercent = if (totalInvestment > 0) (totalPL / totalInvestment) * 100 else 0.0

        return PortfolioSummary(
            portfolio = portfolio,
            totalMarketValue = totalPortfolioValue,
            dailyPL = totalDailyPL,
            dailyPLPercent = totalDailyPLPercent,
            totalPL = totalPL,
            totalPLPercent = totalPLPercent
        )
    }

    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                val portfolios = portfolioDao.getPortfoliosWithHoldings().first()
                val tickers = portfolios.flatMap { it.holdings }.map { it.stock.id }.distinct()
                Log.d("PortfolioListViewModel", "Refreshing all tickers: $tickers")

                val newPriceData = tickers.map { ticker ->
                    async { ticker to YahooFinanceScraper.fetchStockData(ticker) }
                }.awaitAll().filter { it.second != null }.associate { it.first to it.second!! }

                _priceDataFlow.value = newPriceData
            } catch (e: Exception) {
                Log.e("PortfolioListViewModel", "Refresh failed", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun addPortfolio(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            portfolioDao.insert(PortfolioEntity(name = name))
        }
    }

    fun deletePortfolio(portfolio: Portfolio) {
        viewModelScope.launch(Dispatchers.IO) {
            portfolioDao.delete(PortfolioEntity(id = portfolio.id, name = portfolio.name))
        }
    }

    fun exportDatabase(targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                StockDatabase.runCheckpoint(getApplication())
                val filesCopied = StockDatabase.exportDatabase(getApplication(), targetUri)
                if (filesCopied > 0) {
                    _toastEvents.emit("数据库备份成功！文件已保存。")
                } else {
                    _toastEvents.emit("备份失败：未找到主数据库文件。")
                }
            } catch (e: Exception) {
                Log.e("PortfolioListViewModel", "Database export failed", e)
                _toastEvents.emit("备份失败：${e.localizedMessage}")
            }
        }
    }

    fun importDatabase(sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isRefreshing.value = true
                val filesCopied = StockDatabase.importDatabase(getApplication(), sourceUri)
                if (filesCopied > 0) {
                    _priceDataFlow.value = emptyMap()
                    _toastEvents.emit("数据库恢复成功！正在重新加载数据...")
                    refreshAll()
                } else {
                    _toastEvents.emit("恢复失败：未找到备份文件或文件内容为空。")
                }
            } catch (e: Exception) {
                Log.e("PortfolioListViewModel", "Database import failed", e)
                _toastEvents.emit("恢复失败：${e.localizedMessage}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
