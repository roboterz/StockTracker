package com.example.stocktracker.scraper

import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneOffset

object YahooFinanceScraper {

    private const val TAG = "YahooFinanceScraper"

    // *** 关键修复：合并数据类以包含股票名称 ***
    data class ScrapedData(
        val name: String,
        val currentPrice: Double,
        val previousClose: Double
    )
    data class DividendInfo(val date: LocalDate, val dividend: Double)
    data class SplitInfo(val date: LocalDate, val numerator: Double, val denominator: Double)

    // *** 关键修复：移除不再有效且冗余的 fetchStockName 函数 ***

    // *** 关键修复：更新 fetchStockData 以同时返回名称和价格 ***
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

            // 从同一响应中解析名称和价格
            val name = meta.optString("shortName", meta.optString("longName", ticker))
            val currentPrice = meta.getDouble("regularMarketPrice")
            val previousClose = meta.getDouble("previousClose")

            Log.d(TAG, "Ticker: $ticker | Fetched Name: $name | Price: $currentPrice | Prev Close: $previousClose")

            if (currentPrice > 0 && previousClose > 0 && name.isNotBlank()) {
                ScrapedData(name, currentPrice, previousClose)
            } else {
                Log.e(TAG, "Parsed invalid values for $ticker. Name: $name, Current: $currentPrice, Previous: $previousClose")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse chart data for $ticker", e)
            null
        }
    }

    fun fetchDividendHistory(ticker: String, startDate: LocalDate): List<DividendInfo>? {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?period1=$startSeconds&period2=$endSeconds&interval=1d&events=div"

        Log.d(TAG, "Fetching dividends for $ticker. URL: $url")

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            Log.d(TAG, "Raw dividend JSON response for $ticker: $jsonResponse")

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

            Log.d(TAG, "Parsed dividends for $ticker: ${dividendList.size} records found.")
            dividendList

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse dividend data for $ticker", e)
            null
        }
    }

    fun fetchSplitHistory(ticker: String, startDate: LocalDate): List<SplitInfo>? {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?period1=$startSeconds&period2=$endSeconds&interval=1d&events=split"

        Log.d(TAG, "Fetching splits for $ticker. URL: $url")

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            Log.d(TAG, "Raw split JSON response for $ticker: $jsonResponse")

            val splitList = mutableListOf<SplitInfo>()
            val jsonObj = JSONObject(jsonResponse)
            val events = jsonObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0).optJSONObject("events")

            events?.optJSONObject("splits")?.let { splitsObj ->
                splitsObj.keys().forEach { key ->
                    val splitData = splitsObj.getJSONObject(key)
                    val timestamp = splitData.getLong("date")
                    val numerator = splitData.getDouble("numerator")
                    val denominator = splitData.getDouble("denominator")
                    val date = LocalDate.ofEpochDay(timestamp / 86400)
                    splitList.add(SplitInfo(date, numerator, denominator))
                }
            }
            Log.d(TAG, "Parsed splits for $ticker: ${splitList.size} records found.")
            splitList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse split data for $ticker", e)
            null
        }
    }
}

