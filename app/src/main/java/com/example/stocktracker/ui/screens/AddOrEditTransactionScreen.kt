package com.example.stocktracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stocktracker.data.SampleData
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.ui.theme.StockTrackerTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

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
                title = { Text(if (isEditMode) "编辑交易" else if (!isNewStockMode) stock.name else "添加持仓") },
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
