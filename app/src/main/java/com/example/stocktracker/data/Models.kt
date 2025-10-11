package com.example.stocktracker.data

import java.time.LocalDate
import java.util.UUID

// --- UI 数据模型 (UI Data Models) ---

enum class TransactionType {
    BUY, SELL, DIVIDEND
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Int,
    val price: Double,
    val fee: Double = 0.0
)

data class StockHolding(
    val id: String,
    val name: String,
    val ticker: String,
    val currentPrice: Double,
    val transactions: List<Transaction>,
    // 新增属性以匹配UI需求
    val dailyPL: Double = 0.0,
    val dailyPLPercent: Double = 0.0,
    val holdingPL: Double = 0.0,
    val holdingPLPercent: Double = 0.0,
    val cumulativeDividend: Double = 0.0
) {
    val totalQuantity: Int
        get() = transactions.sumOf { if (it.type == TransactionType.BUY) it.quantity else if(it.type == TransactionType.SELL) -it.quantity else 0 }

    val totalCost: Double
        get() = transactions.filter { it.type == TransactionType.BUY }.sumOf { it.quantity * it.price + it.fee }

    val totalSoldValue: Double
        get() = transactions.filter { it.type == TransactionType.SELL }.sumOf { it.quantity * it.price - it.fee }

    val costBasis: Double
        get() = if (totalQuantity > 0) (totalCost - totalSoldValue) / totalQuantity else 0.0

    val marketValue: Double
        get() = totalQuantity * currentPrice

    val totalPL: Double
        get() = marketValue - (totalCost - totalSoldValue)

    val totalPLPercent: Double
        get() = if ((totalCost - totalSoldValue) > 0) totalPL / (totalCost - totalSoldValue) * 100 else 0.0

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

