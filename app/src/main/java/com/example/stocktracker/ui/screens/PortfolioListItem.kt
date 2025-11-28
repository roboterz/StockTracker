package com.example.stocktracker.ui.screens

import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.ui.viewmodel.StockUiState
import com.example.stocktracker.ui.viewmodel.TimeRange

enum class AssetType {
    HOLDINGS, CLOSED, CASH
}

sealed class PortfolioListItem {
    abstract val id: String

    data class Header(val uiState: StockUiState) : PortfolioListItem() {
        override val id: String = "header"
    }

    data class ProfitLossChart(
        val chartData: List<StockUiState.ChartDataPoint>,
        // *** 新增：基准数据 (NASDAQ) ***
        val benchmarkData: List<StockUiState.ChartDataPoint>,
        val selectedRange: TimeRange,
        val isLoading: Boolean
    ) : PortfolioListItem() {
        override val id: String = "pl_chart"
    }

    data class Chart(val holdings: List<StockHolding>, val selectedType: AssetType) : PortfolioListItem() {
        override val id: String = "chart"
    }

    object StockHeader : PortfolioListItem() {
        override val id: String = "stock_header"
    }

    data class Stock(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = stock.id
    }

    object ClosedPositionHeader : PortfolioListItem() {
        override val id: String = "closed_header"
    }

    data class ClosedPosition(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = "closed_${stock.id}"
    }

    object CashHeader : PortfolioListItem() {
        override val id: String = "cash_header"
    }

    data class Cash(val transaction: CashTransaction) : PortfolioListItem() {
        override val id: String = "cash_${transaction.id}"
    }
}