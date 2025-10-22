package com.example.stocktracker.network

/**
 * 用于解析从 Financial Modeling Prep API 的搜索端点返回的数据。
 * 字段已更新以匹配新的 API 响应。
 */
data class SymbolSearchResult(
    val symbol: String,
    val name: String,
    val currency: String,
    val exchangeFullName: String,
    val exchange: String
)

