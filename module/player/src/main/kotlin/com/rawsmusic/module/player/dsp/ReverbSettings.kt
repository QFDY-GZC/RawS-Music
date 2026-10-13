package com.rawsmusic.module.player.dsp

enum class ReverbSpace { SMALL_ROOM, STUDIO, CHAMBER, HALL, CATHEDRAL }
data class ReverbSettings(
    val space: ReverbSpace=ReverbSpace.STUDIO,
    val mix: Float=.15f, val roomSize: Float=.35f, val decaySeconds: Float=1.2f,
    val preDelayMs: Float=12f, val damping: Float=.45f, val diffusion: Float=.7f,
    val width: Float=1f, val lowCutHz: Float=100f, val highCutHz: Float=12000f,
    val earlyLate: Float=.65f, val gainDb: Float=0f,
) {
    fun withSpace(value: ReverbSpace): ReverbSettings {
        if(value==space)return this
        val acoustics=ReverbPresets.builtIn.first { it.settings.space==value }.settings
        return acoustics.copy(mix=mix,gainDb=gainDb).sanitized()
    }
    fun sanitized(): ReverbSettings {
        fun safe(v: Float,range: ClosedFloatingPointRange<Float>,fallback: Float)=
            if(v.isFinite())v.coerceIn(range) else fallback
        return copy(mix=safe(mix,0f..1f,.15f),roomSize=safe(roomSize,0f..1f,.35f),
            decaySeconds=safe(decaySeconds,.15f..12f,1.2f),preDelayMs=safe(preDelayMs,0f..200f,12f),
            damping=safe(damping,0f..1f,.45f),diffusion=safe(diffusion,0f..1f,.7f),
            width=safe(width,0f..1.5f,1f),lowCutHz=safe(lowCutHz,20f..1000f,100f),
            highCutHz=safe(highCutHz,1000f..20000f,12000f),earlyLate=safe(earlyLate,0f..1f,.65f),
            gainDb=safe(gainDb,-12f..6f,0f))
    }
    fun nativeValues() = floatArrayOf(space.ordinal.toFloat(),mix,roomSize,decaySeconds,preDelayMs,
        damping,diffusion,width,lowCutHz,highCutHz,earlyLate,gainDb)
    fun encode()="v1|"+sanitized().nativeValues().joinToString(",")
    companion object {
        fun decode(encoded: String): ReverbSettings {
            if(!encoded.startsWith("v1|"))return ReverbSettings()
            val values=encoded.removePrefix("v1|").split(',')
            if(values.size!=12)return ReverbSettings()
            val v=values.map { it.toFloatOrNull()?:return ReverbSettings() }
            val space=ReverbSpace.entries.getOrNull(v[0].toInt())?:ReverbSpace.STUDIO
            return ReverbSettings(space,v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11]).sanitized()
        }
    }
}
data class ReverbPreset(val name: String,val settings: ReverbSettings)
fun storeReverbPreset(existing: List<ReverbPreset>,name: String,settings: ReverbSettings): List<ReverbPreset>? {
    val clean=name.trim().take(40)
    if(clean.isEmpty())return null
    val others=existing.filterNot { it.name==clean }
    if(others.size>=20)return null
    return others+ReverbPreset(clean,settings.sanitized())
}
object ReverbPresets {
    val builtIn=listOf(
        ReverbPreset("small_room",ReverbSettings(space=ReverbSpace.SMALL_ROOM,mix=.12f,roomSize=.18f,
            decaySeconds=.55f,preDelayMs=5f,damping=.55f,earlyLate=.45f)),
        ReverbPreset("studio",ReverbSettings()),
        ReverbPreset("chamber",ReverbSettings(space=ReverbSpace.CHAMBER,mix=.17f,roomSize=.5f,
            decaySeconds=1.8f,preDelayMs=18f,diffusion=.8f,earlyLate=.6f)),
        ReverbPreset("hall",ReverbSettings(space=ReverbSpace.HALL,mix=.2f,roomSize=.75f,
            decaySeconds=2.8f,preDelayMs=28f,damping=.55f,diffusion=.85f,width=1.15f,earlyLate=.75f)),
        ReverbPreset("cathedral",ReverbSettings(space=ReverbSpace.CATHEDRAL,mix=.23f,roomSize=1f,
            decaySeconds=5.5f,preDelayMs=45f,damping=.65f,diffusion=.95f,width=1.3f,
            lowCutHz=160f,highCutHz=8000f,earlyLate=.85f)),
        ReverbPreset("vocal",ReverbSettings(mix=.18f,roomSize=.3f,decaySeconds=1.4f,
            preDelayMs=35f,damping=.5f,diffusion=.85f,lowCutHz=180f,highCutHz=10000f)),
        ReverbPreset("warm_vocal",ReverbSettings(mix=.2f,roomSize=.4f,decaySeconds=1.7f,
            preDelayMs=40f,damping=.7f,diffusion=.9f,lowCutHz=200f,highCutHz=6500f,earlyLate=.75f)),
        ReverbPreset("acoustic",ReverbSettings(space=ReverbSpace.CHAMBER,mix=.16f,roomSize=.38f,
            decaySeconds=1.3f,preDelayMs=16f,damping=.4f,diffusion=.75f,lowCutHz=140f,earlyLate=.55f)),
        ReverbPreset("piano",ReverbSettings(space=ReverbSpace.HALL,mix=.22f,roomSize=.65f,
            decaySeconds=2.5f,preDelayMs=22f,damping=.45f,diffusion=.9f,width=1.15f,lowCutHz=130f)),
        ReverbPreset("jazz_club",ReverbSettings(space=ReverbSpace.SMALL_ROOM,mix=.17f,roomSize=.3f,
            decaySeconds=.9f,preDelayMs=14f,damping=.6f,lowCutHz=150f,highCutHz=8500f,earlyLate=.5f)),
        ReverbPreset("live_stage",ReverbSettings(space=ReverbSpace.CHAMBER,mix=.23f,roomSize=.6f,
            decaySeconds=1.9f,preDelayMs=32f,damping=.4f,diffusion=.8f,width=1.2f,earlyLate=.55f)),
        ReverbPreset("auditorium",ReverbSettings(space=ReverbSpace.HALL,mix=.25f,roomSize=.85f,
            decaySeconds=3.5f,preDelayMs=38f,damping=.55f,diffusion=.9f,width=1.25f,earlyLate=.8f)),
        ReverbPreset("arena",ReverbSettings(space=ReverbSpace.HALL,mix=.27f,roomSize=1f,
            decaySeconds=4.5f,preDelayMs=65f,damping=.5f,diffusion=.65f,width=1.35f,
            lowCutHz=200f,highCutHz=10000f,earlyLate=.8f)),
        ReverbPreset("ambient",ReverbSettings(space=ReverbSpace.CATHEDRAL,mix=.32f,roomSize=.95f,
            decaySeconds=7.5f,preDelayMs=55f,damping=.75f,diffusion=1f,width=1.4f,
            lowCutHz=250f,highCutHz=6000f,earlyLate=.95f,gainDb=-2f)),
        ReverbPreset("cinematic",ReverbSettings(space=ReverbSpace.CATHEDRAL,mix=.28f,roomSize=.9f,
            decaySeconds=4.8f,preDelayMs=80f,damping=.6f,diffusion=.95f,width=1.4f,
            lowCutHz=180f,highCutHz=9000f,earlyLate=.9f,gainDb=-1f)),
    )
}
