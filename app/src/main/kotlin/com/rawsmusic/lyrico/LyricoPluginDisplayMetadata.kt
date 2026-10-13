package com.rawsmusic.lyrico

/** Resolve presentation fields without rewriting the installed manifest or plugin identity. */
internal object LyricoPluginDisplayMetadata {
    fun resolve(manifest: LyricoPluginManifest, strings: LyricoPluginStrings): LyricoPluginManifest =
        manifest.copy(
            name = strings.manifestText(manifest.name, manifest.id),
            description = strings.manifestText(manifest.description, ""),
        )
}
