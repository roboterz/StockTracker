package com.example.stocktracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.stocktracker.data.SampleData
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.ui.components.PLText
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.theme.StockTrackerTheme

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
    // 股票列表
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


@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun PortfolioScreenPreview() {
    StockTrackerTheme(darkTheme = true) {
        PortfolioScreen(SampleData.holdings, {}, {})
    }
}
