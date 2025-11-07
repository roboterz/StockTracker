package com.example.stocktracker.scraper

import android.util.Log
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap

object YahooFinanceScraper {

    private const val TAG = "YahooFinanceScraper"

    // ... (ScrapedData, DividendInfo, SplitInfo data classes remain the same) ...
    data class ScrapedData(
        val name: String,
        val currentPrice: Double,
        val previousClose: Double,
        val exchangeName: String // *** 新增：交易所代码 (例如 "NMS", "NYQ") ***
    )
    data class DividendInfo(val date: LocalDate, val dividend: Double)
    data class SplitInfo(val date: LocalDate, val numerator: Double, val denominator: Double)

    // *** 新增：用于历史数据的数据类 ***
    data class HistoricalDataPoint(val date: LocalDate, val closePrice: Double)


    // ... (fetchStockData remains the same) ...
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

            // 从同一响应中解析名称、价格和交易所
            val name = meta.optString("shortName", meta.optString("longName", ticker))
            val currentPrice = meta.getDouble("regularMarketPrice")
            val previousClose = meta.getDouble("previousClose")
            val exchangeName = meta.optString("exchangeName", "") // *** 新增：获取交易所代码 ***

            Log.d(TAG, "Ticker: $ticker | Fetched Name: $name | Price: $currentPrice | Prev Close: $previousClose | Exchange: $exchangeName")

            if (currentPrice > 0 && previousClose > 0 && name.isNotBlank()) {
                ScrapedData(name, currentPrice, previousClose, exchangeName) // *** 传递交易所代码 ***
            } else {
                Log.e(TAG, "Parsed invalid values for $ticker. Name: $name, Current: $currentPrice, Previous: $previousClose")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch or parse chart data for $ticker", e)
            null
        }
    }

    // *** 新增：获取历史价格数据的方法 ***
    fun fetchHistoricalData(ticker: String, startDate: LocalDate): List<HistoricalDataPoint> {
        val startSeconds = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val endSeconds = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() // +1 to ensure we get today's data if available
        // 使用 1d 间隔，不请求 events
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?period1=$startSeconds&period2=$endSeconds&interval=1d"

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

            var lastValidPrice = -1.0 // 用于填充 null（非交易日）

            for (i in 0 until timestamps.length()) {
                val date = Instant.ofEpochSecond(timestamps.getLong(i)).atZone(ZoneOffset.UTC).toLocalDate()
                val price = if (closePrices.isNull(i)) {
                    // 如果当天价格为 null（例如假期），使用上一个有效价格
                    if (lastValidPrice != -1.0) lastValidPrice else continue // 如果开头就是null，则跳过
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
    // *** 新增结束 ***


    // ... (fetchDividendHistory and fetchSplitHistory remain the same) ...
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

    // ... (formatExchangeName remains the same) ...
    fun formatExchangeName(code: String): String {
        return when (code.uppercase()) {
            "NMS", "NCM","NASDAQ" -> "NASDAQ" // NMS 是 Nasdaq
            "NYQ", "NYSE" -> "NYSE" // NYQ 是 NYSE
            "PCX", "ARCX" -> "NYSE Arca" // ARCX 是 NYSE Arca
            "OPR" -> "OPRA" // OPRA (Options)
            "OTC" -> "OTC"
            "IOB" -> "IOB"
            "LSE" -> "LSE" // London
            "TOR" -> "TSX" // Toronto
            "GER" -> "XETRA" // Germany
            "FRA" -> "FRA" // Frankfurt
            "PAR" -> "Euronext" // Paris
            "HKG" -> "HKG" // Hong Kong
            "BTS" -> "BATS" // BTS
            "BSE" -> "BSE" // Bombay
            "" -> ""
            else -> code // 如果不认识，则默认返回原始代码
        }
    }
}
