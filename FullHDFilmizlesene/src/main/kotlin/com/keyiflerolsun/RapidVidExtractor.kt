// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

        // 1) GUNCEL FORMAT: "file": av('<enc>')
        val avArg = Regex("""av\(['"]([^'"]+)['"]\)""").find(videoReq)?.groupValues?.get(1)
        if (avArg != null) {
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
