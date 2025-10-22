package com.example.stocktracker.scraper

import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneOffset

object YahooFinanceScraper {

    private const val TAG = "YahooFinanceScraper"

    data class ScrapedData(val currentPrice: Double, val previousClose: Double)
    data class DividendInfo(val date: LocalDate, val dividend: Double)

    fun fetchStockData(ticker: String): ScrapedData? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker"
        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            val jsonObj = JSONObject(jsonResponse)
            val chart = jsonObj.getJSONObject("chart")
            val result = chart.getJSONArray("result").getJSONObject(0)
            val meta = result.getJSONObject("meta")

            val currentPrice = meta.getDouble("regularMarketPrice")
            val previousClose = meta.getDouble("previousClose")

            Log.d(TAG, "Ticker: $ticker | Fetched Price: $currentPrice | Fetched Prev Close: $previousClose")

            if (currentPrice > 0 && previousClose > 0) {
                ScrapedData(currentPrice, previousClose)
            } else {
                Log.e(TAG, "Parsed zero values for $ticker. Current: $currentPrice, Previous: $previousClose")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse price data for $ticker", e)
            null
        }
    }

    fun fetchDividendHistory(ticker: String, startDate: LocalDate): List<DividendInfo>? {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?period1=$startSeconds&period2=$endSeconds&interval=1d&events=div"

        Log.d(TAG, "Fetching dividends for $ticker. URL: $url") // 记录请求的URL

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            Log.d(TAG, "Raw dividend JSON response for $ticker: $jsonResponse") // 记录原始JSON响应

            val dividendList = mutableListOf<DividendInfo>()
            val jsonObj = JSONObject(jsonResponse)
            val events = jsonObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0).optJSONObject("events")

            events?.optJSONObject("dividends")?.let { dividendsObj ->
                dividendsObj.keys().forEach { key ->
                    val dividendData = dividendsObj.getJSONObject(key)
                    val timestamp = dividendData.getLong("date")
                    val amount = dividendData.getDouble("amount")
                    val date = LocalDate.ofEpochDay(timestamp / 86400)
                    dividendList.add(DividendInfo(date, amount))
                }
            }

            Log.d(TAG, "Parsed dividends for $ticker: ${dividendList.size} records found.") // 记录解析结果
            dividendList

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse dividend data for $ticker", e)
            null
        }
    }
}

