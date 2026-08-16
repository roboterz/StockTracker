package com.aerolite.stocktracker.data.database

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aerolite.stocktracker.data.CashTransactionType
import com.aerolite.stocktracker.data.SampleData
import com.aerolite.stocktracker.data.TransactionType
import com.aerolite.stocktracker.data.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

// --- 数据库层 (Room Database Layer) ---

@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String
)

@Entity(
    tableName = "stocks",
    primaryKeys = ["id", "portfolioId"],
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["portfolioId"])]
)
data class StockHoldingEntity(
    val id: String, // 纯股票代码 (e.g. AAPL)
    val portfolioId: String,
    val name: String,
    val ticker: String, // 带有交易所前缀的代码 (e.g. NASDAQ:AAPL)
    val currentPrice: Double
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["portfolioId"]), Index(value = ["stockId"])]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val stockId: String, // 仅保存股票代码 (Ticker)
    val portfolioId: String, // 归属的投资组合 ID
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Double,
    val price: Double,
    val fee: Double
)

// 新增：现金交易实体
@Entity(
    tableName = "cash_transactions",
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["portfolioId"])]
)
data class CashTransactionEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val date: LocalDate,
    val type: CashTransactionType,
    val amount: Double,
    val stockTransactionId: String? // 可为空，用于关联股票交易
)

// 用于查询的组合数据类 (POJO for Queries)
data class StockWithTransactions(
    @Embedded val stock: StockHoldingEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "stockId"
    )
    val transactions: List<TransactionEntity>
) {
    // 由于 Room 的 @Relation 在处理复合关联时有限制，
    // 我们在转换层或此处确保过滤掉不属于当前投资组合的交易
    fun getFilteredTransactions(): List<TransactionEntity> {
        return transactions.filter { it.portfolioId == stock.portfolioId }
    }
}

data class PortfolioWithHoldings(
    @Embedded val portfolio: PortfolioEntity,
    @Relation(
        entity = StockHoldingEntity::class,
        parentColumn = "id",
        entityColumn = "portfolioId"
    )
    val holdings: List<StockWithTransactions>
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
    @androidx.room.Transaction
    @Query("SELECT * FROM stocks WHERE portfolioId = :portfolioId")
    fun getStocksWithTransactionsByPortfolio(portfolioId: String): Flow<List<StockWithTransactions>>

    @Query("SELECT * FROM stocks WHERE id = :stockId AND portfolioId = :portfolioId")
    suspend fun getStockById(stockId: String, portfolioId: String): StockHoldingEntity?

    @Query("SELECT * FROM stocks WHERE ticker = :ticker AND portfolioId = :portfolioId")
    suspend fun getStockByTicker(ticker: String, portfolioId: String): StockHoldingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: StockHoldingEntity)

    @Update
    suspend fun updateStock(stock: StockHoldingEntity)

    @Query("SELECT * FROM transactions WHERE stockId = :ticker AND portfolioId = :portfolioId")
    suspend fun getTransactionsByTicker(ticker: String, portfolioId: String): List<TransactionEntity>

    @Query("DELETE FROM stocks WHERE id = :stockId AND portfolioId = :portfolioId")
    suspend fun deleteStockById(stockId: String, portfolioId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransactionById(transactionId: String)
}

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_transactions WHERE portfolioId = :portfolioId ORDER BY date DESC")
    fun getCashTransactionsByPortfolio(portfolioId: String): Flow<List<CashTransactionEntity>>

    @Query("SELECT * FROM cash_transactions")
    fun getCashTransactionsByAllPortfolios(): Flow<List<CashTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCashTransaction(transaction: CashTransactionEntity)

    @Query("DELETE FROM cash_transactions WHERE stockTransactionId = :stockTransactionId")
    suspend fun deleteByStockTransactionId(stockTransactionId: String)

    @Query("DELETE FROM cash_transactions WHERE id = :transactionId")
    suspend fun deleteCashTransactionById(transactionId: String)
}

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios")
    fun getAllPortfolios(): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios")
    suspend fun getAllPortfoliosDirect(): List<PortfolioEntity>

    @Query("SELECT * FROM portfolios WHERE id = :id")
    suspend fun getPortfolioById(id: String): PortfolioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(portfolio: PortfolioEntity)

    @Update
    suspend fun update(portfolio: PortfolioEntity)

    @Delete
    suspend fun delete(portfolio: PortfolioEntity)

    @androidx.room.Transaction
    @Query("SELECT * FROM portfolios")
    fun getPortfoliosWithHoldings(): Flow<List<PortfolioWithHoldings>>
}


// 数据库
@Database(entities = [PortfolioEntity::class, StockHoldingEntity::class, TransactionEntity::class, CashTransactionEntity::class, StockNameEntity::class], version = 7)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun cashDao(): CashDao
    abstract fun stockNameDao(): StockNameDao
    abstract fun portfolioDao(): PortfolioDao

    companion object {
        private const val DATABASE_NAME = "stock_tracker_v2.db" // 更改数据库名称以触发重新创建或手动迁移
        private const val TAG = "StockDatabase"
        const val DEFAULT_PORTFOLIO_ID = "default_portfolio"

        @Volatile
        private var INSTANCE: StockDatabase? = null

        private val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. 创建 portfolios 表
                database.execSQL("CREATE TABLE IF NOT EXISTS `portfolios` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
                
                // 2. 迁移旧的 portfolio_settings 到 portfolios (如果存在)，或插入默认值
                // 注意：旧表名为 portfolio_settings
                try {
                    database.execSQL("INSERT OR IGNORE INTO portfolios (id, name) SELECT 'default_portfolio', name FROM portfolio_settings")
                } catch (e: Exception) {
                    Log.e(TAG, "portfolio_settings table might not exist", e)
                }
                database.execSQL("INSERT OR IGNORE INTO portfolios (id, name) VALUES ('default_portfolio', '我的投资组合')")

                // 3. 迁移 stocks 表 (涉及主键变更，必须重建)
                database.execSQL("CREATE TABLE IF NOT EXISTS `stocks_new` (`id` TEXT NOT NULL, `portfolioId` TEXT NOT NULL, `name` TEXT NOT NULL, `ticker` TEXT NOT NULL, `currentPrice` REAL NOT NULL, PRIMARY KEY(`id`, `portfolioId`), FOREIGN KEY(`portfolioId`) REFERENCES `portfolios`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                // 从 ticker 或 id 中截取纯代码作为新的 id
                database.execSQL("INSERT OR REPLACE INTO stocks_new (id, portfolioId, name, ticker, currentPrice) SELECT CASE WHEN INSTR(ticker, ':') > 0 THEN SUBSTR(ticker, INSTR(ticker, ':') + 1) ELSE (CASE WHEN INSTR(id, '_') > 0 THEN SUBSTR(id, 1, INSTR(id, '_') - 1) ELSE id END) END, 'default_portfolio', name, ticker, currentPrice FROM stocks")
                database.execSQL("DROP TABLE stocks")
                database.execSQL("ALTER TABLE stocks_new RENAME TO stocks")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_stocks_portfolioId` ON `stocks` (`portfolioId`)")

                // 4. 迁移 transactions 表 (增加 portfolioId 字段，并确保 stockId 是纯代码)
                database.execSQL("CREATE TABLE IF NOT EXISTS `transactions_new` (`id` TEXT NOT NULL, `stockId` TEXT NOT NULL, `portfolioId` TEXT NOT NULL, `date` INTEGER NOT NULL, `type` TEXT NOT NULL, `quantity` REAL NOT NULL, `price` REAL NOT NULL, `fee` REAL NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`portfolioId`) REFERENCES `portfolios`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                // 清洗 stockId：优先处理 ':' (交易所前缀)，否则处理 '_' (旧版 portfolioId 后缀)
                database.execSQL("INSERT INTO transactions_new (id, stockId, portfolioId, date, type, quantity, price, fee) SELECT id, CASE WHEN INSTR(stockId, ':') > 0 THEN SUBSTR(stockId, INSTR(stockId, ':') + 1) ELSE (CASE WHEN INSTR(stockId, '_') > 0 THEN SUBSTR(stockId, 1, INSTR(stockId, '_') - 1) ELSE stockId END) END, 'default_portfolio', date, type, quantity, price, fee FROM transactions")
                database.execSQL("DROP TABLE transactions")
                database.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_portfolioId` ON `transactions` (`portfolioId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_stockId` ON `transactions` (`stockId`)")

                // 5. 迁移 cash_transactions 表 (增加 portfolioId 字段)
                database.execSQL("CREATE TABLE IF NOT EXISTS `cash_transactions_new` (`id` TEXT NOT NULL, `portfolioId` TEXT NOT NULL, `date` INTEGER NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, `stockTransactionId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`portfolioId`) REFERENCES `portfolios`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("INSERT INTO cash_transactions_new (id, portfolioId, date, type, amount, stockTransactionId) SELECT id, 'default_portfolio', date, type, amount, stockTransactionId FROM cash_transactions")
                database.execSQL("DROP TABLE cash_transactions")
                database.execSQL("ALTER TABLE cash_transactions_new RENAME TO cash_transactions")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_transactions_portfolioId` ON `cash_transactions` (`portfolioId`)")

                // 6. 删除旧的 portfolio_settings 表
                database.execSQL("DROP TABLE IF EXISTS portfolio_settings")
            }
        }

        fun getDatabase(context: Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                // 优先执行文件迁移
                migrateDatabaseFileIfNeeded(context)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_5_7) // 添加迁移
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
                                        database.portfolioDao().insert(PortfolioEntity(id = DEFAULT_PORTFOLIO_ID, name = "我的投资组合"))
                                    }
                                    prePopulateStockNames(context, database)
                                }
                            }
                        }
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            Executors.newSingleThreadExecutor().execute {
                                runBlocking {
                                    val portfolio = INSTANCE?.portfolioDao()?.getPortfolioById(DEFAULT_PORTFOLIO_ID)
                                    if (portfolio == null) {
                                        INSTANCE?.portfolioDao()?.insert(PortfolioEntity(id = DEFAULT_PORTFOLIO_ID, name = "我的投资组合"))
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
                    // 统一使用纯代码作为基础，构造一致的 ID
                    val pureTicker = stock.ticker.substringAfter(':')
                    val stockIdWithPortfolio = "${pureTicker}_$DEFAULT_PORTFOLIO_ID"
                    
                    database.stockDao().insertStock(stock.copy(id = stockIdWithPortfolio).toEntity(DEFAULT_PORTFOLIO_ID))
                    stock.transactions.forEach { trans ->
                        database.stockDao().insertTransaction(trans.toEntity(pureTicker, DEFAULT_PORTFOLIO_ID))
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

        private fun migrateDatabaseFileIfNeeded(context: Context) {
            val oldDbFile = context.getDatabasePath("stock_database")
            val newDbFile = context.getDatabasePath(DATABASE_NAME)
            if (oldDbFile.exists() && !newDbFile.exists()) {
                Log.i(TAG, "Migrating old database file to new location: ${oldDbFile.absolutePath} -> ${newDbFile.absolutePath}")
                
                // 重命名主文件
                oldDbFile.renameTo(newDbFile)
                
                // 重命名附属文件
                val oldWal = File(oldDbFile.path + "-wal")
                if (oldWal.exists()) oldWal.renameTo(File(newDbFile.path + "-wal"))
                val oldShm = File(oldDbFile.path + "-shm")
                if (oldShm.exists()) oldShm.renameTo(File(newDbFile.path + "-shm"))
                val oldJournal = File(oldDbFile.path + "-journal")
                if (oldJournal.exists()) oldJournal.renameTo(File(newDbFile.path + "-journal"))
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
