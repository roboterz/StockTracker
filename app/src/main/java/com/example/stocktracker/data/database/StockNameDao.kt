package com.example.stocktracker.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 用于访问 stock_names 表的数据访问对象 (DAO)。
 */
@Dao
interface StockNameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stockNames: List<StockNameEntity>)

    @Query("SELECT chineseName FROM stock_names WHERE ticker = :ticker")
    suspend fun getChineseNameByTicker(ticker: String): String?

    // 可选：添加一个查询所有映射的方法，用于调试
    @Query("SELECT * FROM stock_names")
    suspend fun getAllStockNames(): List<StockNameEntity>
}
