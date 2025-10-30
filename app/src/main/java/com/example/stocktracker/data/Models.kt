package com.example.stocktracker.data

import java.time.LocalDate
import java.util.*

// --- UI Data Models ---

enum class TransactionType {
    BUY, SELL, DIVIDEND, SPLIT
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: TransactionType,
    // Key fix: Changed quantity type to Double to support fractional shares
    val quantity: Double,
    val price: Double, // For SPLIT, this stores the Denominator
    val fee: Double = 0.0
)


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
    private val fifoCalculations by lazy {
        performFifoCalculations()
    }

    val totalQuantity: Double get() = fifoCalculations.finalQuantity

    // *** 修复后的 totalCost：等于未平仓买入的原始总成本（FIFO）***
    // 不再进行利润抵消。清仓后，如果数量为0，则成本为0。
    val totalCost: Double get() = if (totalQuantity > 0) {
        fifoCalculations.totalCost // 使用未调整的剩余成本
    } else {
        0.0
    }

    val totalSoldValue: Double get() = fifoCalculations.totalSoldValue

    // *** 新增：暴露所有买入的总成本 ***
    val totalCostOfAllBuys: Double get() = fifoCalculations.totalCostOfAllBuys

    // holdingPL：基于实际持仓成本计算（无利润抵消）
    val holdingPL: Double get() = if (totalQuantity > 0) marketValue - totalCost else 0.0

    // costBasis：实际持仓成本 / 数量
    val costBasis: Double get() = if (totalQuantity > 0) totalCost / totalQuantity else 0.0

    val holdingPLPercent: Double
        get() {
            // 使用 actual totalCost 作为基数
            return if (totalCost > 0) holdingPL / totalCost * 100 else 0.0
        }

    val marketValue: Double
        get() = totalQuantity * currentPrice

    // *** totalPL：总盈亏 = 当前持仓盈亏 + 已实现利润 + 分红 ***
    // (marketValue - totalCost) 是当前持仓盈亏 (holdingPL)。
    val totalPL: Double
        get() = holdingPL + fifoCalculations.totalRealizedProfit + cumulativeDividend

    // totalPLPercent：总盈亏 / 所有买入的总投资额
    val totalPLPercent: Double
        get() {
            val totalInvestment = fifoCalculations.totalCostOfAllBuys
            return if (totalInvestment > 0) totalPL / totalInvestment * 100 else 0.0
        }

    // Key fix: Function updated to return Double and correctly handle calculations
    fun getQuantityOnDate(date: LocalDate): Double {
        var quantity = 0.0
        transactions
            .filter { it.date.isBefore(date) || it.date.isEqual(date) }
            .sortedBy { it.date }
            .forEach {
                when (it.type) {
                    TransactionType.BUY -> quantity += it.quantity
                    TransactionType.SELL -> quantity -= it.quantity
                    TransactionType.SPLIT -> {
                        val ratio = it.quantity / it.price
                        quantity *= ratio
                    }
                    else -> { /* Do nothing */
                    }
                }
            }
        return quantity
    }


    private data class FifoResult(
        val finalQuantity: Double,
        val totalCost: Double, // 未平仓买入的**未调整**总成本
        val totalSoldValue: Double,
        val totalRealizedProfit: Double, // 总已实现利润
        val totalCostOfAllBuys: Double // 所有买入的总成本 (用于计算总回报率基数)
    )

    private fun performFifoCalculations(): FifoResult {
        val sortedTransactions = transactions.sortedBy { it.date }
        val remainingBuys = mutableListOf<Transaction>()
        var totalSoldValue = 0.0
        var totalRealizedProfit = 0.0
        var totalCostOfAllBuys = 0.0 // 新增：所有买入的总成本（包括已平仓部分）

        for (t in sortedTransactions) {
            when (t.type) {
                TransactionType.BUY -> {
                    // 计算含费用的成本价，并将费用于该笔交易平摊掉
                    val totalBuyCost = t.quantity * t.price + t.fee
                    totalCostOfAllBuys += totalBuyCost // 累加所有买入成本

                    val costPriceWithFee = totalBuyCost / t.quantity

                    // 创建一个新交易对象，其 price 字段现在存储的是含费用的成本价，且 fee 设为 0
                    val adjustedBuy = t.copy(
                        price = costPriceWithFee,
                        fee = 0.0
                    )
                    remainingBuys.add(adjustedBuy)
                }
                TransactionType.SELL -> {
                    var sellQuantity = t.quantity
                    val sellNetProceeds = t.quantity * t.price - t.fee
                    totalSoldValue += sellNetProceeds

                    var costOfGoodsSold = 0.0

                    val iterator = remainingBuys.iterator()
                    while (iterator.hasNext() && sellQuantity > 0) {
                        val buy = iterator.next()
                        val quantityToMatch = minOf(buy.quantity, sellQuantity)

                        // COGS based on FIFO: quantity matched * adjusted cost price
                        costOfGoodsSold += quantityToMatch * buy.price

                        if (buy.quantity <= sellQuantity) {
                            sellQuantity -= buy.quantity
                            iterator.remove() // 整笔买入平仓
                        } else {
                            val updatedBuy = buy.copy(quantity = buy.quantity - sellQuantity)
                            val index = remainingBuys.indexOf(buy)
                            if (index != -1) {
                                remainingBuys[index] = updatedBuy // 部分平仓，更新剩余数量
                            }
                            sellQuantity = 0.0
                        }
                    }
                    // 计算并累加已实现利润 (收入 - 成本)
                    val realizedProfit = sellNetProceeds - costOfGoodsSold
                    totalRealizedProfit += realizedProfit
                }
                TransactionType.SPLIT -> {
                    val ratio = t.quantity / t.price
                    val adjustedBuys = remainingBuys.map { buy ->
                        // 调整数量和含费用的成本价
                        buy.copy(
                            quantity = buy.quantity * ratio,
                            price = buy.price / ratio
                        )
                    }
                    remainingBuys.clear()
                    remainingBuys.addAll(adjustedBuys)
                }
                else -> { /* Ignore DIVIDEND */
                }
            }
        }

        val finalQuantity = remainingBuys.sumOf { it.quantity }
        // 未调整的剩余持仓总成本 (在进行利润抵消前)
        val unadjustedTotalCost = remainingBuys.sumOf { it.quantity * it.price }

        return FifoResult(finalQuantity, unadjustedTotalCost, totalSoldValue, totalRealizedProfit, totalCostOfAllBuys)
    }

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}

