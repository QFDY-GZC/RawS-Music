package com.rawsmusic.module.player

import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import com.rawsmusic.module.scanner.webdav.WebDavPlaybackCredentialResolver

/** Unifies HTTP header ownership for plugin-resolved streams and direct remote/WebDAV sources. */
internal object RemotePlaybackOptionsResolver {
    data class Options(
        val headers: Map<String, String>,
        val userAgent: String?,
        val owner: String,
        val generation: Long? = null,
        val resolvedUrl: String? = null,
    )

    fun lookup(vararg paths: String): Options? {
        paths.forEach { path ->
            if (path.isBlank()) return@forEach
            MusicSourceResolvedStreamRegistry.lookup(path)?.let { entry ->
                return Options(
                    headers = entry.source.headers,
                    userAgent = entry.source.userAgent,
                    owner = "music-source",
                    generation = entry.generation,
                    resolvedUrl = path,
                )
            }
            RemoteHttpStreamRegistry.lookup(path)?.let { entry ->
                return Options(
                    headers = entry.resolveHeaders(path),
                    userAgent = entry.userAgent,
                    owner = entry.owner,
                    resolvedUrl = entry.resolveUrl(path),
                )
            }
            WebDavPlaybackCredentialResolver.resolve(path)?.let { request ->
                val provider: (String) -> Map<String, String> = { target ->
                    WebDavPlaybackCredentialResolver.resolve(target)?.headers.orEmpty()
                }
                RemoteHttpStreamRegistry.register(
                    url = path,
                    headers = request.headers,
                    userAgent = request.userAgent,
                    owner = "webdav-persisted",
                    headerProvider = provider,
                )
                if (request.url != path) {
                    RemoteHttpStreamRegistry.register(
                        url = request.url,
                        headers = request.headers,
                        userAgent = request.userAgent,
                        owner = "webdav-persisted",
                        headerProvider = provider,
                    )
                }
                return Options(
                    headers = request.headers,
                    userAgent = request.userAgent,
                    owner = "webdav-persisted",
                    resolvedUrl = request.url,
                )
            }
        }
        return null
    }
}
