package com.rawsmusic.module.player.dsp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** AutoEq repository search/download client. */
class AutoEqRepository {

    companion object {
        private const val TAG = "AutoEqRepository"
        private const val GITHUB_API = "https://api.github.com"
        private const val RAW_CONTENT_URL = "https://raw.githubusercontent.com"
        private const val REPO_OWNER = "jaakkopasanen"
        private const val REPO_NAME = "AutoEq"
        private const val BRANCH = "master"
        private const val CACHE_DURATION = 3_600_000L
        private const val MAX_SEARCH_RESULTS = 120

        private var cachedCatalog: List<AutoEqSearchResult>? = null
        private var cacheTimestamp: Long = 0
    }

    suspend fun search(query: String): List<AutoEqSearchResult> = withContext(Dispatchers.IO) {
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) return@withContext emptyList()

        try {
            val catalog = getCatalog()
            if (catalog.isEmpty()) {
                Log.e(TAG, "AutoEq catalog is empty")
                return@withContext emptyList()
            }
            rankMatches(catalog, normalizedQuery).take(MAX_SEARCH_RESULTS)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            emptyList()
        }
    }

    /**
     * Prefer AutoEq's generated results/INDEX.md. It is purpose-built as the complete result index,
     * is much smaller than the whole Git tree, and avoids recursive-tree truncation/rate-limit risk.
     * The Git tree remains a compatibility fallback.
     */
    private suspend fun getCatalog(): List<AutoEqSearchResult> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedCatalog?.takeIf { now - cacheTimestamp < CACHE_DURATION }?.let {
            return@withContext it
        }

        val fromIndex = runCatching { fetchIndexCatalog() }
            .onFailure { Log.w(TAG, "INDEX.md catalog fetch failed; falling back to Git tree", it) }
            .getOrDefault(emptyList())

        val catalog = if (fromIndex.isNotEmpty()) fromIndex else {
            parseFilePaths(getFileTree())
        }

        if (catalog.isNotEmpty()) {
            cachedCatalog = catalog.distinctBy { it.path }
            cacheTimestamp = now
        }
        cachedCatalog.orEmpty()
    }

    private fun fetchIndexCatalog(): List<AutoEqSearchResult> {
        val url = "$RAW_CONTENT_URL/$REPO_OWNER/$REPO_NAME/$BRANCH/results/INDEX.md"
        val text = fetchText(url) ?: return emptyList()
        return parseIndex(text)
    }

    private fun parseIndex(text: String): List<AutoEqSearchResult> {
        val regex = Regex("""^-\s+\[(.+)]\((\./.+)\)\s+by\s+(.+?)(?:\s+on\s+(.+))?$""")
        return text.lineSequence().mapNotNull { line ->
            val match = regex.matchEntire(line.trim()) ?: return@mapNotNull null
            val displayName = match.groupValues[1].trim()
            val encodedDirectory = match.groupValues[2].removePrefix("./")
            val decodedSegments = encodedDirectory.split('/').map(::decodePathSegment)
            if (decodedSegments.size < 3) return@mapNotNull null

            val source = decodedSegments[0].ifBlank { match.groupValues[3].trim() }
            val deviceType = decodedSegments[1]
            val folderName = decodedSegments.drop(2).joinToString("/")
            val headphoneName = displayName.ifBlank { folderName }
            val fileName = "$folderName ParametricEQ.txt"
            val decodedPath = "results/${decodedSegments.joinToString("/")}/$fileName"
            resultForPath(
                headphoneName = headphoneName,
                source = source,
                deviceType = deviceType,
                fileName = fileName,
                path = decodedPath,
            )
        }.toList()
    }

    /** GitHub recursive-tree fallback. Truncation is surfaced instead of silently treated as complete. */
    private fun getFileTree(): List<String> {
        return try {
            val url = URL("$GITHUB_API/repos/$REPO_OWNER/$REPO_NAME/git/trees/$BRANCH?recursive=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "RawSMusic-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }
            connection.useConnection { conn ->
                if (conn.responseCode != 200) {
                    Log.e(TAG, "Failed to get file tree: ${conn.responseCode}")
                    return@useConnection emptyList()
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("truncated", false)) {
                    Log.w(TAG, "GitHub recursive tree is truncated; AutoEq search fallback may be incomplete")
                }
                val tree = json.getJSONArray("tree")
                buildList {
                    for (i in 0 until tree.length()) {
                        val item = tree.getJSONObject(i)
                        if (item.optString("type") == "blob") add(item.getString("path"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get file tree error", e)
            emptyList()
        }
    }

    suspend fun download(result: AutoEqSearchResult): AutoEqPreset? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading from: ${result.downloadUrl}")
            val content = fetchText(result.downloadUrl) ?: return@withContext null
            AutoEqPreset.parse(
                name = result.headphoneName,
                source = result.source,
                deviceType = result.deviceType,
                originPath = result.path,
                text = content,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${result.downloadUrl}", e)
            null
        }
    }

    private fun parseFilePaths(paths: List<String>): List<AutoEqSearchResult> {
        return paths.asSequence()
            .filter { it.contains("ParametricEQ", ignoreCase = true) && it.endsWith(".txt", ignoreCase = true) }
            .mapNotNull { path ->
                try {
                    val parts = path.split("/")
                    if (parts.size < 5 || parts[0] != "results") return@mapNotNull null
                    val source = parts[1]
                    val deviceType = parts[2]
                    val headphoneName = parts.drop(3).dropLast(1).joinToString("/")
                    resultForPath(
                        headphoneName = headphoneName,
                        source = source,
                        deviceType = deviceType,
                        fileName = parts.last(),
                        path = path,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Parse file path error: $path", e)
                    null
                }
            }
            .distinctBy { it.path }
            .toList()
    }

    private fun resultForPath(
        headphoneName: String,
        source: String,
        deviceType: String,
        fileName: String,
        path: String,
    ): AutoEqSearchResult {
        val encodedPath = encodePath(path)
        return AutoEqSearchResult(
            headphoneName = headphoneName,
            source = source,
            deviceType = deviceType,
            fileName = fileName,
            path = path,
            downloadUrl = "$RAW_CONTENT_URL/$REPO_OWNER/$REPO_NAME/$BRANCH/$encodedPath",
            htmlUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/blob/$BRANCH/$encodedPath",
        )
    }

    private fun rankMatches(
        catalog: List<AutoEqSearchResult>,
        normalizedQuery: String,
    ): List<AutoEqSearchResult> {
        val tokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        return catalog.mapNotNull { result ->
            val name = normalizeSearchText(result.headphoneName)
            val source = normalizeSearchText(result.source)
            val device = normalizeSearchText(result.deviceType)
            val combined = "$name $source $device"
            val score = when {
                name == normalizedQuery -> 0
                name.startsWith(normalizedQuery) -> 1
                tokens.all { it in name } -> 2
                normalizedQuery in name -> 3
                tokens.all { it in combined } -> 4
                else -> return@mapNotNull null
            }
            score to result
        }.sortedWith(
            compareBy<Pair<Int, AutoEqSearchResult>>({ it.first }, { it.second.headphoneName.length }, { it.second.headphoneName.lowercase() })
        ).map { it.second }
    }

    private fun normalizeSearchText(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun decodePathSegment(segment: String): String = runCatching {
        URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(segment)

    private fun encodePath(path: String): String {
        return try {
            URI(null, null, "/$path", null).rawPath.removePrefix("/")
        } catch (_: Exception) {
            path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
        }
    }

    private fun fetchText(urlText: String): String? {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RawSMusic-Android")
            connectTimeout = 15000
            readTimeout = 15000
        }
        return connection.useConnection { conn ->
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "HTTP ${conn.responseCode}: $urlText")
                null
            }
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}

data class AutoEqSearchResult(
    val headphoneName: String,
    val source: String,
    val deviceType: String,
    val fileName: String,
    val path: String,
    val downloadUrl: String,
    val htmlUrl: String,
) {
    val displayName: String
        get() = "$headphoneName ($source)"

    val cacheIdentity: String
        get() = path.takeIf { it.isNotBlank() }
            ?: listOf(source, deviceType, headphoneName).joinToString("\u001f")
}
