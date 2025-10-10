package com.example.stocktracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import kotlin.math.absoluteValue

// --- 辅助组件和函数 (Helpers & Utilities) ---

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
