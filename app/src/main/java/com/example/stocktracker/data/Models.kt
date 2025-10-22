package com.example.stocktracker.data

import java.time.LocalDate
import java.util.*

// --- UI 数据模型 (UI Data Models) ---

enum class TransactionType {
    BUY, SELL, DIVIDEND, SPLIT // 新增 SPLIT 类型
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Int, // 对于 SPLIT，这里将存储 Numerator (分子)
    val price: Double, // 对于 SPLIT，这里将存储 Denominator (分母)
    val fee: Double = 0.0
)

data class StockHolding(
    val id: String,
    val name: String,
    val ticker: String,
    var currentPrice: Double,
    val transactions: List<Transaction>,
    var dailyPL: Double = 0.0,
    var dailyPLPercent: Double = 0.0,
    val cumulativeDividend: Double = 0.0
) {
    // 这是一个用于缓存FIFO计算结果的属性
    private val fifoCalculations by lazy {
        performFifoCalculations()
    }

    val totalQuantity: Int get() = fifoCalculations.finalQuantity
    val totalCost: Double get() = fifoCalculations.totalCost
    val totalSoldValue: Double get() = fifoCalculations.totalSoldValue
    val holdingPL: Double get() = if (totalQuantity > 0) marketValue - (totalCost - totalSoldValue) else 0.0
    val costBasis: Double get() = if (totalQuantity > 0) (totalCost - totalSoldValue) / totalQuantity else 0.0

    // *** 关键修复：重新添加缺失的 holdingPLPercent 属性 ***
    val holdingPLPercent: Double
        get() {
            val cost = totalCost - totalSoldValue
            return if (cost > 0) holdingPL / cost * 100 else 0.0
        }

    val marketValue: Double
        get() = totalQuantity * currentPrice

    val totalPL: Double
        get() = marketValue - (totalCost - totalSoldValue) + cumulativeDividend

    val totalPLPercent: Double
        get() = if ((totalCost - totalSoldValue) > 0) totalPL / (totalCost - totalSoldValue) * 100 else 0.0

    // 根据日期获取持股数量
    fun getQuantityOnDate(date: LocalDate): Int {
        var quantity = 0
        transactions
            .filter { it.date.isBefore(date) || it.date.isEqual(date) }
            .sortedBy { it.date }
            .forEach {
                when (it.type) {
                    TransactionType.BUY -> quantity += it.quantity
                    TransactionType.SELL -> quantity -= it.quantity
                    TransactionType.SPLIT -> {
                        val ratio = it.quantity.toDouble() / it.price
                        quantity = (quantity * ratio).toInt()
                    }
                    else -> { /* Do nothing */
                    }
                }
            }
        return quantity
    }


    // 内部数据类，用于封装FIFO计算的复杂结果
    private data class FifoResult(
        val finalQuantity: Int,
        val totalCost: Double,
        val totalSoldValue: Double
    )

    // 核心FIFO计算逻辑，现在支持拆股/合股
    private fun performFifoCalculations(): FifoResult {
        val sortedTransactions = transactions.sortedBy { it.date }
        val remainingBuys = mutableListOf<Transaction>()
        var totalSoldValue = 0.0

        for (t in sortedTransactions) {
            when (t.type) {
                TransactionType.BUY -> remainingBuys.add(t)
                TransactionType.SELL -> {
                    var sellQuantity = t.quantity
                    var sellProceeds = t.quantity * t.price - t.fee
                    totalSoldValue += sellProceeds

                    val iterator = remainingBuys.iterator()
                    while (iterator.hasNext() && sellQuantity > 0) {
                        val buy = iterator.next()
                        if (buy.quantity <= sellQuantity) {
                            sellQuantity -= buy.quantity
                            iterator.remove()
                        } else {
                            val updatedBuy = buy.copy(quantity = buy.quantity - sellQuantity)
                            // 用更新后的buy替换原来的buy
                            val index = remainingBuys.indexOf(buy)
                            if (index != -1) {
                                remainingBuys[index] = updatedBuy
                            }
                            sellQuantity = 0
                        }
                    }
                }
                TransactionType.SPLIT -> {
                    // 当遇到拆股事件时，调整所有现有持仓的数量和价格
                    val ratio = t.quantity.toDouble() / t.price
                    val adjustedBuys = remainingBuys.map { buy ->
                        buy.copy(
                            quantity = (buy.quantity * ratio).toInt(),
                            price = buy.price / ratio
                        )
                    }
                    remainingBuys.clear()
                    remainingBuys.addAll(adjustedBuys)
                }
                else -> { /* 忽略 DIVIDEND */
                }
            }
        }

        val finalQuantity = remainingBuys.sumOf { it.quantity }
        val totalCost = remainingBuys.sumOf { it.quantity * it.price + it.fee }

        return FifoResult(finalQuantity, totalCost, totalSoldValue)
    }

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}


// --- 现金交易模型 ---
enum class CashTransactionType {
    DEPOSIT, WITHDRAWAL
}

data class CashTransaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: CashTransactionType,
    val amount: Double,
    val stockTransactionId: String? = null
)

