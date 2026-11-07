// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.net"
    override val requiresReferer = true

    // rapidvid guncel obfuscation: jwSetup.sources -> "file": av('<enc>'), av(o)=_(o)
    // _(e): ters cevir -> base64 decode -> her karakteri "K9L"[i%3] offset'i kadar geri kaydir -> base64 decode
    // Dogrulandi 2026-05-21 (host-side): cikti gecerli #EXTM3U master playlist.
    private fun rapidDecode(enc: String): String {
        val reversed = enc.reversed()
        val t        = String(Base64.decode(reversed, Base64.DEFAULT), Charsets.ISO_8859_1)
        val key      = "K9L"
        val sb       = StringBuilder(t.length)
        for (i in t.indices) {
            val r = key[i % 3]
            val n = t[i].code - (r.code % 5 + 1)
            sb.append(n.toChar())
        }
        return String(Base64.decode(sb.toString(), Base64.DEFAULT), Charsets.UTF_8)
    }

    // CloudStream VLC'ye sadece languageCode == "subs_auto_select" ayari olan TEK altyaziyi gonderir
    // (VlcPackage.kt); languageCode = SubtitleFile.langTag = fromCodeToLangTagIETF(lang). Bu yuzden
    // etiketi ISO koduna cevir: Turkce -> "tr", Ingilizce -> "en". Forced vb. etiketler oldugu gibi kalir.
    // VLC'nin "subtitles_location" extra'si SADECE yerel dosya yolu kabul ediyor: http URL'i
    // file:///https%3A/... yapip "cannot open file" veriyor (2026-11-07 TV logcat kaniti). Yerel
    // /storage/.../files/subs/x.vtt yolu ile VLC webvtt demux'u yukleyip altyaziyi gosterdi (kullanici teyidi).
    // Bu yuzden altyaziyi CloudStream'in kendi dis-depolama klasorune indir, yerel yolu ver; hata -> http URL.
    private suspend fun localizeSub(subUrl: String, lang: String, referer: String?): String {
        return runCatching {
            val dir = java.io.File("/storage/emulated/0/Android/data/com.lagradost.cloudstream3/files/subs")
            if (!dir.exists()) dir.mkdirs()
            val cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val body = app.get(subUrl, referer = referer).text
            check(body.contains("-->")) { "altyazi icerigi degil" }
            val f = java.io.File(dir, "rv_${subUrl.hashCode().toUInt()}_${lang.replace(Regex("[^A-Za-z0-9]"), "")}.vtt")
            f.writeText(body, Charsets.UTF_8)
            f.absolutePath
        }.getOrElse {
            Log.d("Kekik_${this.name}", "altyazi yerel kayit hata: ${it.message}")
            subUrl
        }
    }

    private fun subLangFromLabel(label: String): String {
        val l = label.lowercase()
        return when {
            "forced" in l          -> label
            "türk" in l || "turk" in l -> "tr"
            "ngiliz" in l || "english" in l -> "en"
            else -> label.ifBlank { "Altyazi" }
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url, referer=extRef).text

        val subUrls = mutableSetOf<String>()
        Regex("""captions","file":"([^"]+)","label":"([^"]+)"""").findAll(videoReq).forEach {
            val (subUrl, subLang) = it.destructured

            if (subUrl in subUrls) { return@forEach }
            subUrls.add(subUrl)

            subtitleCallback.invoke(
                SubtitleFile(
                    lang = subLang.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç"),
                    url  = fixUrl(subUrl.replace("\\", ""))
                )
            )
        }

        val decoded: String

        // 0a) GUNCEL FORMAT (2026-11): window._p8='<enc>' -> rapidDecode (ayni K9L zinciri) -> JSON config.
        // cm = HLS master, ct = [{kind:"captions", file:"https://subs.rapidvid.org/.../1.vtt", label:"Turkce Altyazi"}].
        // Dogrulandi 2026-11-07 (host-side): cm #EXTM3U, ct VTT 200 text/vtt (VLC/Dalvik UA ile de).
        var p8Video: String? = null
        Regex("""window\._p8\s*=\s*'([^']+)'""").find(videoReq)?.groupValues?.get(1)?.let { enc ->
            runCatching {
                val cfg = jacksonObjectMapper().readTree(rapidDecode(enc))
                p8Video = cfg.get("cm")?.asText()?.takeIf { it.startsWith("http") }
                cfg.get("ct")?.forEach { tr ->
                    val file = tr.get("file")?.asText() ?: return@forEach
                    if (tr.get("kind")?.asText() != "captions" || file in subUrls) return@forEach
                    subUrls.add(file)
                    val lang   = subLangFromLabel(tr.get("label")?.asText() ?: "")
                    val remote = fixUrl(file)
                    val local  = localizeSub(remote, lang, url)
                    // Sira onemli: VLC listedeki ILK eslesen dili alir -> once yerel yol (VLC icin),
                    // sonra uzak URL (dahili ExoPlayer SubtitleOrigin.URL'yi sadece OkHttp ile acar).
                    for (u in listOf(local, remote).distinct()) {
                        val sub = newSubtitleFile(lang, u)
                        Log.d("Kekik_${this.name}", "altyazi » lang=${sub.lang} langTag=${sub.langTag} url=${sub.url}")
                        subtitleCallback.invoke(sub)
                    }
                }
            }.onFailure { Log.d("Kekik_${this.name}", "_p8 decode hata: ${it.message}") }
        }

        // 0b) Ayni sayfada window.atomCodecTelemetry={...,"h264":"<m3u8>",...} (cm ile ayni master).
        // Dogrulandi 2026-11-07 (host-side): #EXTM3U master, 1920x800 avc1, segmentler saf MPEG-TS, referer gerekmiyor.
        val h264Url = Regex(""""h264"\s*:\s*"([^"]+)"""").find(videoReq)?.groupValues?.get(1)?.replace("\\/", "/")

        // 1) ONCEKI FORMAT: "file": av('<enc>')
        val avArg = Regex("""av\(['"]([^'"]+)['"]\)""").find(videoReq)?.groupValues?.get(1)
        if (p8Video != null) {
            decoded = p8Video!!
        } else if (h264Url != null) {
            decoded = h264Url
        } else if (avArg != null) {
            decoded = rapidDecode(avArg)
        } else {
            // 2) ESKI FORMAT fallback'leri (hex \x)
            var extractedValue = Regex("""file": "(.*)",""").find(videoReq)?.groupValues?.get(1)
            if (extractedValue != null) {
                val bytes = extractedValue.split("\\x").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
                decoded   = String(bytes, Charsets.UTF_8)
            } else {
                val evalJWSsetup = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""").find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
                @Suppress("LocalVariableName")
                val JWSsetup      = getAndUnpack(getAndUnpack(evalJWSsetup)).replace("\\\\", "\\")
                extractedValue  = Regex("""file":"(.*)","label""").find(JWSsetup)?.groupValues?.get(1)?.replace("\\\\x", "")

                val bytes = extractedValue?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                decoded   = bytes?.toString(Charsets.UTF_8) ?: throw ErrorLoadingException("File not found")
            }
        }
        Log.d("Kekik_${this.name}", "decoded » $decoded")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = decoded,
                type    = ExtractorLinkType.M3U8
            ) {
                this.referer = extRef
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
