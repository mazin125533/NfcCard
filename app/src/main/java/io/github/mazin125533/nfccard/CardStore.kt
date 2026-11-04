package io.github.mazin125533.nfccard

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 名刺（画像 + URL）の保存先。
 * - 並び順と URL は SharedPreferences に JSON で保存
 * - 画像はアプリ専用領域（filesDir/cards）にコピーして保存するので、元の写真を消しても残る
 */
class CardStore(context: Context, prefsName: String = "cards", dirName: String = "cards") {

    data class Card(val id: String, val url: String, val imageName: String)

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, dirName).apply { mkdirs() }

    fun load(): List<Card> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Card(o.getString("id"), o.getString("url"), o.getString("image"))
            }.filter { imageFile(it).exists() }   // 画像が消えていたら表示しない
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun find(id: String): Card? = load().firstOrNull { it.id == id }

    fun imageFile(card: Card) = File(dir, card.imageName)

    /**
     * 名刺を追加する。[image] は取り込み済みの一時ファイル（[ImageImport] の出力）で、保存先へ移動される。
     * @return 追加した名刺（末尾に追加）
     */
    fun add(url: String, image: File): Card {
        val id = UUID.randomUUID().toString()
        val card = Card(id, url, moveIn(id, image))
        save(load() + card)
        return card
    }

    /** URL と、必要なら画像を差し替える */
    fun update(id: String, url: String, newImage: File?): Card? {
        val cards = load()
        val old = cards.firstOrNull { it.id == id } ?: return null
        val imageName = if (newImage != null) {
            val name = moveIn(id, newImage)
            if (name != old.imageName) imageFile(old).delete()
            name
        } else {
            old.imageName
        }
        val updated = Card(id, url, imageName)
        save(cards.map { if (it.id == id) updated else it })
        return updated
    }

    fun delete(id: String) {
        val cards = load()
        cards.firstOrNull { it.id == id }?.let { imageFile(it).delete() }
        save(cards.filter { it.id != id })
    }

    /** テスト用: すべて消す */
    fun clear() {
        prefs.edit().remove(KEY).commit()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun moveIn(id: String, image: File): String {
        // 画像を差し替えたとき表示側のキャッシュが残らないよう、ファイル名は毎回変える
        val name = "$id-${System.currentTimeMillis()}.${image.extension.ifEmpty { "jpg" }}"
        val dest = File(dir, name)
        if (!image.renameTo(dest)) {
            image.copyTo(dest, overwrite = true)
            image.delete()
        }
        return name
    }

    private fun save(cards: List<Card>) {
        val array = JSONArray()
        cards.forEach { array.put(JSONObject().put("id", it.id).put("url", it.url).put("image", it.imageName)) }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private companion object {
        const val KEY = "entries"
    }
}
