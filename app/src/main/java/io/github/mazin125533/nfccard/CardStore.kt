package io.github.mazin125533.nfccard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 名刺（名前 + URL + ページ1枚以上）の保存先。ページは表の画像と、あれば裏の画像。
 * - 並び順・名前・URL は SharedPreferences に JSON で保存
 * - 画像はアプリ専用領域（filesDir/cards）にコピーして保存するので、元の写真を消しても残る
 */
class CardStore(context: Context, prefsName: String = "cards", dirName: String = "cards") {

    /** 画像のファイル名（保存先フォルダ内） */
    data class Page(val front: String, val back: String? = null)

    data class Card(val id: String, val name: String, val url: String, val pages: List<Page>) {
        /** タブに出す名前。未設定なら URL のホスト名 */
        val label: String
            get() = name.ifBlank { Uri.parse(url).host?.removePrefix("www.") ?: url }
    }

    /** 保存・更新に渡すページ。保存済みの画像（[file] で得たもの）か、取り込んだ一時ファイル */
    data class PageFiles(val front: File, val back: File? = null)

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, dirName).apply { mkdirs() }

    fun load(): List<Card> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> parse(array.getJSONObject(i)) }
                .filter { it.pages.isNotEmpty() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun parse(o: JSONObject): Card {
        val pages = o.optJSONArray("pages")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                Page(p.getString("front"), p.optString("back").takeIf { it.isNotEmpty() })
            }
        } ?: listOfNotNull(o.optString("image").takeIf { it.isNotEmpty() }?.let { Page(it) })   // v1.0.x は画像1枚
        // 画像が消えていたら表示しない
        val existing = pages.filter { exists(it.front) }.map { if (it.back != null && !exists(it.back)) it.copy(back = null) else it }
        return Card(o.getString("id"), o.optString("name"), o.getString("url"), existing)
    }

    fun find(id: String): Card? = load().firstOrNull { it.id == id }

    fun file(name: String) = File(dir, name)

    fun pageFiles(card: Card): List<PageFiles> = card.pages.map { PageFiles(file(it.front), it.back?.let(::file)) }

    /** この保存先に入っている画像か（編集画面で、既存の画像と取り込んだばかりの画像を見分ける） */
    fun isStored(file: File) = file.parentFile?.canonicalPath == dir.canonicalPath

    /** 名刺を末尾に追加する。一時ファイルは保存先へ移動される */
    fun add(name: String, url: String, pages: List<PageFiles>): Card {
        require(pages.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val card = Card(id, name.trim(), url, pages.map { storePage(id, it) })
        save(load() + card)
        return card
    }

    /**
     * 名前・URL・ページを更新する。[pages] は新しい並び順。保存済みの画像はそのまま、
     * それ以外は保存先へ移動する。使われなくなった画像のファイルは消す。
     */
    fun update(id: String, name: String, url: String, pages: List<PageFiles>): Card? {
        require(pages.isNotEmpty())
        val cards = load()
        val old = cards.firstOrNull { it.id == id } ?: return null
        val newPages = pages.map { storePage(id, it) }
        (old.pages.imageNames() - newPages.imageNames().toSet()).forEach { file(it).delete() }
        val updated = Card(id, name.trim(), url, newPages)
        save(cards.map { if (it.id == id) updated else it })
        return updated
    }

    fun delete(id: String) {
        val cards = load()
        cards.firstOrNull { it.id == id }?.let { card -> card.pages.imageNames().forEach { file(it).delete() } }
        save(cards.filter { it.id != id })
    }

    /** テスト用: すべて消す */
    fun clear() {
        prefs.edit().remove(KEY).commit()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun List<Page>.imageNames() = flatMap { listOfNotNull(it.front, it.back) }

    private fun exists(name: String) = file(name).exists()

    private fun storePage(id: String, page: PageFiles) =
        Page(storeImage(id, page.front), page.back?.let { storeImage(id, it) })

    private fun storeImage(id: String, image: File): String {
        if (isStored(image)) return image.name
        val name = "$id-${UUID.randomUUID().toString().take(8)}.${image.extension.ifEmpty { "jpg" }}"
        val dest = file(name)
        if (!image.renameTo(dest)) {
            image.copyTo(dest, overwrite = true)
            image.delete()
        }
        return name
    }

    private fun save(cards: List<Card>) {
        val array = JSONArray()
        cards.forEach { card ->
            val pages = JSONArray()
            card.pages.forEach { p -> pages.put(JSONObject().put("front", p.front).apply { p.back?.let { put("back", it) } }) }
            array.put(JSONObject().put("id", card.id).put("name", card.name).put("url", card.url).put("pages", pages))
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private companion object {
        const val KEY = "entries"
    }
}
