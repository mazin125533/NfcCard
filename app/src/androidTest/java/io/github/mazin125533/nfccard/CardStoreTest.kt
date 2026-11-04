package io.github.mazin125533.nfccard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CardStoreTest {

    private lateinit var context: Context
    // アプリ本体の名刺を消さないよう、テスト専用の保存先を使う
    private lateinit var store: CardStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = CardStore(context, "cards_test", "cards_test")
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun addUpdateDeleteKeepsOrderAndFiles() {
        val a = store.add("https://a.example/", tempImage(Color.RED))
        val b = store.add("https://b.example/", tempImage(Color.BLUE))
        assertEquals(listOf(a.id, b.id), store.load().map { it.id })
        assertTrue(store.imageFile(a).exists())

        // URL だけ変更
        store.update(a.id, "https://a2.example/", null)
        assertEquals("https://a2.example/", store.find(a.id)!!.url)
        assertEquals(a.imageName, store.find(a.id)!!.imageName)

        // 画像を差し替えると古い画像ファイルは消える
        val oldFile = store.imageFile(a)
        val updated = store.update(a.id, "https://a2.example/", tempImage(Color.GREEN))!!
        assertFalse(oldFile.exists())
        assertTrue(store.imageFile(updated).exists())
        assertEquals(listOf(a.id, b.id), store.load().map { it.id })   // 並び順は変わらない

        store.delete(a.id)
        assertEquals(listOf(b.id), store.load().map { it.id })
        assertFalse(store.imageFile(updated).exists())
    }

    @Test
    fun importShrinksLargeImageAndKeepsAspect() {
        val src = writePng(Bitmap.createBitmap(4000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }, opaque = true)
        val out = ImageImport.import(context, Uri.fromFile(src))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, bounds)
        assertTrue("長辺 ${bounds.outWidth}", bounds.outWidth <= 2560)
        assertEquals(4.0, bounds.outWidth.toDouble() / bounds.outHeight, 0.05)
        assertEquals("jpg", out.extension)   // 透過なし → JPEG
        out.delete()
    }

    @Test
    fun importKeepsTransparencyAsPng() {
        val src = writePng(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }, opaque = false)
        val out = ImageImport.import(context, Uri.fromFile(src))
        assertEquals("png", out.extension)
        assertNotNull(ImageImport.loadForDisplay(out, 1000))
        out.delete()
    }

    private fun tempImage(color: Int): File {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565).apply { eraseColor(color) }
        return File.createTempFile("card", ".jpg", context.cacheDir).also { f ->
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
    }

    private fun writePng(bitmap: Bitmap, opaque: Boolean): File {
        bitmap.setHasAlpha(!opaque)
        return File.createTempFile("src", ".png", context.cacheDir).also { f ->
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
