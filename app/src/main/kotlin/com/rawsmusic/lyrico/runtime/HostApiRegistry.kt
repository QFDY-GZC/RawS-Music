package com.rawsmusic.lyrico.runtime

object HostApiRegistry {
    /** Plugin callback protocol. API v5 extends structured TTML lyric semantics. */
    const val PLUGIN_API_VERSION = 5
    /** Platform bridge API. Host API v4 adds plugin-scoped i18n. */
    const val HOST_API_VERSION = 4
    val SUPPORTED_PLUGIN_API_VERSIONS = 1..PLUGIN_API_VERSION

    val SUPPORTED_HOST_APIS = setOf(
        "app.info",
        "app.userAgent",
        "runtime.info",
        "i18n.getLocale",
        "i18n.t",
        "cache.get",
        "cache.set",
        "cache.remove",
        "cache.clear",
        "crypto.md5",
        "crypto.aesEcbPkcs5EncryptBase64",
        "crypto.aesEcbPkcs5EncryptHex",
        "crypto.aesEcbPkcs5DecryptBase64ToText",
        "base64.encodeText",
        "base64.decodeText",
        "base64.dropBytes",
        "base64.decodeBytes",
        "base64.encodeBytes",
        "base64.encodeUrlText",
        "base64.decodeUrlText",
        "base64.encodeUrlBytes",
        "base64.decodeUrlBytes",
        "base64.toUrl",
        "base64.fromUrl",
        "bytes.xor",
        "bytes.xorBase64",
        "compression.inflateBytesToText",
        "compression.inflateBase64ToText",
        "http.getText",
        "http.postText",
        "http.postBytes",
        "http.get",
        "http.post",
        "http.getBytes",
        "http.postBytesResponse",
        "log.debug",
        "log.warn",
        "log.error",
        "xml.getRootAttributes",
        "xml.findElements",
        "xml.replaceChildrenByAttr",
        "xml.removeElements"
    )
}
