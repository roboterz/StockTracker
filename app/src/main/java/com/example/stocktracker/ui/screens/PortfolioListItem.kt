package com.example.stocktracker.ui.screens

import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.ui.viewmodel.StockUiState

// *** 新增：定义资类型枚举 ***
enum class AssetType {
    HOLDINGS, CLOSED, CASH
}

sealed class PortfolioListItem {
    abstract val id: String

    data class Header(val uiState: StockUiState) : PortfolioListItem() {
        override val id: String = "header"
    }

    data class ProfitLossChart(val placeholder: Boolean = true) : PortfolioListItem() {
        override val id: String = "pl_chart"
    }

    // *** 修改：Chart 项现在包含所选的类型，以便 ViewHolder 更新按钮状态 ***
    data class Chart(val holdings: List<StockHolding>, val selectedType: AssetType) : PortfolioListItem() {
        override val id: String = "chart"
    }

    // --- 持仓明细 ---
    object StockHeader : PortfolioListItem() {
        override val id: String = "stock_header"
    }

    data class Stock(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = stock.id
    }

    // --- 新增：平仓明细 ---
    object ClosedPositionHeader : PortfolioListItem() {
        override val id: String = "closed_header"
    }

    data class ClosedPosition(val stock: StockHolding) : PortfolioListItem() {
        override val id: String = "closed_${stock.id}"
    }

    // --- 新增：现金明细 ---
    object CashHeader : PortfolioListItem() {
        override val id: String = "cash_header"
    }

    data class Cash(val transaction: CashTransaction) : PortfolioListItem() {
        override val id: String = "cash_${transaction.id}"
    }
}

