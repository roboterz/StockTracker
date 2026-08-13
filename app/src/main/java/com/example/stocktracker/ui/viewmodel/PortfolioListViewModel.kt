package com.example.stocktracker.ui.viewmodel

import android.app.Application
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

    private val _priceDataFlow = MutableStateFlow<Map<String, YahooFinanceScraper.ScrapedData>>(emptyMap())

    init {
        // 初始加载时刷新价格
        viewModelScope.launch {
            portfolioDao.getPortfoliosWithHoldings().firstOrNull()?.let {
                refreshAll()
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
            val ticker = YahooFinanceScraper.extractTicker(holding.id)
            val prices = priceDataMap[ticker]
            val currentPrice = prices?.currentPrice ?: holding.currentPrice
            val marketValue = holding.totalQuantity * currentPrice
            
            totalStockValue += marketValue
            
            // Daily PL
            if (prices != null && holding.totalQuantity > 0) {
                val overnightQuantity = holding.getQuantityOnDate(today.minusDays(1))
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
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val portfolios = portfolioDao.getPortfoliosWithHoldings().first()
                val tickers = portfolios.flatMap { it.holdings }.map { YahooFinanceScraper.extractTicker(it.stock.id) }.distinct()
                Log.d("PortfolioListViewModel", "Refreshing all tickers: $tickers")
                
                val newPriceData = mutableMapOf<String, YahooFinanceScraper.ScrapedData>()
                tickers.forEach { ticker ->
                    YahooFinanceScraper.fetchStockData(ticker)?.let {
                        newPriceData[ticker] = it
                    }
                }
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
}
