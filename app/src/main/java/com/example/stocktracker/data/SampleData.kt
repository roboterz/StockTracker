package com.example.stocktracker.data

import java.time.LocalDate

// --- 模拟数据 (用于首次创建数据库时预填充) ---

object SampleData {
    val holdings = listOf(
        StockHolding(
            id = "TSLA",
            name = "特斯拉",
            ticker = "NASDAQ:TSLA",
            currentPrice = 223.52,
            transactions = listOf(
                Transaction(date = LocalDate.of(2025, 9, 5), type = TransactionType.BUY, quantity = 10.0, price = 164.67),
                Transaction(date = LocalDate.of(2025, 9, 9), type = TransactionType.BUY, quantity = 20.0, price = 167.48),
                Transaction(date = LocalDate.of(2025, 9, 10), type = TransactionType.SELL, quantity = 5.0, price = 178.09)
            )
        ),
        StockHolding(
            id = "NVDA",
            name = "英伟达",
            ticker = "NASDAQ:NVDA",
            currentPrice = 177.56,
            transactions = listOf(
                Transaction(date = LocalDate.of(2025, 9, 4), type = TransactionType.BUY, quantity = 2.0, price = 170.67),
                Transaction(date = LocalDate.of(2025, 9, 5), type = TransactionType.BUY, quantity = 2.0, price = 165.13),
                // 新增一条分红记录
                Transaction(date = LocalDate.of(2025, 9, 11), type = TransactionType.DIVIDEND, quantity = 4.0, price = 1.99) // 4 * 1.99 = 7.96
            )
        )
    )
}

