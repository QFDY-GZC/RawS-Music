package com.rawsmusic.module.player.dsp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * AutoEq 仓库
 * 负责从 GitHub API 搜索和下载耳机预设
 */
class AutoEqRepository {

    companion object {
        private const val TAG = "AutoEqRepository"
        private const val GITHUB_API = "https://api.github.com"
        private const val RAW_CONTENT_URL = "https://raw.githubusercontent.com"
        private const val REPO_OWNER = "jaakkopasanen"
        private const val REPO_NAME = "AutoEq"
        private const val BRANCH = "master"
        private const val RESULTS_PATH = "results"
        
        // 缓存文件树，避免重复请求
        private var cachedFileTree: List<String>? = null
        private var cacheTimestamp: Long = 0
        private const val CACHE_DURATION = 3600000L // 1小时缓存
    }

    /**
     * 搜索耳机预设
     * @param query 搜索关键词（耳机名称）
     * @return 搜索结果列表
     */
    suspend fun search(query: String): List<AutoEqSearchResult> = withContext(Dispatchers.IO) {
        try {
            // 使用 Git Trees API 获取文件树
            val fileTree = getFileTree()
            if (fileTree.isEmpty()) {
                Log.e(TAG, "Failed to get file tree")
                return@withContext emptyList()
            }
            
            // 在本地过滤 ParametricEQ.txt 文件
            val parametricEqFiles = fileTree.filter { 
                it.contains("ParametricEQ", ignoreCase = true) && 
                it.endsWith(".txt") 
            }
            
            // 根据查询关键词过滤
            val queryLower = query.lowercase()
            val matchingFiles = parametricEqFiles.filter { path ->
                val pathLower = path.lowercase()
                pathLower.contains(queryLower)
            }
            
            // 解析结果
            parseFilePaths(matchingFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            emptyList()
        }
    }
    
    /**
     * 获取仓库文件树
     * 使用 Git Trees API 获取完整文件树并缓存
     */
    private suspend fun getFileTree(): List<String> = withContext(Dispatchers.IO) {
        // 检查缓存是否有效
        val currentTime = System.currentTimeMillis()
        if (cachedFileTree != null && (currentTime - cacheTimestamp) < CACHE_DURATION) {
            return@withContext cachedFileTree!!
        }
        
        try {
            val url = URL("$GITHUB_API/repos/$REPO_OWNER/$REPO_NAME/git/trees/$BRANCH?recursive=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "RawSMusic-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }
            
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val tree = json.getJSONArray("tree")
                
                val paths = mutableListOf<String>()
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    val path = item.getString("path")
                    val type = item.getString("type")
                    // 只要文件，不要目录
                    if (type == "blob") {
                        paths.add(path)
                    }
                }
                
                // 更新缓存
                cachedFileTree = paths
                cacheTimestamp = currentTime
                
                paths
            } else {
                Log.e(TAG, "Failed to get file tree: ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get file tree error", e)
            emptyList()
        }
    }

    /**
     * 下载预设文件
     * @param result 搜索结果
     * @return 预设对象，如果下载失败则返回 null
     */
    suspend fun download(result: AutoEqSearchResult): AutoEqPreset? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading from: ${result.downloadUrl}")
            val url = URL(result.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RawSMusic-Android")
                connectTimeout = 15000
                readTimeout = 15000
            }

            if (connection.responseCode == 200) {
                val content = connection.inputStream.bufferedReader().readText()
                AutoEqPreset.parse(
                    name = result.headphoneName,
                    source = result.source,
                    text = content
                )
            } else {
                Log.e(TAG, "Download failed: ${connection.responseCode}, URL: ${result.downloadUrl}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${result.downloadUrl}", e)
            null
        }
    }

    /**
     * 解析文件路径列表为搜索结果
     */
    private fun parseFilePaths(paths: List<String>): List<AutoEqSearchResult> {
        val results = mutableListOf<AutoEqSearchResult>()
        
        for (path in paths) {
            try {
                // 路径格式: results/{source}/{device_type}/{headphone_name}/ParametricEQ.txt
                val pathParts = path.split("/")
                if (pathParts.size < 5) continue
                if (pathParts[0] != "results") continue
                
                val source = pathParts[1] // 测量来源
                val deviceType = pathParts[2] // 设备类型
                val headphoneName = pathParts[3] // 耳机名称
                val fileName = pathParts[4] // 文件名
                
                // 构建下载 URL - 只编码每个路径段，保留斜杠
                val encodedPath = encodePath(path)
                val downloadUrl = "$RAW_CONTENT_URL/$REPO_OWNER/$REPO_NAME/$BRANCH/$encodedPath"
                
                // 构建 HTML URL
                val htmlUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/blob/$BRANCH/$encodedPath"
                
                results.add(
                    AutoEqSearchResult(
                        headphoneName = headphoneName,
                        source = source,
                        deviceType = deviceType,
                        fileName = fileName,
                        path = path,
                        downloadUrl = downloadUrl,
                        htmlUrl = htmlUrl
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Parse file path error: $path", e)
            }
        }
        
        return results
    }
    
    /**
     * 编码URL路径，只编码每个路径段，保留斜杠
     */
    private fun encodePath(path: String): String {
        return try {
            // 使用URI来正确编码路径
            val uri = URI(path)
            uri.toASCIIString()
        } catch (e: Exception) {
            // 备用方案：手动编码每个路径段
            path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20")  // 将加号替换为%20
                    .replace("%2F", "/")  // 保留斜杠
            }
        }
    }
}

/**
 * AutoEq 搜索结果
 */
data class AutoEqSearchResult(
    val headphoneName: String,    // 耳机名称
    val source: String,           // 测量来源 (crinacle, oratory1990 等)
    val deviceType: String,       // 设备类型 (in-ear, over-ear 等)
    val fileName: String,         // 文件名
    val path: String,             // 完整路径
    val downloadUrl: String,      // 下载 URL
    val htmlUrl: String           // GitHub 页面 URL
) {
    /**
     * 显示名称
     */
    val displayName: String
        get() = "$headphoneName ($source)"
}
