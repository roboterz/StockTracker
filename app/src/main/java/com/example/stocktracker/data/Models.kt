package com.example.stocktracker.data

import java.time.LocalDate
import java.util.UUID
import kotlin.math.min

// --- UI 数据模型 (UI Data Models) ---

enum class TransactionType {
    BUY, SELL, DIVIDEND
}

// 新增：现金交易类型
enum class CashTransactionType {
    DEPOSIT, WITHDRAWAL
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Int,
    val price: Double,
    val fee: Double = 0.0
)

// 新增：现金交易数据类
data class CashTransaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: CashTransactionType,
    val amount: Double,
    val stockTransactionId: String? = null // 用于关联股票交易
)

data class StockHolding(
    val id: String,
    val name: String,
    val ticker: String,
    val currentPrice: Double,
    val transactions: List<Transaction>,
    val dailyPL: Double = 0.0,
    val dailyPLPercent: Double = 0.0,
    val cumulativeDividend: Double = 0.0
) {
    // --- 新增的私有属性，用于实现 FIFO 成本计算 ---
    private val fifoCostOfCurrentHoldings: Double
        get() {
            if (totalQuantity <= 0) return 0.0

            val sortedBuys = transactions.filter { it.type == TransactionType.BUY }.sortedBy { it.date }
            var sharesToAccountFor = transactions.filter { it.type == TransactionType.SELL }.sumOf { it.quantity }

            var costOfSoldShares = 0.0

            for (buy in sortedBuys) {
                if (sharesToAccountFor <= 0) break

                val sharesSoldFromThisBuy = min(buy.quantity, sharesToAccountFor)

                // 按比例计算被卖出部分的成本（包括手续费）
                if (buy.quantity > 0) {
                    val proportionSold = sharesSoldFromThisBuy.toDouble() / buy.quantity.toDouble()
                    costOfSoldShares += (buy.price * buy.quantity + buy.fee) * proportionSold
                }

                sharesToAccountFor -= sharesSoldFromThisBuy
            }

            // 当前持仓的成本 = 所有买入的总成本 - 已卖出部分的成本
            return totalCost - costOfSoldShares
        }

    val totalQuantity: Int
        get() = transactions.sumOf { if (it.type == TransactionType.BUY) it.quantity else if (it.type == TransactionType.SELL) -it.quantity else 0 }

    val totalCost: Double
        get() = transactions.filter { it.type == TransactionType.BUY }.sumOf { it.quantity * it.price + it.fee }

    val totalSoldValue: Double
        get() = transactions.filter { it.type == TransactionType.SELL }.sumOf { it.quantity * it.price - it.fee }

    // 成本价：现在基于 FIFO 计算
    val costBasis: Double
        get() = if (totalQuantity > 0) fifoCostOfCurrentHoldings / totalQuantity else 0.0

    val marketValue: Double
        get() = totalQuantity * currentPrice

    // 持仓盈亏（未实现盈亏）：现在基于 FIFO 成本计算
    val holdingPL: Double
        get() = if (totalQuantity > 0) marketValue - fifoCostOfCurrentHoldings else 0.0

    val holdingPLPercent: Double
        get() {
            val cost = fifoCostOfCurrentHoldings
            return if (cost > 0) (holdingPL / cost) * 100 else 0.0
        }

    // 总盈亏（已实现 + 未实现）
    val totalPL: Double
        get() = (marketValue + totalSoldValue) - totalCost

    val totalPLPercent: Double
        get() = if (totalCost > 0) ((marketValue + totalSoldValue - totalCost) / totalCost) * 100 else 0.0

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}

