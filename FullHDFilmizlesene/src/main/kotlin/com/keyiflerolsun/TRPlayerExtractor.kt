// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class TRPlayer : ExtractorApi() {
    override val name            = "TRPlayer"
    override val mainUrl         = "https://watch.trplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url, referer = extRef).text

        // watch sayfasindaki "var video = {...}" JSON nesnesinden alanlari cek
        val id     = Regex(""""id":"([^"]+)"""").find(videoReq)?.groupValues?.get(1)
        val uid    = Regex(""""uid":"([^"]+)"""").find(videoReq)?.groupValues?.get(1)
        val md5    = Regex(""""md5":"([^"]+)"""").find(videoReq)?.groupValues?.get(1)
        val status = Regex(""""status":"([^"]+)"""").find(videoReq)?.groupValues?.get(1) ?: "1"

        if (uid == null || md5 == null || id == null) throw ErrorLoadingException("File not found")

        // Stream: /m3u8/<uid>/<md5>/master.txt?s=1&id=<id>&cache=<status> -> #EXTM3U master playlist
        // Dogrulandi 2026-05-21 (host-side): code 200, #EXTM3U variant playlist.
        val m3uLink = "${mainUrl}/m3u8/${uid}/${md5}/master.txt?s=1&id=${id}&cache=${status}"
        Log.d("Kekik_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = m3uLink,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
