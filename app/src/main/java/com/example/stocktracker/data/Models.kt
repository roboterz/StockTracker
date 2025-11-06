package com.example.stocktracker.data

import java.time.LocalDate
import java.util.*
import kotlin.math.absoluteValue

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
    SELL, BUY, DEPOSIT, WITHDRAWAL, DIVIDEND, SPLIT
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

    // *** 新增：单独计算总数量的逻辑 ***
    private val simpleTotalQuantity: Double by lazy {
        var quantity = 0.0
        transactions
            .sortedBy { it.date }
            .forEach {
                when (it.type) {
                    TransactionType.BUY -> quantity += it.quantity
                    TransactionType.SELL -> quantity -= it.quantity
                    TransactionType.SPLIT -> {
                        // 只有在持有数量不为0时才应用拆股/合股
                        if (quantity.absoluteValue > 1e-9) { // 使用容差值比较
                            val ratio = it.quantity / it.price
                            quantity *= ratio
                        }
                    }
                    else -> { /* Do nothing */ }
                }
            }
        quantity
    }

    // *** 关键修复：totalQuantity 现在使用 simpleTotalQuantity ***
    val totalQuantity: Double get() = simpleTotalQuantity
    // val totalQuantity: Double get() = fifoCalculations.finalQuantity // 旧的错误代码

    // *** 修复后的 totalCost：等于未平仓买入的原始总成本（FIFO）***
    // 不再进行利润抵消。清仓后，如果数量为0，则成本为0。
    val totalCost: Double get() = if (totalQuantity > 0) {
        fifoCalculations.totalCost // 使用未调整的剩余成本
    } else {
        0.0 // 如果是空仓或空头仓位，持仓成本为0
    }

    val totalSoldValue: Double get() = fifoCalculations.totalSoldValue

    // *** 新增：暴露所有买入的总成本 ***
    val totalCostOfAllBuys: Double get() = fifoCalculations.totalCostOfAllBuys

    // holdingPL：基于实际持仓成本计算（无利润抵消）
    // *** 修复：如果是空头仓位 (totalQuantity < 0)，持仓盈亏也需要计算 ***
    val holdingPL: Double get() = when {
        totalQuantity > 1e-9 -> marketValue - totalCost // 多头 (使用容差)
        totalQuantity < -1e-9 -> marketValue + fifoCalculations.totalCostOfShorts // 空头 (使用容差)
        else -> 0.0 // 平仓
    }

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
                        if (quantity.absoluteValue > 1e-9) { // 使用容差值比较
                            val ratio = it.quantity / it.price
                            quantity *= ratio
                        }
                    }
                    else -> { /* Do nothing */
                    }
                }
            }
        return quantity
    }


    private data class FifoResult(
        val finalQuantity: Double, // FIFO 剩余的多头数量
        val totalCost: Double, // 未平仓买入的**未调整**总成本
        val totalSoldValue: Double,
        val totalRealizedProfit: Double, // 总已实现利润
        val totalCostOfAllBuys: Double, // 所有买入的总成本 (用于计算总回报率基数)
        val totalCostOfShorts: Double // *** 新增：空头仓位的成本 ***
    )

    private fun performFifoCalculations(): FifoResult {
        val sortedTransactions = transactions.sortedBy { it.date }
        val remainingBuys = mutableListOf<Transaction>()
        var totalSoldValue = 0.0
        var totalRealizedProfit = 0.0
        var totalCostOfAllBuys = 0.0 // 新增：所有买入的总成本（包括已平仓部分）

        // *** 新增：用于处理空头仓位的变量 ***
        val remainingShorts = mutableListOf<Transaction>()
        var totalCostOfShorts = 0.0 // 空头仓位的总"成本"（即卖出总收入）
        var netQuantity = 0.0 // 跟踪净数量

        // *** 关键修复：定义一个 Epsilon (容差值) ***
        val Epsilon = 1e-9

        for (t in sortedTransactions) {
            // *** 修复：拆股逻辑应在买卖逻辑之前应用到现有仓位 ***
            if (t.type == TransactionType.SPLIT) {
                val ratio = t.quantity / t.price

                // 调整多头
                val adjustedBuys = remainingBuys.map { buy ->
                    buy.copy(
                        quantity = buy.quantity * ratio,
                        price = buy.price / ratio
                    )
                }
                remainingBuys.clear()
                remainingBuys.addAll(adjustedBuys)

                // 调整空头
                val adjustedShorts = remainingShorts.map { shortSell ->
                    shortSell.copy(
                        quantity = shortSell.quantity * ratio,
                        price = shortSell.price / ratio
                    )
                }
                remainingShorts.clear()
                remainingShorts.addAll(adjustedShorts)

                // 调整净数量
                netQuantity *= ratio

                continue // 处理完拆股，跳过本轮循环
            }

            // 累加净数量（仅买卖）
            val quantityChange = when (t.type) {
                TransactionType.BUY -> t.quantity
                TransactionType.SELL -> -t.quantity
                else -> 0.0
            }
            netQuantity += quantityChange


            when (t.type) {
                TransactionType.BUY -> {
                    val totalBuyCost = t.quantity * t.price + t.fee
                    totalCostOfAllBuys += totalBuyCost // 累加所有买入成本
                    val costPriceWithFee = if (t.quantity > Epsilon) totalBuyCost / t.quantity else 0.0
                    var buyQuantity = t.quantity

                    // --- 逻辑修改：买入时，优先平掉空头仓位 ---
                    val iterator = remainingShorts.iterator()
                    while (iterator.hasNext() && buyQuantity > Epsilon) {
                        val shortSell = iterator.next()
                        val quantityToMatch = minOf(shortSell.quantity, buyQuantity)

                        // 卖出时的收入（含费用）
                        val proceedsFromShort = quantityToMatch * shortSell.price
                        // 买入平仓的成本 (使用当前买入价)
                        val costToClose = quantityToMatch * costPriceWithFee

                        totalRealizedProfit += (proceedsFromShort - costToClose)

                        if (shortSell.quantity - buyQuantity < Epsilon) {
                            buyQuantity -= shortSell.quantity
                            iterator.remove() // 该空头仓位被完全平仓
                        } else {
                            // 空头仓位被部分平仓
                            val updatedShort = shortSell.copy(quantity = shortSell.quantity - buyQuantity)
                            val index = remainingShorts.indexOf(shortSell)
                            if (index != -1) remainingShorts[index] = updatedShort
                            buyQuantity = 0.0
                        }
                    }
                    // --- 结束空头平仓逻辑 ---

                    // 如果平仓后仍有剩余买入数量，计入多头仓位
                    if (buyQuantity > Epsilon) {
                        val adjustedBuy = t.copy(
                            quantity = buyQuantity, // 可能是剩余的数量
                            price = costPriceWithFee, // 含费用的成本价
                            fee = 0.0
                        )
                        remainingBuys.add(adjustedBuy)
                    }
                }
                TransactionType.SELL -> {
                    var sellQuantity = t.quantity
                    // 卖出时的净收入（已减手续费）
                    val sellNetProceeds = t.quantity * t.price - t.fee
                    totalSoldValue += sellNetProceeds
                    val pricePerShareWithFee = if (t.quantity > Epsilon) sellNetProceeds / t.quantity else 0.0 // 卖出时的净单价

                    // --- 逻辑修改：卖出时，优先平掉多头仓位 ---
                    val iterator = remainingBuys.iterator()
                    while (iterator.hasNext() && sellQuantity > Epsilon) {
                        val buy = iterator.next()
                        val quantityToMatch = minOf(buy.quantity, sellQuantity)

                        // 卖出平多仓的已实现利润
                        val proceedsFromSale = quantityToMatch * pricePerShareWithFee // 卖出收入
                        val costOfGoodsSold = quantityToMatch * buy.price // 买入成本
                        totalRealizedProfit += (proceedsFromSale - costOfGoodsSold)

                        if (buy.quantity - sellQuantity < Epsilon) {
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
                    // --- 结束多头平仓逻辑 ---

                    // 如果平仓后仍有剩余卖出数量，计入空头仓位
                    if (sellQuantity > Epsilon) {
                        val adjustedShort = t.copy(
                            quantity = sellQuantity, // 剩余的卖出数量
                            price = pricePerShareWithFee, // 含费用的净收入单价
                            fee = 0.0
                        )
                        remainingShorts.add(adjustedShort)
                    }
                }
                TransactionType.SPLIT -> {
                    // 逻辑已移到循环顶部
                }
                else -> { /* Ignore DIVIDEND */
                }
            }
        }

        // *** 修复：finalQuantity 应该只代表多头数量 ***
        val finalLongQuantity = remainingBuys.sumOf { it.quantity }
        // 未调整的剩余持仓总成本 (在进行利润抵消前)
        val unadjustedTotalCost = remainingBuys.sumOf { it.quantity * it.price }

        // *** 新增：计算空头仓位成本 ***
        totalCostOfShorts = remainingShorts.sumOf { it.quantity * it.price }


        return FifoResult(
            finalLongQuantity, // 仅多头数量
            unadjustedTotalCost,
            totalSoldValue,
            totalRealizedProfit,
            totalCostOfAllBuys,
            totalCostOfShorts // 空头成本（总收入）
        )
    }

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}