package com.example.stocktracker.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用于存储股票代码和中文名称映射的数据库实体。
 */
@Entity(tableName = "stock_names")
data class StockNameEntity(
    @PrimaryKey val ticker: String,
    val chineseName: String
)
