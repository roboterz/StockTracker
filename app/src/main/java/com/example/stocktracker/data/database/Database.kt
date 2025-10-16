package com.example.stocktracker.data.database

import androidx.room.*
import com.example.stocktracker.data.SampleData
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.data.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.Executors

// --- 数据库层 (Room Database Layer) ---

// 数据库实体 (Entities)
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
    val quantity: Int,
    val price: Double,
    val fee: Double
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
}


// 数据访问对象 (DAO)
@Dao
interface StockDao {
    @androidx.room.Transaction
    @Query("SELECT * FROM stocks")
    fun getAllStocksWithTransactions(): Flow<List<StockWithTransactions>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: StockHoldingEntity)

    @Update
    suspend fun updateStock(stock: StockHoldingEntity) // 确认此方法存在

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteTransactionById(transactionId: String)
}

// 数据库
@Database(entities = [StockHoldingEntity::class, TransactionEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class StockDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao

    companion object {
        @Volatile
        private var INSTANCE: StockDatabase? = null

        fun getDatabase(context: android.content.Context): StockDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockDatabase::class.java,
                    "stock_database"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // 预填充数据库
                            Executors.newSingleThreadExecutor().execute {
                                INSTANCE?.let { database ->
                                    runBlocking {
                                        SampleData.holdings.forEach{ stock ->
                                            database.stockDao().insertStock(stock.toEntity())
                                            stock.transactions.forEach { trans ->
                                                database.stockDao().insertTransaction(trans.toEntity(stock.id))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
