package com.example.stocktracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.example.stocktracker.ui.components.HeaderMetric
import com.example.stocktracker.ui.components.InfoColumn
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.theme.StockTrackerTheme
import java.time.format.DateTimeFormatter

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

@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun StockDetailScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        StockDetailScreen(SampleData.holdings.last(), {}, {}, {})
    }
}
