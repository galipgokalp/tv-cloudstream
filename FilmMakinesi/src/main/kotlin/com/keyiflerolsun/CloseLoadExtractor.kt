// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.nicehttp.NiceResponse
import java.lang.Math.floorMod

// CS3IPlayer, video interceptor'i link.source adiyla SAGLAYICIDAN ister (getApiFromNameNull).
// Extractor adi (CloseLoad/Rapidrame) saglayici degil -> interceptor null -> Cronet -> 404.
// Bu yuzden link.source = saglayici adi; gorunen ad (name) extractor adi olarak kalir.
private const val PROVIDER_NAME = "FilmMakinesi"

open class CloseLoadExtractor : ExtractorApi() {
    override val name            = "CloseLoad"
    override val mainUrl         = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource = app.get(url, referer = extRef)
        val html    = iSource.text
        val obfuscatedScript = iSource.document.select("script").find { it.data().contains("eval(function(p,a,c,k,e") }?.data()?.trim()

        // Sayfadaki tum P.A.C.K.E.R bloklarini ac (rapid'de 2 adet var; kaynak degiskeni ilkinde).
        val unpacked = Regex("""eval\(function\(p,a,c,k,e,d\)""").findAll(html).mapNotNull { m ->
            runCatching { getAndUnpack(html.substring(m.range.first)) }.getOrNull()
        }.toList()
        val allJs = (listOf(html) + unpacked).joinToString("\n")
        val pageHost = runCatching { java.net.URI(url).host }.getOrNull() ?: java.net.URI(mainUrl).host

        val trackSubs = getTrackSubs(allJs, "https://$pageHost", subtitleCallback)
        if (!trackSubs) getSubs(iSource, obfuscatedScript, subtitleCallback)

        // ! 2026-11-07 host-side dogrulandi (closeload 10/10, rapid 5/5 -> #EXTM3U):
        // ! jwplayer setup `sources: [{file: <VAR>}]`; <VAR> = <fn>("...<SEP>...".split("<SEP>")).
        // ! <fn> her yuklemede ad/ayirici degistiren polimorfik sifre; algoritma ve sabitler sabit
        // ! (splitCipherDecode). Anahtar sayfa hostname'ine baglidir. ld+json contentUrl ARTIK SAHTE
        // ! (tum filmlerde ayni hls8.playmix.uno/...f9gx1M12BwC adresi, 404) -> en sona alindi.
        val cipherUrl = runCatching { findSplitCipherUrl(allJs, pageHost) }
            .onFailure { Log.d("Kekik_${this.name}", "splitCipher hata: ${it.message}") }.getOrNull()
        if (!cipherUrl.isNullOrBlank()) {
            Log.d("Kekik_${this.name}", "cipherUrl » $cipherUrl")
            callback.invoke(
                newExtractorLink(
                    source  = PROVIDER_NAME, // getApiFromNameNull(link.source) -> FilmMakinesi.getVideoInterceptor (Referer)
                    name    = this.name,
                    url     = cipherUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://$pageHost/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Eski dc_ zinciri (onceki format) - bulunamazsa sessizce gecer.
        if (obfuscatedScript != null && runCatching { getAndUnpack(obfuscatedScript).contains("dc_") }.getOrDefault(false)) {
            runCatching { getLinks(obfuscatedScript, callback) }.onSuccess { return }
        }

        // ! Son care: ld+json contentUrl (2026-11 itibariyla sahte/404; ileride gercek olursa diye kaldi).
        val ldJsonUrl = iSource.document.select("script[type=application/ld+json]")
            .firstNotNullOfOrNull { script ->
                runCatching {
                    val node = jacksonObjectMapper().readTree(script.data())
                    node.get("contentUrl")?.asText()
                }.getOrNull()
            }

        if (!ldJsonUrl.isNullOrBlank()) {
            Log.d("Kekik_${this.name}", "ldJsonUrl » $ldJsonUrl")
            callback.invoke(
                newExtractorLink(
                    source  = PROVIDER_NAME, // getApiFromNameNull(link.source) -> FilmMakinesi.getVideoInterceptor (Referer)
                    name    = this.name,
                    url     = ldJsonUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
        Log.d("Kekik_${this.name}", "Kaynak bulunamadi: $url")
    }

    // jwplayer `tracks: [{"file":"/srt/..vtt","label":"..","language":"tr","kind":"captions"}]` (rapid).
    // CloudStream VLC'ye sadece languageCode == subs_auto_select olan TEK altyaziyi yollar; bu yuzden
    // lang = ISO kodu ("tr"/"en") verilir (langTag = fromCodeToLangTagIETF(lang)).
    private suspend fun getTrackSubs(js: String, origin: String, subtitleCallback: (SubtitleFile) -> Unit): Boolean {
        val arr = Regex("""tracks\s*:\s*(\[[^\]]*\])""").findAll(js).map { it.groupValues[1] }
            .firstOrNull { it.contains("\"file\"") } ?: return false
        val nodes = runCatching { jacksonObjectMapper().readTree(arr) }.getOrNull() ?: return false
        var found = false
        val seen = mutableSetOf<String>()
        for (n in nodes) {
            if ((n.get("kind")?.asText() ?: "captions") != "captions") continue
            val file  = n.get("file")?.asText()?.replace("\\/", "/") ?: continue
            if (!seen.add(file)) continue
            val label = n.get("label")?.asText() ?: ""
            val code  = n.get("language")?.asText()?.lowercase()
            val lang  = when {
                code == "tr" || code == "en" -> code
                code == "forced" || label.equals("forced", true) -> "Forced"
                label.lowercase().let { "türk" in it || "turk" in it } -> "tr"
                label.lowercase().let { "ngiliz" in it || "english" in it } -> "en"
                else -> label.ifBlank { "Altyazi" }
            }
            val remote = if (file.startsWith("http")) file else origin + file
            val local  = localizeSub(remote, lang, "$origin/")
            // Sira onemli: oynaticilar ilk eslesen dili otomatik secer. Bu sitenin stream'leri Referer
            // ister ve CloudStream VLC'ye header aktaramadigi icin (OpenInAppAction) bu site sadece
            // DAHILI oynaticida calisir -> once uzak URL (dahili/OkHttp), sonra yerel yol (VLC yedek).
            for (u in listOf(remote, local).distinct()) {
                val sub = newSubtitleFile(lang, u)
                Log.d("Kekik_${this.name}", "altyazi » lang=${sub.lang} langTag=${sub.langTag} url=${sub.url}")
                subtitleCallback.invoke(sub)
            }
            found = true
        }
        return found
    }

    // VLC "subtitles_location" sadece yerel dosya yolu kabul ediyor (http -> file:///https%3A -> acilamaz;
    // 2026-11-07 TV logcat kaniti, yerel yol ile webvtt yuklendi + kullanici ekranda gordu).
    // Altyaziyi CloudStream dis-depolama klasorune indir, yerel yolu ver; hata -> uzak URL.
    private suspend fun localizeSub(subUrl: String, lang: String, referer: String?): String {
        return runCatching {
            val dir = java.io.File("/storage/emulated/0/Android/data/com.lagradost.cloudstream3/files/subs")
            if (!dir.exists()) dir.mkdirs()
            val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val body = app.get(subUrl, referer = referer).text
            check(body.contains("-->")) { "altyazi icerigi degil" }
            val f = java.io.File(dir, "fm_${subUrl.hashCode().toUInt()}_${lang.replace(Regex("[^A-Za-z0-9]"), "")}.vtt")
            f.writeText(body, Charsets.UTF_8)
            f.absolutePath
        }.getOrElse {
            Log.d("Kekik_${this.name}", "altyazi yerel kayit hata: ${it.message}")
            subUrl
        }
    }

    private fun findSplitCipherUrl(js: String, host: String): String? {
        val varName = Regex("""(?m)^\s*sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][\w$]*)""").find(js)?.groupValues?.get(1)
            ?: return null
        val m = Regex("""var\s+""" + Regex.escape(varName) + """\s*=\s*[\w$]+\(\s*"([^"]*)"\.split\(\s*"([^"]*)"\s*\)\s*\)""")
            .find(js) ?: return null
        val parts = m.groupValues[1].replace("\\/", "/").split(m.groupValues[2])
        return splitCipherDecode(parts, host).takeIf { it.startsWith("http") }
    }

    private fun jsAtob(s: String): String {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
    }

    // Sayfadaki JS'in birebir portu (2026-11-07, Python'da 15/15 canli dogrulandi):
    // ops/anahtar splice -> anahtar+hostname'den tohumlar -> ops'u tersten uygula ('7'=atob,'3'=ters,
    // harf=Caesar) -> LCG (x*97+41)%65519 ile Fisher-Yates geri karistirma -> akis XOR (d*5+step).
    fun splitCipherDecode(partsIn: List<String>, host: String): String {
        val parts = partsIn.toMutableList()
        val n     = parts.size - 2
        val ops   = parts.removeAt(8 + (n % 5))
        val key   = parts.removeAt(n % 7) + host
        var q     = parts.joinToString("")

        var hk = 0; var i9 = 0
        key.forEachIndexed { idx, ch ->
            val c = ch.code
            hk = (hk * 37 + c) % 241
            i9 = (i9 + ((c shl 1) xor idx)) and 255
        }
        val x0 = (hk * 3 + i9) % 256
        val step = (i9 % 11) + 5
        var u = ((i9 * 251 + hk) % 65519) + 1

        for (ch in ops.reversed()) {
            q = when (ch) {
                '7'  -> jsAtob(q)
                '3'  -> q.reversed()
                else -> {
                    val sh = (26 - ((ch.code - 96) % 26)) % 26
                    buildString(q.length) {
                        for (c in q) {
                            when (c) {
                                in 'A'..'Z' -> append(((c.code - 65 + sh) % 26 + 65).toChar())
                                in 'a'..'z' -> append(((c.code - 97 + sh) % 26 + 97).toChar())
                                else -> append(c)
                            }
                        }
                    }
                }
            }
        }

        val len = q.length
        val t = IntArray(len)
        for (v in len - 1 downTo 1) { u = (u * 97 + 41) % 65519; t[v] = u % (v + 1) }
        val arr = q.toCharArray()
        for (v in 1 until len) { val j = t[v]; val tmp = arr[v]; arr[v] = arr[j]; arr[j] = tmp }

        var d = x0
        val out = StringBuilder(len)
        for (ch in arr) {
            val c = ch.code
            d = (d * 5 + step) % 256
            out.append((c xor d).toChar())
            d = (d + c) % 256
        }
        return out.toString()
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
                source  = PROVIDER_NAME, // getApiFromNameNull(link.source) -> FilmMakinesi.getVideoInterceptor (Referer)
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
