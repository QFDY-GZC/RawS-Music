package com.rawsmusic.core.ui.scene

enum class NavScene(
    val id: Int,
    val tag: String,
    val label: String = tag,
    val icon: String = ""
) {
    HOME(0, "home", "主页", "home"),
    SONGS(1, "songs", "歌曲列表", "music_note"),
    FOLDERS(2, "folders", "文件夹", "folder"),
    FOLDER_HIERARCHY(3, "folder_hierarchy", "文件夹层次", "folder_open"),
    ALBUMS(4, "albums", "专辑", "album"),
    ALBUM_DETAIL(5, "album_detail", "专辑详情", "album"),
    ARTISTS(6, "artists", "艺术家", "person"),
    ARTIST_DETAIL(7, "artist_detail", "艺术家详情", "person"),
    PLAYLISTS(8, "playlists", "歌单", "queue_music"),
    PLAYLIST_DETAIL(9, "playlist_detail", "歌单详情", "queue_music"),
    QUEUE(10, "queue", "播放队列", "playlist_play"),
    RECENTLY_ADDED(11, "recently_added", "最近添加", "schedule"),
    WEBDAV(12, "webdav", "WebDAV", "cloud"),
    ABOUT(13, "about", "关于", "info"),
    SONG_STATS(14, "song_stats", "歌曲统计", "bar_chart"),
    LOG_VIEWER(15, "log_viewer", "日志分析", "bug_report"),
    ANALYTICS(16, "analytics", "听歌统计", "analytics"),
    PLAYLIST_LIST(17, "playlist_list", "歌单列表", "queue_music"),
    PLAYLIST_DETAIL_PAGE(18, "playlist_detail_page", "歌单详情", "queue_music"),
    SEARCH(19, "search", "搜索", "search"),
    SETTINGS(20, "settings", "设置", "settings"),
    APPEARANCE(21, "appearance", "外观", "palette"),
    AUDIO_EFFECTS(22, "audio_effects", "音效", "equalizer"),
    AUDIO_SETTINGS(23, "audio_settings", "音频设置", "tune"),
    ALBUM_ART_SETTINGS(24, "album_art_settings", "专辑封面", "image"),
    BASS_TREBLE_BOOST(25, "bass_treble_boost", "低音增强", "volume_up"),
    COMPRESSOR(26, "compressor", "压缩器", "compress"),
    GLOBAL_FONT_SETTINGS(27, "global_font_settings", "全局字体", "font_download"),
    LYRIC_FONT_SETTINGS(28, "lyric_font_settings", "歌词字体", "lyrics"),
    LYRIC_MANAGEMENT(29, "lyric_management", "歌词管理", "description"),
    PANORAMIC_360(30, "panoramic_360", "全景360", "panorama"),
    PEQ(31, "peq", "参量均衡器", "graphic_eq"),
    PLAYER_INTERFACE(32, "player_interface", "播放器界面", "phone_android"),
    SPATIAL_SOUND(33, "spatial_sound", "空间音效", "spatial_audio"),
    STATUS_BAR_LYRIC(34, "status_bar_lyric", "状态栏歌词", "lyrics"),
    SURROUND_360(35, "surround_360", "环绕360", "surround_sound"),
    USB_DAC_SETTINGS(36, "usb_dac_settings", "USB DAC", "usb"),
    WEBDAV_BACKUP(37, "webdav_backup", "WebDAV 备份", "cloud_upload"),
    DAILY_20(38, "daily_20", "每日20首", "calendar_today"),
    GENRE(39, "genre", "流派", "local_offer"),
    YEAR(40, "year", "年份", "date_range"),
    COMPOSER(41, "composer", "作曲家", "edit_note"),
    GENRE_DETAIL(42, "genre_detail", "流派详情", "local_offer"),
    YEAR_DETAIL(43, "year_detail", "年份详情", "date_range"),
    COMPOSER_DETAIL(44, "composer_detail", "作曲家详情", "edit_note"),
    SCAN_SETTINGS(45, "scan_settings", "扫描设置", "scanner"),
    TRANSITION_SETTINGS(46, "transition_settings", "淡入淡出", "transition");

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): NavScene? = idMap[id]

        val topLevel = setOf(SONGS, FOLDERS, ALBUMS, ARTISTS, PLAYLISTS, QUEUE, RECENTLY_ADDED, WEBDAV)

        val homeEntries = listOf(SONGS, FOLDERS, ALBUMS, ARTISTS, PLAYLISTS, QUEUE, RECENTLY_ADDED, WEBDAV, SETTINGS)
    }

    fun isDetail(): Boolean = this in setOf(
        ALBUM_DETAIL,
        ARTIST_DETAIL,
        PLAYLIST_DETAIL,
        FOLDER_HIERARCHY,
        GENRE_DETAIL,
        YEAR_DETAIL,
        COMPOSER_DETAIL
    )
}
