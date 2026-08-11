// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.nicehttp.NiceResponse
import java.lang.Math.floorMod

open class CloseLoadExtractor : ExtractorApi() {
    override val name            = "CloseLoad"
    override val mainUrl         = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource = app.get(url, referer = extRef)
        val obfuscatedScript = iSource.document.select("script").find { it.data().contains("eval(function(p,a,c,k,e") }?.data()?.trim()
        getSubs(iSource, obfuscatedScript,subtitleCallback)
        getLinks(obfuscatedScript, callback)
    }

    private fun getSubs(
        iSource: NiceResponse,
        obfuscatedScript: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        iSource.document.select("track").forEach {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = it.attr("label"),
                    url = mainUrl + it.attr("src")
                )
            )
        }
        val track = obfuscatedScript?.substringAfter("tracks: ")?.substringBefore("]") + "]"
        if (track.startsWith("[") && track.endsWith("]")) {
            Log.d("Kekik_${this.name}", "track -> $track")
            val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val tracks: List<SubSource> = objectMapper.readValue(track)
            Log.d("Kekik_${this.name}", "tracks -> $tracks")
            tracks.forEach { it ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = it.label.toString(),
                        url = mainUrl + it.file.toString()
                    )
                )
            }
        }
    }

    private suspend fun getLinks(obfuscatedScript: String?, callback: (ExtractorLink) -> Unit) {
        if (obfuscatedScript == null) {
            Log.d("Kekik_${this.name}", "obfuscatedScript bulunamadi, atlaniyor")
            return
        }
        val rawScript         = getAndUnpack(obfuscatedScript)
        val helloVarmi = rawScript.contains("dc_hello")
        var dcRegex = Regex("dc_hello\\(\"([^\"]*)\"\\)", setOf(RegexOption.IGNORE_CASE))
        if (!helloVarmi) {
            dcRegex = Regex("""dc_\w+\(\[(.*?)\]\)""", RegexOption.DOT_MATCHES_ALL)
        }
        val match = dcRegex.find(rawScript)
        val groupValues = match!!.groupValues[1]

        val lastUrl = if (helloVarmi) {
            Log.d("Kekik_${this.name}", "groupValues » $groupValues")
            val dcUrl = dcHello(groupValues).substringAfter("http")
            Log.d("Kekik_${this.name}", "dcUrl » $dcUrl")
            dcUrl
        } else{
            val parts = groupValues.split(",")
                .map { it.trim().removeSurrounding("\"") }
            Log.d("Kekik_${this.name}", "parts » $parts")
            val dcUrl = dcNew(parts)
            Log.d("Kekik_${this.name}", "dcUrl » $dcUrl")
            dcUrl
        }
        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = lastUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    fun dcHello(base64Input: String): String {
        val decodedOnce = base64Decode(base64Input)
        val reversedString = decodedOnce.reversed()
        val decodedTwice = base64Decode(reversedString)
        val link    = if (decodedTwice.contains("+")) {
            decodedTwice.substringAfterLast("+")
        } else if (decodedTwice.contains(" ")) {
            decodedTwice.substringAfterLast(" ")
        } else if (decodedTwice.contains("|")){
            decodedTwice.substringAfterLast("|")
        } else {
            decodedTwice
        }
        return link
    }

    // ! 2026-08-12 host-side dogrulandi: eski (base64x1 -> reverse -> rot13 -> zaman-degisken-mod cikarma)
    // ! zinciri artik GECERSIZ. Yeni zincir: birlestir -> ters cevir -> base64 CIFT coz -> XOR akis sifresi
    // ! (acc=86 baslangic, her byte'ta acc=(acc+9)%256, plain=byte xor acc, acc=(acc+byte)%256).
    // ! Canli veri uzerinde JS'de birebir calistirilip "https://...m3u8" ciktisi dogrulandi.
    fun dcNew(parts: List<String>): String {
        val joined = parts.joinToString("")
        val reversed = joined.reversed()
        val decodedOnce = String(base64DecodeArray(reversed), Charsets.ISO_8859_1)
        val decodedTwice = String(base64DecodeArray(decodedOnce), Charsets.ISO_8859_1)

        var acc = 86
        val unmix = StringBuilder()
        for (ch in decodedTwice) {
            val b = ch.code and 0xFF
            acc = (acc + 9) % 256
            val plain = b xor acc
            acc = (acc + b) % 256
            unmix.append(plain.toChar())
        }
        return unmix.toString()
    }
}

private data class SubSource(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("kind") val kind: String? = null
)
