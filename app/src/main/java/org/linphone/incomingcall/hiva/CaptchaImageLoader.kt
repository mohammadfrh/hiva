package org.linphone.incomingcall.hiva

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import com.caverock.androidsvg.SVG
import okhttp3.OkHttpClient
import okhttp3.Request
import org.linphone.incomingcall.BuildConfig
import java.io.File

object CaptchaImageLoader {

    private const val TAG = "HIVA_CAPTCHA"

    /** Tries API-backed captcha URLs until a non-HTML image body is decoded. */
    fun loadBitmapForPath(client: OkHttpClient, imagePath: String): Bitmap? {
        val urls = HivaGoldClient.captchaImageUrlCandidates(imagePath)
        Log.i(TAG, "load candidates count=${urls.size} paths=$urls")
        for (url in urls) {
            val bmp = fetchAndDecode(client, url)
            if (bmp != null) {
                Log.i(TAG, "load success url=$url")
                return bmp
            }
            Log.w(TAG, "load try failed url=$url")
        }
        Log.e(TAG, "load failed all candidates for imagePath=$imagePath")
        return null
    }

    fun loadBitmap(client: OkHttpClient, url: String): Bitmap? {
        if (url.contains("/captcha/image/") && !url.contains("/api/captcha/image/")) {
            val key = url.substringAfter("/captcha/image/").trim('/')
            if (key.isNotBlank()) {
                return loadBitmapForPath(client, "/captcha/image/$key/")
            }
        }
        return fetchAndDecode(client, url)
    }

    private fun fetchAndDecode(client: OkHttpClient, url: String): Bitmap? {
        Log.i(TAG, "fetch start url=$url")
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "image/png,image/jpeg,image/*;q=0.9,*/*;q=0.5")
                .get()
                .build()
        ).execute()
        val code = response.code
        val contentType = response.header("Content-Type").orEmpty()
        val contentLength = response.header("Content-Length").orEmpty()
        if (!response.isSuccessful) {
            Log.w(TAG, "fetch failed code=$code type=$contentType url=$url")
            return null
        }
        val bytes = response.body?.bytes() ?: run {
            Log.w(TAG, "fetch empty body code=$code type=$contentType url=$url")
            return null
        }
        if (bytes.isEmpty()) {
            Log.w(TAG, "fetch zero bytes code=$code type=$contentType url=$url")
            return null
        }
        Log.i(
            TAG,
            "fetch ok code=$code type=$contentType lengthHdr=$contentLength bytes=${bytes.size} sniff=${sniffFormat(bytes)} url=$url"
        )
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "headHex=${hexPreview(bytes, 24)} headText=${textPreview(bytes, 80)}")
        }
        if (isHtml(bytes)) {
            Log.w(TAG, "fetch got HTML (SPA fallback), skip decode url=$url")
            return null
        }
        return decodeBytes(bytes)
    }

    fun decodeBytes(bytes: ByteArray): Bitmap? {
        val sniff = sniffFormat(bytes)
        Log.i(TAG, "decode start bytes=${bytes.size} sniff=$sniff sdk=${Build.VERSION.SDK_INT}")

        if (isHtml(bytes)) {
            Log.w(TAG, "decode abort: body looks like HTML")
            return null
        }

        if (isSvg(bytes)) {
            val bmp = decodeSvg(bytes, "svg-detect")
            if (bmp != null) return bmp
        }

        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onSuccess { bmp ->
            if (bmp != null) {
                Log.i(TAG, "decode ok via=BitmapFactory ${bmp.width}x${bmp.height}")
                return bmp
            }
        }.onFailure { e ->
            Log.w(TAG, "decode BitmapFactory threw: ${e.message}")
        }
        Log.w(TAG, "decode BitmapFactory returned null (Skia may log 'unimplemented')")

        if (looksLikeText(bytes)) {
            Log.i(TAG, "decode retry as SVG (text-like body)")
            decodeSvg(bytes, "text-fallback")?.let { return it }
        }

        val viaDecoder = decodeWithImageDecoder(bytes)
        if (viaDecoder != null) {
            Log.i(TAG, "decode ok via=ImageDecoder ${viaDecoder.width}x${viaDecoder.height}")
            return viaDecoder
        }

        Log.e(
            TAG,
            "decode failed sniff=$sniff bytes=${bytes.size} sdk=${Build.VERSION.SDK_INT} headHex=${hexPreview(bytes, 16)}"
        )
        return null
    }

    private fun decodeWithImageDecoder(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "ImageDecoder skipped: API<28")
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.d(TAG, "ImageDecoder path=createSource(bytes)")
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes))
            } else {
                Log.d(TAG, "ImageDecoder path=tempFile")
                val tmp = File.createTempFile("hiva_captcha_", ".bin")
                try {
                    tmp.writeBytes(bytes)
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp))
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ImageDecoder failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun sniffFormat(bytes: ByteArray): String {
        if (bytes.size < 4) return "too-short"
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png"
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpeg"
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) return "webp?"
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) return "gif"
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 64)), Charsets.UTF_8).trimStart()
        if (head.contains("<svg", ignoreCase = true)) return "svg"
        if (head.startsWith("<?xml", ignoreCase = true)) return "xml"
        if (head.startsWith("<!doctype", ignoreCase = true) || head.startsWith("<html", ignoreCase = true)) {
            return "html"
        }
        if (looksLikeText(bytes)) return "text"
        return "unknown"
    }

    private fun hexPreview(bytes: ByteArray, n: Int): String {
        val len = minOf(bytes.size, n)
        return buildString {
            for (i in 0 until len) {
                if (i > 0) append(' ')
                append("%02x".format(bytes[i]))
            }
        }
    }

    private fun textPreview(bytes: ByteArray, maxChars: Int): String {
        val raw = String(bytes.copyOfRange(0, minOf(bytes.size, maxChars)), Charsets.UTF_8)
            .replace('\n', ' ')
            .replace('\r', ' ')
        return raw.take(maxChars)
    }

    private fun isHtml(bytes: ByteArray): Boolean {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 128)), Charsets.UTF_8)
            .trimStart()
            .lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html")
    }

    private fun isSvg(bytes: ByteArray): Boolean {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.UTF_8).trimStart()
        return head.contains("<svg", ignoreCase = true) ||
            (head.startsWith("<?xml", ignoreCase = true) && head.contains("svg", ignoreCase = true))
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 256))
        var printable = 0
        for (b in sample) {
            val c = b.toInt() and 0xff
            if (c == 9 || c == 10 || c == 13 || c in 32..126) printable++
        }
        return sample.isNotEmpty() && printable * 100 / sample.size >= 70
    }

    private fun decodeSvg(bytes: ByteArray, path: String): Bitmap? {
        return try {
            val svg = SVG.getFromString(String(bytes, Charsets.UTF_8))
            val w = svg.documentWidth.takeIf { it > 0f } ?: 220f
            val h = svg.documentHeight.takeIf { it > 0f } ?: 72f
            val bitmap = Bitmap.createBitmap(
                w.toInt().coerceIn(1, 800),
                h.toInt().coerceIn(1, 400),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas)
            Log.i(TAG, "decode ok via=SVG path=$path ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "decode SVG failed path=$path: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
