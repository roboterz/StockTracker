package com.example.stocktracker.data.database

import android.content.Context
import android.util.Log
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase // Import SupportSQLiteDatabase
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.SampleData
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject // Import JSONObject
import java.io.BufferedReader // Import BufferedReader
import java.io.InputStreamReader // Import InputStreamReader
import java.time.LocalDate
import java.util.concurrent.Executors

// --- 数据库层 (Room Database Layer) ---

// ... (StockHoldingEntity, TransactionEntity, CashTransactionEntity remain the same) ...
@Entity(tableName = "stocks")
data class StockHoldingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ticker: String,
    val currentPrice: Double
)

@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = StockHoldingEntity::class,
        parentColumns = ["id"],
        childColumns = ["stockId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["stockId"])]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val stockId: String,
    val date: LocalDate,
    val type: TransactionType,
    // *** 关键修复：将 quantity 类型改为 Double ***
    val quantity: Double,
    val price: Double,
    val fee: Double
)

// 新增：现金交易实体
@Entity(tableName = "cash_transactions")
data class CashTransactionEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val type: CashTransactionType,
    val amount: Double,
    val stockTransactionId: String? // 可为空，用于关联股票交易
)

// *** 新增：投资组合设置实体 ***
@Entity(tableName = "portfolio_settings")
data class PortfolioSettingsEntity(
    @PrimaryKey val id: Int = 1, // 固定ID，确保只有一行记录
    val name: String
)

// 用于查询的组合数据类 (POJO for Queries)
data class StockWithTransactions(
    @Embedded val stock: StockHoldingEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "stockId"
    )
    val transactions: List<TransactionEntity>
)

// Room类型转换器
class Converters {
    // ... (Existing converters remain the same) ...
    @TypeConverter
    fun fromTimestamp(value: Long?): LocalDate? {
        return value?.let { LocalDate.ofEpochDay(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: LocalDate?): Long? {
        return date?.toEpochDay()
    }

    @TypeConverter
    fun fromTransactionType(value: String?): TransactionType? {
        return value?.let { TransactionType.valueOf(it) }
    }

    @TypeConverter
    fun transactionTypeToString(type: TransactionType?): String? {
        return type?.name
    }

    // 新增：现金交易类型的转换器
    @TypeConverter
    fun fromCashTransactionType(value: String?): CashTransactionType? {
        return value?.let { CashTransactionType.valueOf(it) }
    }

    @TypeConverter
    fun cashTransactionTypeToString(type: CashTransactionType?): String? {
        return type?.name
    }
}


// 数据访问对象 (DAO)
@Dao
interface StockDao {
    // ... (Existing methods remain the same) ...
    @androidx.room.Transaction
    @Query("SELECT * FROM stocks")
    fun getAllStocksWithTransactions(): Flow<List<StockWithTransactions>>

    @Query("SELECT * FROM stocks WHERE id = :stockId")
    suspend fun getStockById(stockId: String): StockHoldingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: StockHoldingEntity)

    @Update
    suspend fun updateStock(stock: StockHoldingEntity)

    // *** 新增：根据股票ID查询所有交易 ***
    @Query("SELECT * FROM transactions WHERE stockId = :stockId")
    suspend fun getTransactionsByStockId(stockId: String): List<TransactionEntity>

    // *** 新增：根据股票ID删除股票实体 ***
    @Query("DELETE FROM stocks WHERE id = :stockId")
    suspend fun deleteStockById(stockId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransactionById(transactionId: String)
}

// ... (CashDao remains the same) ...
@Dao
interface CashDao {
    @Query("SELECT * FROM cash_transactions ORDER BY date DESC")
    fun getAllCashTransactions(): Flow<List<CashTransactionEntity>>

    @Insert
    suspend fun insertCashTransaction(transaction: CashTransactionEntity)

    @Query("DELETE FROM cash_transactions WHERE stockTransactionId = :stockTransactionId")
    suspend fun deleteByStockTransactionId(stockTransactionId: String)
}

// *** 新增：投资组合设置 DAO ***
@Dao
interface PortfolioSettingsDao {
    @Query("SELECT name FROM portfolio_settings WHERE id = 1")
    fun getPortfolioName(): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: PortfolioSettingsEntity)
}


// 数据库
// *** 更新版本号到 5，添加 PortfolioSettingsEntity ***
@Database(entities = [StockHoldingEntity::class, TransactionEntity::class, CashTransactionEntity::class, StockNameEntity::class, PortfolioSettingsEntity::class], version = 5)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun cashDao(): CashDao
    abstract fun stockNameDao(): StockNameDao
    abstract fun portfolioSettingsDao(): PortfolioSettingsDao // *** 新增 PortfolioSettingsDao ***

    companion object {
        @Volatile
        private var INSTANCE: StockDatabase? = null

        // *** 修改 getDatabase 以包含新的 DAO 和预填充逻辑 ***
        fun getDatabase(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    "stock_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 在数据库创建时执行预填充
                            Executors.newSingleThreadExecutor().execute {
                                INSTANCE?.let { database ->
                                    // 预填充 SampleData (如果需要)
                                    // prePopulateSampleData(database)
                                    // *** 预填充股票名称数据和默认组合名称 ***
                                    runBlocking {
                                        database.portfolioSettingsDao().insert(PortfolioSettingsEntity(name = "我的投资组合"))
                                    }
                                    prePopulateStockNames(context, database)
                                }
                            }
                        }
                        // 如果需要，可以在 onOpen 中也添加填充逻辑，以处理数据库已存在但表为空的情况
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // 确保默认组合名称存在（如果用户没有修改过）
                            Executors.newSingleThreadExecutor().execute {
                                runBlocking {
                                    val name = INSTANCE?.portfolioSettingsDao()?.getPortfolioName()?.firstOrNull()
                                    if (name == null || name.isBlank()) {
                                        INSTANCE?.portfolioSettingsDao()?.insert(PortfolioSettingsEntity(name = "我的投资组合"))
                                    }
                                }
                            }
                        }
                    })
                    .fallbackToDestructiveMigration() // 迁移策略
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // 辅助函数：预填充 SampleData
        private fun prePopulateSampleData(database: StockDatabase) {
            runBlocking {
                SampleData.holdings.forEach{ stock ->
                    database.stockDao().insertStock(stock.toEntity())
                    stock.transactions.forEach { trans ->
                        database.stockDao().insertTransaction(trans.toEntity(stock.id))
                    }
                }
            }
        }

        // *** 新增辅助函数：从 assets 读取 JSON 并填充 stock_names 表 ***
        private fun prePopulateStockNames(context: Context, database: StockDatabase) {
            try {
                context.assets.open("us-stock-code-zh.json").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        val jsonString = reader.readText()
                        val jsonObject = JSONObject(jsonString)
                        val stockNamesList = mutableListOf<StockNameEntity>()
                        val keys = jsonObject.keys()
                        while (keys.hasNext()) {
                            val ticker = keys.next()
                            val name = jsonObject.getString(ticker)
                            if (ticker.isNotBlank() && name.isNotBlank()) {
                                stockNamesList.add(StockNameEntity(ticker = ticker.uppercase(), chineseName = name))
                            }
                        }
                        if (stockNamesList.isNotEmpty()) {
                            runBlocking {
                                database.stockNameDao().insertAll(stockNamesList)
                                Log.d("StockDatabase", "Successfully pre-populated ${stockNamesList.size} stock names.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StockDatabase", "Error pre-populating stock names from JSON", e)
                // 处理错误，例如显示 Toast 或记录日志
            }
        }
    }
}
