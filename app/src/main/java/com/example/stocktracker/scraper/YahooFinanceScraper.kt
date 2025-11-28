package com.example.stocktracker.scraper

import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap

object YahooFinanceScraper {

    private const val TAG = "YahooFinanceScraper"

    data class ScrapedData(
        val name: String,
        val currentPrice: Double,
        val previousClose: Double,
        val exchangeName: String
    )
    data class DividendInfo(val date: LocalDate, val dividend: Double)
    data class SplitInfo(val date: LocalDate, val numerator: Double, val denominator: Double)
    data class HistoricalDataPoint(val date: LocalDate, val closePrice: Double)

    // Helper to safely encode tickers (e.g., ^IXIC -> %5EIXIC)
    private fun safeTicker(ticker: String): String {
        return try {
            URLEncoder.encode(ticker, "UTF-8")
        } catch (e: Exception) {
            ticker
        }
    }

    fun fetchStockData(ticker: String): ScrapedData? {
        // *** 修改：使用 safeTicker 处理 ticker ***
        val encodedTicker = safeTicker(ticker)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedTicker"
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

            val name = meta.optString("shortName", meta.optString("longName", ticker))
            val currentPrice = meta.getDouble("regularMarketPrice")
            val previousClose = meta.getDouble("previousClose")
            val exchangeName = meta.optString("exchangeName", "")

            Log.d(TAG, "Ticker: $ticker | Fetched Name: $name | Price: $currentPrice | Prev Close: $previousClose | Exchange: $exchangeName")

            if (currentPrice > 0 && previousClose > 0 && name.isNotBlank()) {
                ScrapedData(name, currentPrice, previousClose, exchangeName)
            } else {
                Log.e(TAG, "Parsed invalid values for $ticker. Name: $name, Current: $currentPrice, Previous: $previousClose")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse chart data for $ticker", e)
            null
        }
    }

    fun fetchHistoricalData(ticker: String, startDate: LocalDate): List<HistoricalDataPoint> {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

        // *** 修改：使用 safeTicker 处理 ticker ***
        val encodedTicker = safeTicker(ticker)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedTicker?period1=$startSeconds&period2=$endSeconds&interval=1d"

        Log.d(TAG, "Fetching historical data for $ticker. URL: $url")

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            val historicalData = mutableListOf<HistoricalDataPoint>()
            val jsonObj = JSONObject(jsonResponse)
            val result = jsonObj.getJSONObject("chart").getJSONArray("result").getJSONObject(0)

            val timestamps = result.getJSONArray("timestamp")
            val quotes = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val closePrices = quotes.getJSONArray("close")

            var lastValidPrice = -1.0

            for (i in 0 until timestamps.length()) {
                val date = Instant.ofEpochSecond(timestamps.getLong(i)).atZone(ZoneOffset.UTC).toLocalDate()
                val price = if (closePrices.isNull(i)) {
                    if (lastValidPrice != -1.0) lastValidPrice else continue
                } else {
                    closePrices.getDouble(i).also { lastValidPrice = it }
                }
                historicalData.add(HistoricalDataPoint(date, price))
            }

            Log.d(TAG, "Parsed ${historicalData.size} historical data points for $ticker.")
            historicalData
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse historical data for $ticker", e)
            emptyList()
        }
    }

    fun fetchDividendHistory(ticker: String, startDate: LocalDate): List<DividendInfo>? {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        // *** 修改：使用 safeTicker 处理 ticker ***
        val encodedTicker = safeTicker(ticker)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedTicker?period1=$startSeconds&period2=$endSeconds&interval=1d&events=div"

        Log.d(TAG, "Fetching dividends for $ticker. URL: $url")

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            // ... (Parsing logic remains the same)
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
            dividendList

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse dividend data for $ticker", e)
            null
        }
    }

    fun fetchSplitHistory(ticker: String, startDate: LocalDate): List<SplitInfo>? {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        // *** 修改：使用 safeTicker 处理 ticker ***
        val encodedTicker = safeTicker(ticker)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedTicker?period1=$startSeconds&period2=$endSeconds&interval=1d&events=split"

        Log.d(TAG, "Fetching splits for $ticker. URL: $url")

        return try {
            val jsonResponse = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .ignoreContentType(true)
                .execute()
                .body()

            // ... (Parsing logic remains the same)
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
            splitList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse split data for $ticker", e)
            null
        }
    }

    fun formatExchangeName(code: String): String {
        return when (code.uppercase()) {
            "NMS", "NCM","NASDAQ" -> "NASDAQ"
            "NYQ", "NYSE" -> "NYSE"
            "PCX", "ARCX" -> "NYSE Arca"
            "OPR" -> "OPRA"
            "OTC" -> "OTC"
            "IOB" -> "IOB"
            "LSE" -> "LSE"
            "TOR" -> "TSX"
            "GER" -> "XETRA"
            "FRA" -> "FRA"
            "PAR" -> "Euronext"
            "HKG" -> "HKG"
            "BTS" -> "BATS"
            "BSE" -> "BSE"
            "" -> ""
            else -> code
        }
    }
}