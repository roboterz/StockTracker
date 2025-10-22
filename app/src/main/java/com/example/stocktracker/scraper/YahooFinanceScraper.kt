package com.example.stocktracker.scraper

import android.util.Log
import org.jsoup.Jsoup
import org.json.JSONObject

object YahooFinanceScraper {

    data class ScrapedData(val currentPrice: Double, val previousClose: Double)

    fun fetchStockData(ticker: String): ScrapedData? {
        // 改为请求雅虎财经的内部 JSON API 端点，这比抓取 HTML 更稳定
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker"
        return try {
            // 使用 Jsoup 连接并获取 JSON 响应体
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000) // 10秒超时
                .ignoreContentType(true) // 必须设置此项以接收 JSON
                .execute()
                .body()

            // 解析 JSON 数据
            val jsonObj = JSONObject(jsonResponse)
            val chart = jsonObj.getJSONObject("chart")
            val result = chart.getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")

            val currentPrice = meta.getDouble("regularMarketPrice")
            val previousClose = meta.getDouble("previousClose")

            Log.d("YahooFinanceScraper", "Ticker: $ticker | Fetched Price: $currentPrice | Fetched Prev Close: $previousClose")

            if (currentPrice > 0 && previousClose > 0) {
                ScrapedData(currentPrice, previousClose)
            } else {
                Log.e("YahooFinanceScraper", "Parsed zero values for $ticker. Current: $currentPrice, Previous: $previousClose")
                null
            }

        } catch (e: Exception) {
            Log.e("YahooFinanceScraper", "Failed to fetch or parse JSON data for $ticker", e)
            null
        }
    }
}

