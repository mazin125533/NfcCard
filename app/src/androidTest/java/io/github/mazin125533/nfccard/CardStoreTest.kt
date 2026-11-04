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
import org.junit.Assert.assertNull
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
        store = CardStore(context, PREFS, PREFS)
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun addUpdateDeleteKeepsOrderAndFiles() {
        val a = store.add("仕事", "https://a.example/", listOf(page(Color.RED)))
        val b = store.add("", "https://b.example/", listOf(page(Color.BLUE)))
        assertEquals(listOf(a.id, b.id), store.load().map { it.id })
        assertTrue(store.file(a.pages[0].front).exists())
        assertEquals("仕事", store.find(a.id)!!.label)
        assertEquals("b.example", store.find(b.id)!!.label)   // 名前が空なら URL のホスト名

        // URL だけ変更（画像はそのまま）
        store.update(a.id, "仕事", "https://a2.example/", store.pageFiles(store.find(a.id)!!))
        assertEquals("https://a2.example/", store.find(a.id)!!.url)
        assertEquals(a.pages, store.find(a.id)!!.pages)

        // 画像を差し替えると古い画像ファイルは消える
        val oldFile = store.file(a.pages[0].front)
        val updated = store.update(a.id, "仕事", "https://a2.example/", listOf(page(Color.GREEN)))!!
        assertFalse(oldFile.exists())
        assertTrue(store.file(updated.pages[0].front).exists())
        assertEquals(listOf(a.id, b.id), store.load().map { it.id })   // 並び順は変わらない

        store.delete(a.id)
        assertEquals(listOf(b.id), store.load().map { it.id })
        assertFalse(store.file(updated.pages[0].front).exists())
    }

    @Test
    fun multiplePagesWithBackSides() {
        val card = store.add("名刺", "https://example.com/",
            listOf(page(Color.RED, back = Color.BLACK), page(Color.GREEN), page(Color.BLUE, back = Color.WHITE)))
        assertEquals(3, card.pages.size)
        assertNotNull(card.pages[0].back)
        assertNull(card.pages[1].back)
        val names = card.pages.flatMap { listOfNotNull(it.front, it.back) }
        assertEquals(5, names.toSet().size)   // 同時に取り込んでもファイル名は重ならない
        names.forEach { assertTrue(store.file(it).exists()) }

        // 並べ替え + 1枚目の裏面を外す + 2枚目を外す
        val files = store.pageFiles(card)
        val reordered = listOf(files[2], files[0].copy(back = null))
        val updated = store.update(card.id, "名刺", card.url, reordered)!!
        assertEquals(listOf(card.pages[2], card.pages[0].copy(back = null)), updated.pages)
        assertEquals(updated.pages, store.find(card.id)!!.pages)   // 保存し直しても同じ
        assertFalse(store.file(card.pages[0].back!!).exists())
        assertFalse(store.file(card.pages[1].front).exists())
        assertTrue(store.file(card.pages[2].back!!).exists())

        store.delete(card.id)
        names.forEach { assertFalse(store.file(it).exists()) }
    }

    @Test
    fun readsV1Format() {
        // v1.0.x の保存形式（画像1枚、名前なし）からそのまま読める
        val image = File(context.filesDir, "$PREFS/old-1.jpg")
        tempImage(Color.RED).copyTo(image, overwrite = true)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("entries", """[{"id":"old","url":"https:\/\/github.com\/me","image":"old-1.jpg"}]""").commit()

        val card = store.load().single()
        assertEquals("old", card.id)
        assertEquals("", card.name)
        assertEquals("github.com", card.label)
        assertEquals(listOf(CardStore.Page("old-1.jpg")), card.pages)

        // 編集して保存すると新しい形式になり、画像はそのまま使われる
        val updated = store.update("old", "GitHub", card.url, store.pageFiles(card) + page(Color.BLUE))!!
        assertEquals("old-1.jpg", updated.pages[0].front)
        assertEquals(updated, store.load().single())
        assertTrue(image.exists())
    }

    @Test
    fun missingImagesAreSkipped() {
        val card = store.add("", "https://example.com/", listOf(page(Color.RED, back = Color.BLACK), page(Color.GREEN)))
        store.file(card.pages[0].back!!).delete()
        assertNull(store.find(card.id)!!.pages[0].back)   // 裏面が消えていたら表だけ
        store.file(card.pages[0].front).delete()
        store.file(card.pages[1].front).delete()
        assertTrue(store.load().isEmpty())               // 画像が1枚もなければ名刺ごと表示しない
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

    @Test
    fun importManyAtOnceGivesDistinctFiles() {
        val src = writePng(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }, opaque = true)
        val outs = (1..5).map { ImageImport.import(context, Uri.fromFile(src)) }
        assertEquals(5, outs.map { it.path }.toSet().size)
        outs.forEach { assertTrue(it.length() > 0); it.delete() }
    }

    private fun page(front: Int, back: Int? = null) = CardStore.PageFiles(tempImage(front), back?.let(::tempImage))

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

    private companion object {
        const val PREFS = "cards_test"
    }
}
