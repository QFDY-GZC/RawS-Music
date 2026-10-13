package com.rawsmusic.module.data.prefs

import com.rawsmusic.module.player.dsp.*
import org.junit.Assert.*
import org.junit.Test

class BackupPeqCompatibilityTest {
    @Test fun oldBackupExportsAndMigratesPeqWithoutDroppingItsSettings() {
        val raw = """[{"a":"i","b":1200,"c":-3,"d":0.7,"e":true}]"""
        val entry = com.google.gson.JsonObject().apply {
            addProperty("key", "peq_filters_json"); addProperty("type", "STRING"); addProperty("value", raw)
        }
        val archive = BackupArchiveCodec.decode("""{"version":2,"settings":{"mmkv":[$entry],"sharedPreferences":{}}}""".toByteArray())
        val filters = PeqFilterJsonCodec.decode((archive.preferences.single().value as PreferenceValue.StringValue).value)
        assertEquals(FilterType.LOW_SHELF, filters.single().type)
        assertEquals(-3f, filters.single().gainDB)
        assertTrue((archive.preferences.single().value as PreferenceValue.StringValue).value.contains("\"frequency\""))
    }
    @Test fun wireNamesAreStableAndDoNotDependOnReflectionFieldNames() {
        val json = PeqFilterJsonCodec.encode(listOf(PEQFilter(FilterType.PEAK_ANALOG, 1200f, -3f, 1f, true)))
        assertEquals("""[{"type":"PEAK_ANALOG","frequency":1200.0,"gainDB":-3.0,"Q":1.0,"enabled":true}]""", json)
        assertEquals(FilterType.PEAK_ANALOG, PeqFilterJsonCodec.decode(json).single().type)
    }
    @Test fun verifiedHistoricalReleaseSchemaRetainsEveryFilterParameter() {
        val filters = PeqFilterJsonCodec.decode("""[{"a":"i","b":1200,"c":-3,"d":0.7,"e":true}]""")
        assertEquals(listOf(PEQFilter(FilterType.LOW_SHELF, 1200f, -3f, 0.7f, true)), filters)
    }
    @Test fun ambiguousOrIncompleteObfuscationIsNeverGuessed() {
        for (json in listOf("""[{"a":"unknown","b":1200,"c":0,"d":1,"e":true}]""", """[{"a":"h","b":1200}]"""))
            assertTrue(runCatching { PeqFilterJsonCodec.decode(json) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun duplicateFilterFieldsAreRejected() {
        assertTrue(runCatching { PeqFilterJsonCodec.decode("""[{"type":"PEAK","frequency":1200,"frequency":1000,"gainDB":0,"Q":1,"enabled":true}]""") }.exceptionOrNull() is IllegalArgumentException)
    }
}
