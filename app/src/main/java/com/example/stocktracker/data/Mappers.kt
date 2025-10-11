package com.example.stocktracker.data

import com.example.stocktracker.data.database.StockHoldingEntity
import com.example.stocktracker.data.database.StockWithTransactions
import com.example.stocktracker.data.database.TransactionEntity

// --- 数据映射 (Data Mappers) ---

fun StockWithTransactions.toUIModel(): StockHolding {
    val uiTransactions = transactions.map { it.toUIModel() }
    val cumulativeDividend = uiTransactions
        .filter { it.type == TransactionType.DIVIDEND }
        .sumOf { it.quantity * it.price }

    // 注意: 当日盈亏和持仓盈亏需要实时API数据，这里使用占位数据以匹配UI
    val dailyPL = 6.45
    val dailyPLPercent = 0.18
    val holdingPL = 56.94
    val holdingPLPercent = 1.63


    return StockHolding(
        id = stock.id,
        name = stock.name,
        ticker = stock.ticker,
        currentPrice = stock.currentPrice,
        transactions = uiTransactions,
        dailyPL = dailyPL,
        dailyPLPercent = dailyPLPercent,
        holdingPL = holdingPL,
        holdingPLPercent = holdingPLPercent,
        cumulativeDividend = cumulativeDividend
    )
}

fun TransactionEntity.toUIModel(): Transaction {
    return Transaction(id, date, type, quantity, price, fee)
}

fun StockHolding.toEntity(): StockHoldingEntity {
    return StockHoldingEntity(id, name, ticker, currentPrice)
}

fun Transaction.toEntity(stockId: String): TransactionEntity {
    return TransactionEntity(id, stockId, date, type, quantity, price, fee)
}

