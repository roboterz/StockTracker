package com.example.stocktracker.data.database

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.SampleData
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.concurrent.Executors

// --- 数据库层 (Room Database Layer) ---

// ... (StockHoldingEntity, TransactionEntity, CashTransactionEntity, PortfolioSettingsEntity, StockWithTransactions, Converters, StockDao, PortfolioSettingsDao definitions remain the same) ...
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

    // *** 修改：使用 REPLACE 策略，使其可以用于更新 ***
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCashTransaction(transaction: CashTransactionEntity)

    @Query("DELETE FROM cash_transactions WHERE stockTransactionId = :stockTransactionId")
    suspend fun deleteByStockTransactionId(stockTransactionId: String)

    // *** 新增：按 ID 删除现金交易 ***
    @Query("DELETE FROM cash_transactions WHERE id = :transactionId")
    suspend fun deleteCashTransactionById(transactionId: String)
}

// *** 新增：投资组合设置 DAO ***
@Dao
interface PortfolioSettingsDao {
    // ... (existing code in PortfolioSettingsDao) ...
    @Query("SELECT name FROM portfolio_settings WHERE id = 1")
    fun getPortfolioName(): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: PortfolioSettingsEntity)
}


// 数据库
// ... (Database definition and companion object remain the same) ...
@Database(entities = [StockHoldingEntity::class, TransactionEntity::class, CashTransactionEntity::class, StockNameEntity::class, PortfolioSettingsEntity::class], version = 5)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun cashDao(): CashDao
    abstract fun stockNameDao(): StockNameDao
    abstract fun portfolioSettingsDao(): PortfolioSettingsDao // *** 新增 PortfolioSettingsDao ***

    companion object {
        private const val DATABASE_NAME = "stock_database" // 数据库文件名
        private const val TAG = "StockDatabase"

        @Volatile
        private var INSTANCE: StockDatabase? = null

        fun getDatabase(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
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
                                Log.d(TAG, "Successfully pre-populated ${stockNamesList.size} stock names.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pre-populating stock names from JSON", e)
                // 处理错误，例如显示 Toast 或记录日志
            }
        }

        // *** 新增：WAL 检查点方法 ***
        /**
         * 强制执行 WAL 检查点，将所有 WAL 事务写入主数据库文件。
         * 这对于确保数据库文件在导出时的完整性至关重要。
         */
        fun runCheckpoint(context: Context) {
            try {
                // 确保数据库实例已创建
                val dbInstance = getDatabase(context)
                // 强制执行 FULL 检查点
                dbInstance.openHelper.writableDatabase.use { db ->
                    db.execSQL("PRAGMA wal_checkpoint(FULL)")
                    Log.d(TAG, "WAL Checkpoint (FULL) executed successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing WAL Checkpoint: ${e.message}", e)
            }
        }
        // *** 新增结束 ***

        // --- 数据库导出/导入逻辑 (使用 SAF) ---

        /**
         * 导出主数据库文件到指定的 Uri (SAF)。
         * @return 成功复制的文件数量 (应为 1)
         */
        fun exportDatabase(context: Context, targetUri: Uri): Int {
            var filesCopied = 0
            val dbFolder = File(context.applicationInfo.dataDir + "/databases")
            val dbFile = File(dbFolder, DATABASE_NAME)

            if (dbFile.exists()) {
                try {
                    // 复制主文件
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        dbFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                            filesCopied++
                            Log.d(TAG, "Database main file copied to $targetUri")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error exporting database file: ${e.message}", e)
                }
            }
            return filesCopied
        }

        /**
         * 从指定的 Uri (SAF) 导入数据库文件。
         * 警告：Room 数据库在导入时必须是**关闭状态**。
         * @return 成功复制的文件数量 (应为 1)
         */
        fun importDatabase(context: Context, sourceUri: Uri): Int {
            var filesCopied = 0
            val dbFolder = File(context.applicationInfo.dataDir + "/databases")
            val dbFile = File(dbFolder, DATABASE_NAME)

            if (!dbFolder.exists()) dbFolder.mkdirs()

            try {
                // 1. 关闭现有数据库连接
                // 注意：在 Room 2.1+，此操作可能更复杂。这里采用最直接的方式。
                INSTANCE?.close()
                INSTANCE = null
                Log.d(TAG, "Closed existing database connection.")

                // 2. 删除现有的数据库文件和附属文件
                val filesToDelete = listOf(
                    dbFile,
                    File(dbFolder, "$DATABASE_NAME-wal"),
                    File(dbFolder, "$DATABASE_NAME-shm"),
                    File(dbFolder, "$DATABASE_NAME-journal")
                )
                filesToDelete.forEach {
                    if (it.exists()) it.delete()
                }
                Log.d(TAG, "Deleted old database files.")

                // 3. 复制主文件
                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    dbFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                        filesCopied++
                        Log.d(TAG, "Database main file imported from $sourceUri")
                    }
                }

                // 4. 重新初始化数据库连接（下次调用 getDatabase 时）
                getDatabase(context)

            } catch (e: Exception) {
                Log.e(TAG, "Error importing database: ${e.message}", e)
                // 导入失败后，重新打开数据库，防止应用崩溃
                getDatabase(context)
            }
            return filesCopied
        }
    }
}
