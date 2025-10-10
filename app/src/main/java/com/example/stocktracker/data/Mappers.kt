package com.example.stocktracker.data

import com.example.stocktracker.data.database.StockHoldingEntity
import com.example.stocktracker.data.database.StockWithTransactions
import com.example.stocktracker.data.database.TransactionEntity

// --- 数据映射 (Data Mappers) ---

fun StockWithTransactions.toUIModel(): StockHolding {
    return StockHolding(
        id = stock.id,
        name = stock.name,
        ticker = stock.ticker,
        currentPrice = stock.currentPrice,
        transactions = transactions.map { it.toUIModel() }
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
