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

    // 注意：当日盈亏、持仓盈亏等将在ViewModel中获取网络数据后计算。
    // 数据库中的currentPrice用作初始值。
    return StockHolding(
        id = stock.id,
        name = stock.name,
        ticker = stock.ticker,
        currentPrice = stock.currentPrice,
        transactions = uiTransactions,
        dailyPL = 0.0, // 占位符
        dailyPLPercent = 0.0, // 占位符
        holdingPL = 0.0, // 占位符
        holdingPLPercent = 0.0, // 占位符
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

