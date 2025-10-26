package com.example.stocktracker.data

import android.util.Log
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

    // totalCost 调整：使用未平仓买入的原始总成本，并减去**本周期内**已实现的利润。
    // 如果持仓数量 > 0，则应用周期性利润抵消。
    val totalCost: Double get() = if (totalQuantity > 0) {
        // 实际成本 = 未调整剩余成本 - 本周期已实现利润
        fifoCalculations.currentCycleTotalCost - fifoCalculations.currentCycleRealizedProfit
    } else {
        0.0 // 平仓后成本清零
    }

    val totalSoldValue: Double get() = fifoCalculations.totalSoldValue // 依然是总卖出价值

    // holdingPL：基于调整后的总成本计算。
    val holdingPL: Double get() = if (totalQuantity > 0) marketValue - totalCost else 0.0

    // costBasis：调整后的总成本 / 数量
    val costBasis: Double get() = if (totalQuantity > 0) totalCost / totalQuantity else 0.0

    val holdingPLPercent: Double
        get() {
            // 使用本周期内总投资作为基数来计算百分比，避免负成本基数
            val currentCycleInvestment = fifoCalculations.currentCycleTotalInvestment
            return if (currentCycleInvestment > 0) holdingPL / currentCycleInvestment * 100 else 0.0
        }

    val marketValue: Double
        get() = totalQuantity * currentPrice

    // totalPL：总盈亏 = 当前持仓盈亏 + 本周期已实现利润 + 累计分红
    // 注意：这里的 totalCost 已被调整。如果 totalCost = 0，则总盈亏就是已实现利润。
    val totalPL: Double
        get() = holdingPL + fifoCalculations.currentCycleRealizedProfit + cumulativeDividend

    // totalPLPercent：总盈亏 / 总投资额
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

    /**
     * 查找最近一次持仓数量归零的日期。
     */
    private fun findLastClearOutDate(allTransactions: List<Transaction>): LocalDate? {
        // 按时间倒序遍历，查找最近的清仓点
        var quantity = 0.0
        for (t in allTransactions.sortedByDescending { it.date }) {
            when (t.type) {
                TransactionType.BUY -> quantity -= t.quantity
                TransactionType.SELL -> quantity += t.quantity
                TransactionType.SPLIT -> {
                    // Split is complex to reverse, we simplify this:
                    // If we encounter a split, we can't reliably track the quantity backwards before the split date.
                    // We assume the first transaction before the last clear-out date must have quantity > 0
                }
                else -> { /* Ignore DIVIDEND */ }
            }

            // 如果数量 <= 0，且这不是最早的交易，则当前交易日期（或前一天）是清仓日。
            // 实际上，我们应该从头开始正向计算，并在归零时记录日期。
        }

        // --- 正向计算并记录归零点 ---
        var currentQuantity = 0.0
        var lastClearOutDate: LocalDate? = null

        for (t in allTransactions.sortedBy { it.date }) {
            when (t.type) {
                TransactionType.BUY -> currentQuantity += t.quantity
                TransactionType.SELL -> currentQuantity -= t.quantity
                TransactionType.SPLIT -> {
                    val ratio = t.quantity / t.price
                    currentQuantity *= ratio
                }
                else -> { /* Ignore DIVIDEND */ }
            }

            if (currentQuantity <= 0.0001 && currentQuantity >= -0.0001) { // 数量清零
                lastClearOutDate = t.date
                currentQuantity = 0.0 // 确保清零
            }
        }
        return lastClearOutDate
    }

    private fun getTransactionsInCurrentCycle(allTransactions: List<Transaction>): List<Transaction> {
        val lastClearOutDate = findLastClearOutDate(allTransactions)

        if (lastClearOutDate == null) {
            // 从未清仓，使用所有交易
            return allTransactions.filter { it.type != TransactionType.DIVIDEND }
        }

        // 过滤出最近清仓日之后的交易 (包括清仓日当天但排除清仓日前已清仓的)
        return allTransactions
            .filter { it.date.isAfter(lastClearOutDate) || (it.date.isEqual(lastClearOutDate) && it.type != TransactionType.SELL) }
            .filter { it.type != TransactionType.DIVIDEND } // 始终排除分红，因为它不影响成本
    }


    private data class FifoResult(
        val finalQuantity: Double,
        val totalCost: Double, // 所有买入的未调整总成本（FIFO匹配后）
        val totalSoldValue: Double,
        val totalCostOfAllBuys: Double, // 所有买入的总成本 (全局，用于总回报率基数)

        // --- 周期性成本调整相关字段 ---
        val currentCycleRealizedProfit: Double, // 本周期已实现利润
        val currentCycleTotalCost: Double, // 本周期剩余持仓的未调整总成本
        val currentCycleTotalInvestment: Double // 本周期所有买入的总成本
    )

    private fun performFifoCalculations(): FifoResult {
        // 仅处理当前周期内的交易（不包括分红）
        val transactionsInCycle = getTransactionsInCurrentCycle(transactions).sortedBy { it.date }

        val allTransactionsSorted = transactions.sortedBy { it.date }

        val remainingBuys = mutableListOf<Transaction>()
        var totalSoldValue = 0.0
        var currentCycleRealizedProfit = 0.0
        var totalCostOfAllBuys = 0.0 // 全局所有买入的总成本
        var currentCycleTotalInvestment = 0.0 // 本周期内所有买入的总成本


        // --- 重新计算全局总投资额 ---
        allTransactionsSorted.filter { it.type == TransactionType.BUY }.forEach { t ->
            totalCostOfAllBuys += (t.quantity * t.price + t.fee)
        }

        // --- 1. 计算当前周期内的 FIFO 和已实现利润 ---
        for (t in transactionsInCycle) {
            when (t.type) {
                TransactionType.BUY -> {
                    val totalBuyCost = t.quantity * t.price + t.fee
                    currentCycleTotalInvestment += totalBuyCost

                    val costPriceWithFee = totalBuyCost / t.quantity

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

                        costOfGoodsSold += quantityToMatch * buy.price

                        if (buy.quantity <= sellQuantity) {
                            sellQuantity -= buy.quantity
                            iterator.remove()
                        } else {
                            val updatedBuy = buy.copy(quantity = buy.quantity - sellQuantity)
                            val index = remainingBuys.indexOf(buy)
                            if (index != -1) {
                                remainingBuys[index] = updatedBuy
                            }
                            sellQuantity = 0.0
                        }
                    }
                    val realizedProfit = sellNetProceeds - costOfGoodsSold
                    currentCycleRealizedProfit += realizedProfit
                }
                TransactionType.SPLIT -> {
                    val ratio = t.quantity / t.price
                    val adjustedBuys = remainingBuys.map { buy ->
                        buy.copy(
                            quantity = buy.quantity * ratio,
                            price = buy.price / ratio
                        )
                    }
                    remainingBuys.clear()
                    remainingBuys.addAll(adjustedBuys)
                }
                else -> { /* 已在过滤时移除 */ }
            }
        }

        val finalQuantity = remainingBuys.sumOf { it.quantity }
        val currentCycleTotalCost = remainingBuys.sumOf { it.quantity * it.price }

        return FifoResult(
            finalQuantity = finalQuantity,
            totalCost = currentCycleTotalCost, // 在本周期内，这个就是未调整的剩余成本
            totalSoldValue = allTransactionsSorted.filter { it.type == TransactionType.SELL }.sumOf { it.quantity * it.price - it.fee },
            totalCostOfAllBuys = totalCostOfAllBuys,
            currentCycleRealizedProfit = currentCycleRealizedProfit,
            currentCycleTotalCost = currentCycleTotalCost,
            currentCycleTotalInvestment = currentCycleTotalInvestment
        )
    }

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}


// --- Cash Transaction Model ---
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
