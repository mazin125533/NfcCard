package io.github.mazin125533.nfccard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** 画像の取り込みと表示用の読み込み */
object ImageImport {
    /** 取り込むときの長辺の上限。名刺の全画面表示には十分で、メモリも食いすぎない */
    private const val MAX_IMPORT_PX = 2560

    /**
     * 選んだ画像（content:// など）を縮小・回転補正して、アプリ内の一時ファイルに保存する。
     * 透過のある画像は PNG、それ以外は JPEG で保存する。
     */
    fun import(context: Context, uri: Uri, outDir: File = context.cacheDir): File {
        val bitmap = decode(context, uri, MAX_IMPORT_PX)
        val png = bitmap.hasAlpha()
        val out = File(outDir, "import-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
        out.outputStream().use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
        }
        bitmap.recycle()
        return out
    }

    /** 保存済みの画像を、画面に収まる程度の大きさで読み込む */
    fun loadForDisplay(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun decode(context: Context, uri: Uri, maxPx: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder は EXIF の向きも自動で反映する
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxPx.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // compress() できるように
            }
        }

        // Android 8.x: BitmapFactory で縮小し、EXIF の向きを手で反映する
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val bitmap = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("画像として読み込めません: $uri")
        val orientation = resolver.openInputStream(uri).use {
            if (it == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** 長辺が maxPx 以下になる最小の 2 の累乗 */
    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var size = 1
        while (max(w, h) / (size * 2) >= maxPx) size *= 2
        return size
    }
}
