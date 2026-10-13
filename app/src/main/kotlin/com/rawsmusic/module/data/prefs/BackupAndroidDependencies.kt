package com.rawsmusic.module.data.prefs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.rawsmusic.lyrico.LyricoPluginStore
import com.rawsmusic.ai.instrument.AiInstrumentPackStore
import com.rawsmusic.separation.AiSeparationJson
import java.io.File

/** Read-only checks: no grants, downloads, model-store migration or media scanning. */
internal class BackupAndroidDependencies(private val context: Context) : BackupDependencyAccess {
    private val preferences = AndroidBackupPreferenceAccess(context)
    private val plugins by lazy { LyricoPluginStore.get(context).listInstalled().map { it.manifest.id }.toSet() }
    private val instruments by lazy { AiInstrumentPackStore.get(context).installedPacks().map { "${it.manifest.id}@${it.manifest.version}" }.toSet() }
    private val models by lazy {
        buildSet {
            val root = File(context.filesDir, "ai_separation")
            listOf("models" to false, "lyric_alignment/models" to true).forEach { (relative, lyric) ->
                File(root, relative).walkTopDown().maxDepth(3).filter { it.isFile && it.name == "installed.json" }.forEach { manifest ->
                    runCatching {
                        val text = manifest.inputStream().use { BackupBoundedReader.read(it, 1024 * 1024) }.toString(Charsets.UTF_8)
                        if (lyric) {
                            val catalog = AiSeparationJson.parseLyricAlignmentInstalled(text).first
                            val dir = manifest.parentFile
                            if (File(dir, catalog.modelFile).let { it.isFile && it.canRead() && it.length() == catalog.modelSizeBytes } &&
                                File(dir, catalog.vocabularyFile).let { it.isFile && it.canRead() && it.length() == catalog.vocabularySizeBytes }) add(catalog.id to catalog.version)
                        } else {
                            val catalog = AiSeparationJson.parseInstalled(text).first
                            if (File(manifest.parentFile, catalog.modelFile).let { it.isFile && it.canRead() && it.length() == catalog.modelSizeBytes }) add(catalog.id to catalog.version)
                        }
                    }
                }
            }
        }
    }
    override fun readable(reference: String): Boolean = runCatching {
        val uri = Uri.parse(reference)
        when (uri.scheme) {
            "content" -> {
                if (DocumentsContract.isTreeUri(uri)) {
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
                } else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
            }
            null, "file" -> File(if (uri.scheme == "file") uri.path.orEmpty() else reference).let { it.exists() && it.canRead() }
            else -> false
        }
    }.getOrDefault(false)
    override fun pluginInstalled(id: String) = id in plugins
    override fun modelInstalled(id: String, version: String) = id to version in models
    override fun instrumentInstalled(key: String) = key in instruments
    override fun current(address: PreferenceAddress): PreferenceValue? = BackupPreferenceRegistry.classify(address)?.let { preferences.read(address, it.type) }
}
