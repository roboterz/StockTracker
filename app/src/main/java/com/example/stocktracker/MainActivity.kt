package com.example.stocktracker

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.absoluteValue

// --- UI 数据模型 (UI Data Models) ---
// 这些数据类用于UI显示，它们由数据库实体映射而来

enum class TransactionType {
    BUY, SELL, DIVIDEND
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val type: TransactionType,
    val quantity: Int,
    val price: Double,
    val fee: Double = 0.0
)

data class StockHolding(
    val id: String,
    val name: String,
    val ticker: String,
    val currentPrice: Double,
    val transactions: List<Transaction>
) {
    val totalQuantity: Int
        get() = transactions.sumOf { if (it.type == TransactionType.BUY) it.quantity else if(it.type == TransactionType.SELL) -it.quantity else 0 }

    val totalCost: Double
        get() = transactions.filter { it.type == TransactionType.BUY }.sumOf { it.quantity * it.price + it.fee }

    val totalSoldValue: Double
        get() = transactions.filter { it.type == TransactionType.SELL }.sumOf { it.quantity * it.price - it.fee }

    val costBasis: Double
        get() = if (totalQuantity > 0) (totalCost - totalSoldValue) / totalQuantity else 0.0

    val marketValue: Double
        get() = totalQuantity * currentPrice

    val totalPL: Double
        get() = marketValue - (totalCost - totalSoldValue)

    val totalPLPercent: Double
        get() = if ((totalCost - totalSoldValue) > 0) totalPL / (totalCost - totalSoldValue) * 100 else 0.0

    companion object {
        val empty = StockHolding("", "", "", 0.0, emptyList())
    }
}


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
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Pre-populate database
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


// --- ViewModel ---

data class StockUiState(
    val holdings: List<StockHolding> = emptyList(),
    val currentScreen: Screen = Screen.Portfolio,
    val selectedStockId: String? = null,
    val transactionToEditId: String? = null
) {
    val selectedStock: StockHolding
        get() = holdings.find { it.id == selectedStockId } ?: StockHolding.empty

    val transactionToEdit: Transaction?
        get() = selectedStock.transactions.find { it.id == transactionToEditId }
}

class StockViewModel(application: Application) : ViewModel() {
    private val dao = StockDatabase.getDatabase(application).stockDao()

    private val _uiState = MutableStateFlow(StockUiState())
    val uiState: StateFlow<StockUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dao.getAllStocksWithTransactions()
                .map { list -> list.map { it.toUIModel() } }
                .catch { throwable ->
                    // Handle error
                }
                .collect { holdings ->
                    _uiState.update { it.copy(holdings = holdings) }
                }
        }
    }

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun selectStock(stockId: String) {
        _uiState.update { it.copy(selectedStockId = stockId, currentScreen = Screen.Details) }
    }

    fun prepareNewTransaction(stockId: String? = null) {
        _uiState.update {
            it.copy(
                selectedStockId = stockId, // Bug fix: Explicitly set or clear the stock ID
                transactionToEditId = null,
                currentScreen = Screen.AddOrEditTransaction
            )
        }
    }

    fun prepareEditTransaction(transactionId: String) {
        _uiState.update {
            it.copy(
                transactionToEditId = transactionId,
                currentScreen = Screen.AddOrEditTransaction
            )
        }
    }

    fun navigateBack() {
        val currentState = _uiState.value
        val newScreen = when (currentState.currentScreen) {
            Screen.AddOrEditTransaction -> if (currentState.selectedStockId != null) Screen.Details else Screen.Portfolio
            Screen.Details -> Screen.Portfolio
            else -> Screen.Portfolio
        }
        _uiState.update { it.copy(currentScreen = newScreen, transactionToEditId = null) }
    }

    fun saveOrUpdateTransaction(transaction: Transaction, stockId: String?, newStockIdentifier: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val idToProcess = (stockId ?: newStockIdentifier).uppercase()
            if (idToProcess.isBlank()) return@launch

            val existingStock = _uiState.value.holdings.find { it.id.equals(idToProcess, ignoreCase = true) }

            if (existingStock != null) {
                // It's an existing stock, just insert or update the transaction
                dao.insertTransaction(transaction.toEntity(existingStock.id))
            } else {
                // It's a new stock
                val newStock = StockHolding(
                    id = idToProcess,
                    name = newStockIdentifier,
                    ticker = "NASDAQ:$idToProcess",
                    currentPrice = transaction.price,
                    transactions = emptyList()
                )
                dao.insertStock(newStock.toEntity())
                dao.insertTransaction(transaction.toEntity(newStock.id))
            }
            navigateBack()
        }
    }

    fun deleteTransaction(transactionId: String, stockId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTransactionById(transactionId)
            navigateBack()
        }
    }
}

class StockViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StockViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StockViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


// --- 主程序入口 (Main Activity) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockTrackerTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val viewModel: StockViewModel = viewModel(
                        factory = StockViewModelFactory(context.applicationContext as Application)
                    )
                    StockApp(viewModel)
                }
            }
        }
    }
}

// --- App导航和状态管理 (App Navigation & State) ---
enum class Screen {
    Portfolio,
    Details,
    AddOrEditTransaction
}

@Composable
fun StockApp(viewModel: StockViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState.currentScreen) {
        Screen.Portfolio -> PortfolioScreen(
            holdings = uiState.holdings,
            onStockClick = { stock -> viewModel.selectStock(stock.id) },
            onAddClick = { viewModel.prepareNewTransaction() }
        )
        Screen.Details -> {
            StockDetailScreen(
                stock = uiState.selectedStock,
                onBack = { viewModel.navigateBack() },
                onAddTransaction = { viewModel.prepareNewTransaction(uiState.selectedStockId) },
                onTransactionClick = { transaction -> viewModel.prepareEditTransaction(transaction.id) }
            )
        }
        Screen.AddOrEditTransaction -> {
            AddOrEditTransactionScreen(
                stock = uiState.selectedStock,
                transactionToEdit = uiState.transactionToEdit,
                onBack = { viewModel.navigateBack() },
                onSave = { transaction, stockId, newStockId ->
                    viewModel.saveOrUpdateTransaction(transaction, stockId, newStockId)
                },
                onDelete = { transactionId, stockId ->
                    viewModel.deleteTransaction(transactionId, stockId)
                }
            )
        }
    }
}

// --- 界面 (Screens) 和 可组合组件 (Composables) ---
// (The UI code below this point is largely the same, but now it reads data from the ViewModel's state
// and calls ViewModel functions for events.)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    holdings: List<StockHolding>,
    onStockClick: (StockHolding) -> Unit,
    onAddClick: () -> Unit
) {
    val totalMarketValue = holdings.sumOf { it.marketValue }
    val totalPL = holdings.sumOf { it.totalPL }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Name") },
                actions = {
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = "添加持仓")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1C23)
                )
            )
        },
        containerColor = Color(0xFF1A1C23)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                PortfolioHeader(totalMarketValue, totalPL)
                Spacer(modifier = Modifier.height(24.dp))
                Text("持有资产", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(holdings) { stock ->
                StockHoldingItem(stock = stock, onClick = { onStockClick(stock) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stock: StockHolding,
    onBack: () -> Unit,
    onAddTransaction: () -> Unit,
    onTransactionClick: (Transaction) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stock.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAddTransaction) {
                        Icon(Icons.Default.Add, contentDescription = "添加交易")
                    }
                    IconButton(onClick = { /* TODO */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1C23)
                )
            )
        },
        containerColor = Color(0xFF1A1C23)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                StockDetailHeader(stock)
                Spacer(modifier = Modifier.height(24.dp))
                Text("历史记录", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(stock.transactions.sortedByDescending { it.date }) { transaction ->
                TransactionItem(transaction, onClick = { onTransactionClick(transaction) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditTransactionScreen(
    stock: StockHolding,
    transactionToEdit: Transaction?,
    onBack: () -> Unit,
    onSave: (transaction: Transaction, stockId: String?, newStockIdentifier: String) -> Unit,
    onDelete: (transactionId: String, stockId: String) -> Unit
) {
    val isEditMode = transactionToEdit != null
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    var transactionType by remember { mutableStateOf(transactionToEdit?.type ?: TransactionType.BUY) }
    var price by remember { mutableStateOf(transactionToEdit?.price?.toString() ?: if (stock.id.isNotEmpty()) stock.currentPrice.toString() else "") }
    var quantity by remember { mutableStateOf(transactionToEdit?.quantity?.toString() ?: "") }
    var fee by remember { mutableStateOf(transactionToEdit?.fee?.toString() ?: "") }
    var date by remember { mutableStateOf(transactionToEdit?.date?.format(formatter) ?: LocalDate.now().format(formatter)) }
    var newStockIdentifier by remember { mutableStateOf("") }
    val isNewStockMode = stock.id.isEmpty() && !isEditMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "编辑交易" else if (!isNewStockMode) stock.name else "模拟持仓") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (isNewStockMode) {
                OutlinedTextField(
                    value = newStockIdentifier,
                    onValueChange = { newStockIdentifier = it },
                    label = { Text("股票名称/代码") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text("最新价: ${stock.currentPrice}", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { transactionType = TransactionType.BUY },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (transactionType == TransactionType.BUY) MaterialTheme.colorScheme.primary else Color.DarkGray
                    )
                ) { Text("买入") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { transactionType = TransactionType.SELL },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (transactionType == TransactionType.SELL) Color(0xFFE53935) else Color.DarkGray
                    )
                ) { Text("卖出") }
            }
            Spacer(modifier = Modifier.height(16.dp))

            TransactionInputRow(label = "日期", value = date, onValueChange = { date = it })
            TransactionInputRow(label = "价格", value = price, onValueChange = { price = it }, isNumeric = true)
            TransactionInputRow(label = "数量", value = quantity, onValueChange = { quantity = it }, placeholder = "请输入股数", isNumeric = true)
            TransactionInputRow(label = "手续费", value = fee, onValueChange = { fee = it }, placeholder = "请输入手续费", isNumeric = true)

            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val p = price.toDoubleOrNull() ?: 0.0
                    val q = quantity.toIntOrNull() ?: 0
                    if ((!isNewStockMode || newStockIdentifier.isNotBlank()) && p > 0 && q > 0) {
                        val finalTransaction = Transaction(
                            id = transactionToEdit?.id ?: UUID.randomUUID().toString(),
                            date = LocalDate.parse(date, formatter),
                            type = transactionType,
                            quantity = q,
                            price = p,
                            fee = fee.toDoubleOrNull() ?: 0.0
                        )
                        onSave(finalTransaction, if(!isNewStockMode) stock.id else null, newStockIdentifier)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("保存", fontSize = 18.sp)
            }
            if (isEditMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        transactionToEdit?.id?.let { transactionId ->
                            onDelete(transactionId, stock.id)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("删除", fontSize = 18.sp)
                }
            }
        }
    }
}

// --- 辅助组件和函数 (Helpers & Utilities) ---

@Composable
fun PortfolioHeader(totalValue: Double, totalPL: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E48))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("总市值 (USD)", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatCurrency(totalValue),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text("当日盈亏", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                    PLText(value = -1207.55, percent = -3.27, isPercentFirst = false)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("持仓盈亏", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                    PLText(value = -13022.99, percent = -29.89, isPercentFirst = false)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("总盈亏", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                    PLText(value = totalPL, percent = 38.06, isPercentFirst = false)
                }
            }
        }
    }
}

@Composable
fun StockHoldingItem(stock: StockHolding, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(stock.name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stock.name, fontWeight = FontWeight.Bold)
            Text(stock.ticker, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatCurrency(stock.marketValue))
            PLText(value = stock.totalPL, percent = stock.totalPLPercent)
        }
    }
}

@Composable
fun StockDetailHeader(stock: StockHolding) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E48))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("总市值 (USD)", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
            Text(
                text = formatCurrency(stock.marketValue),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                HeaderMetric("当日盈亏", -43.50, -1.13)
                HeaderMetric("持仓盈亏", -251.50, -6.24)
                HeaderMetric("总盈亏", stock.totalPL, stock.totalPLPercent)
            }
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = Color.Gray.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                InfoColumn("当前价格", stock.currentPrice.toString())
                InfoColumn("成本价", formatCurrency(stock.costBasis, showSign = false))
                InfoColumn("数量", stock.totalQuantity.toString())
                InfoColumn("成本", formatCurrency(stock.totalCost, showSign = false))
            }
        }
    }
}

@Composable
fun TransactionItem(transaction: Transaction, onClick: () -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.5f)) {
            Text(transaction.date.format(formatter), style = MaterialTheme.typography.labelMedium)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (transaction.type) {
                    TransactionType.BUY -> "买入"
                    TransactionType.SELL -> "卖出"
                    TransactionType.DIVIDEND -> "分红"
                },
                color = when (transaction.type) {
                    TransactionType.BUY -> Color(0xFF4CAF50) // Green
                    TransactionType.SELL -> Color(0xFFF44336) // Red
                    TransactionType.DIVIDEND -> Color(0xFF2196F3) // Blue
                },
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(if (transaction.type != TransactionType.DIVIDEND) transaction.quantity.toString() else "--")
        }
        Column(modifier = Modifier.weight(1.5f), horizontalAlignment = Alignment.End) {
            val amount = transaction.quantity * transaction.price
            Text(formatCurrency(amount, showSign = false))
            Spacer(modifier = Modifier.height(2.dp))
            Text(transaction.price.toString(), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun TransactionInputRow(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String = "", isNumeric: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.DarkGray,
                unfocusedContainerColor = Color.DarkGray,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
fun RowScope.HeaderMetric(label: String, value: Double, percent: Double) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        PLText(value = value, percent = percent, isPercentFirst = false, valueFontSize = 16.sp)
    }
}

@Composable
fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun PLText(
    value: Double,
    percent: Double,
    isPercentFirst: Boolean = true,
    valueFontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize
) {
    val color = if (value >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
    val formattedValue = formatCurrency(value, showSign = true)
    val formattedPercent = "${String.format("%.2f", percent.absoluteValue)}%"

    val firstText = if (isPercentFirst) formattedPercent else formattedValue
    val secondText = if (isPercentFirst) formattedValue else formattedPercent

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = firstText, color = color, fontSize = valueFontSize, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = secondText, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

fun formatCurrency(value: Double, showSign: Boolean = false): String {
    val format = DecimalFormat("#,##0.00")
    return if (showSign) {
        if (value > 0) "+${format.format(value)}" else format.format(value)
    } else {
        format.format(value)
    }
}


// --- 模拟数据 (用于首次创建数据库时预填充) ---

object SampleData {
    val holdings = listOf(
        StockHolding(
            id = "TSLA",
            name = "特斯拉",
            ticker = "NASDAQ:TSLA",
            currentPrice = 223.52,
            transactions = listOf(
                Transaction(date = LocalDate.of(2025, 9, 5), type = TransactionType.BUY, quantity = 10, price = 164.67),
                Transaction(date = LocalDate.of(2025, 9, 9), type = TransactionType.BUY, quantity = 20, price = 167.48),
                Transaction(date = LocalDate.of(2025, 9, 10), type = TransactionType.SELL, quantity = 5, price = 178.09)
            )
        ),
        StockHolding(
            id = "NVDA",
            name = "英伟达",
            ticker = "NASDAQ:NVDA",
            currentPrice = 177.56,
            transactions = listOf(
                Transaction(date = LocalDate.of(2025, 9, 4), type = TransactionType.BUY, quantity = 2, price = 170.67),
                Transaction(date = LocalDate.of(2025, 9, 5), type = TransactionType.BUY, quantity = 2, price = 165.13),
            )
        )
    )
}

// --- UI Theme & Previews ---
// (No changes needed for Theme and Previews, but they need the new ViewModel architecture to work)

@Composable
fun StockTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Using default dark/light color schemes for simplicity
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF121212),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC6),
            background = Color.White,
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.Black,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(), // Default typography
        content = content
    )
}


@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun PortfolioScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        PortfolioScreen(SampleData.holdings, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun StockDetailScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        StockDetailScreen(SampleData.holdings.last(), {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun AddTransactionScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        AddOrEditTransactionScreen(
            stock = StockHolding.empty,
            transactionToEdit = null,
            onBack = {},
            onSave = { _, _, _ -> },
            onDelete = { _, _ ->}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun EditTransactionScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        AddOrEditTransactionScreen(
            stock = SampleData.holdings.first(),
            transactionToEdit = SampleData.holdings.first().transactions.first(),
            onBack = {},
            onSave = { _, _, _ -> },
            onDelete = { _, _ ->}
        )
    }
}

