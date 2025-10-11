package com.example.stocktracker.ui.screens

import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.ui.viewmodel.StockUiState
import java.util.UUID

sealed class PortfolioListItem {
    abstract val id: String

    data class Header(val uiState: StockUiState) : PortfolioListItem() {
        override val id: String = "header"
    }

    data class Chart(val holdings: List<StockHolding>) : PortfolioListItem() {
        override val id: String = "chart"
    }

    data class Stock(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = stock.id
    }
}