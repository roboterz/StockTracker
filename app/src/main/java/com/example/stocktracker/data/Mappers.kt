package com.example.stocktracker.data

import com.example.stocktracker.data.database.CashTransactionEntity
import com.example.stocktracker.data.database.PortfolioEntity
import com.example.stocktracker.data.database.StockHoldingEntity
import com.example.stocktracker.data.database.StockWithTransactions
import com.example.stocktracker.data.database.TransactionEntity

// --- 数据映射 (Data Mappers) ---

fun PortfolioEntity.toUIModel(): Portfolio {
    return Portfolio(id, name)
}

fun Portfolio.toEntity(): PortfolioEntity {
    return PortfolioEntity(id, name)
}

fun StockWithTransactions.toUIModel(): StockHolding {
    val uiTransactions = getFilteredTransactions().map { it.toUIModel() }
    val cumulativeDividend = uiTransactions
        .filter { it.type == TransactionType.DIVIDEND }
        .sumOf { it.quantity * it.price }

    // 持仓盈亏现在由 StockHolding 内部自动计算，不再需要在此处手动赋值
    return StockHolding(
        id = stock.id,
        name = stock.name,
        ticker = stock.ticker,
        currentPrice = stock.currentPrice,
        transactions = uiTransactions,
        dailyPL = 0.0, // 初始化为0，等待网络刷新
        dailyPLPercent = 0.0, // 初始化为0
        cumulativeDividend = cumulativeDividend
    )
}

fun TransactionEntity.toUIModel(): Transaction {
    return Transaction(id, date, type, quantity, price, fee)
}

// 新增：现金交易的映射函数
fun CashTransactionEntity.toUIModel(): CashTransaction {
    return CashTransaction(id, date, type, amount, stockTransactionId)
}


fun StockHolding.toEntity(portfolioId: String): StockHoldingEntity {
    return StockHoldingEntity(id, portfolioId, name, ticker, currentPrice)
}

fun Transaction.toEntity(ticker: String, portfolioId: String): TransactionEntity {
    return TransactionEntity(id, ticker, portfolioId, date, type, quantity, price, fee)
}

// 新增：现金交易的映射函数
fun CashTransaction.toEntity(portfolioId: String): CashTransactionEntity {
    return CashTransactionEntity(id, portfolioId, date, type, amount, stockTransactionId)
}

