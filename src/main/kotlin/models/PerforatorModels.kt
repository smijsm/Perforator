package com.perforator.perforator

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Represents profiling data for a specific line of code
 * @param line Line number in source file
 * @param executionTime Time spent executing this line (ms for CPU, MB for Memory)
 * @param samples Number of samples collected
 * @param methodName Name of the method containing this line
 */
data class ProfilingData(
    val line: Int,
    val executionTime: Double,
    val samples: Int,
    val methodName: String = ""
)

/**
 * Utility for making HTTP requests to Pyroscope server
 */
object HttpRequestHelper {

    /**
     * Makes authenticated or unauthenticated request to Pyroscope
     * @return Response body as string, or null if request fails
     */
    fun makePyroscopeRequest(
        url: String,
        hasAuth: Boolean,
        username: String,
        password: String
    ): String? {
        return try {
            if (hasAuth) {
                val credentials = "$username:$password"
                val encodedCredentials = java.util.Base64.getEncoder()
                    .encodeToString(credentials.toByteArray())
                com.intellij.util.io.HttpRequests.request(url)
                    .tuner { connection ->
                        connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
                    }
                    .connect { it.readString() }
            } else {
                com.intellij.util.io.HttpRequests.request(url)
                    .connect { it.readString() }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Utility for calculating time ranges from human-readable formats
 */
object TimeRangeCalculator {

    /**
     * Converts time period string to start/end timestamps
     * @param timePeriod Format: "now-15m", "now-1h", "now-1d", etc.
     * @return Pair of (startTime, endTime) as ISO-8601 strings
     */
    fun calculateTimeRange(timePeriod: String): Pair<String, String> {
        val now = Instant.now()
        val endTime = now.toString()

        val startTime = when (timePeriod) {
            "now-15m" -> now.minus(15, ChronoUnit.MINUTES)
            "now-30m" -> now.minus(30, ChronoUnit.MINUTES)
            "now-1h" -> now.minus(1, ChronoUnit.HOURS)
            "now-1d" -> now.minus(1, ChronoUnit.DAYS)
            "now-3d" -> now.minus(3, ChronoUnit.DAYS)
            "now-1w" -> now.minus(7, ChronoUnit.DAYS)
            else -> now.minus(15, ChronoUnit.MINUTES)
        }.toString()

        return startTime to endTime
    }
}

/**
 * Decompresses GZIP-compressed byte arrays
 */
fun decompressGzip(compressedBytes: ByteArray): ByteArray {
    return java.util.zip.GZIPInputStream(
        java.io.ByteArrayInputStream(compressedBytes)
    ).use { it.readBytes() }
}