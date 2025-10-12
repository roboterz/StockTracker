package com.example.stocktracker.ui.screens

import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.ui.viewmodel.StockUiState

sealed class PortfolioListItem {
    abstract val id: String

    data class Header(val uiState: StockUiState) : PortfolioListItem() {
        override val id: String = "header"
    }

    data class ProfitLossChart(val placeholder: Boolean = true) : PortfolioListItem() {
        override val id: String = "pl_chart"
    }

    data class Chart(val holdings: List<StockHolding>) : PortfolioListItem() {
        override val id: String = "chart"
    }

    data class Stock(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = stock.id
    }
}
