package io.github.mazin125533.nfccard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * 名刺の追加・編集。EXTRA_ID があれば編集、無ければ新規作成（開くとすぐ画像選択を出す）。
 * 名刺は名前・URL と、1枚以上のページ（表の画像と、あれば裏の画像）からなる。
 * 保存したら EXTRA_ID に名刺の ID を入れて RESULT_OK で戻る（削除したときは ID なし）。
 */
class EditCardActivity : Activity() {

    private lateinit var store: CardStore
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var pagesView: LinearLayout
    private lateinit var pagesScroll: HorizontalScrollView
    private lateinit var pageDetail: View
    private lateinit var frontView: ImageView
    private lateinit var backView: ImageView
    private lateinit var backEmpty: View
    private lateinit var progress: View

    private var card: CardStore.Card? = null
    /** 編集中のページ。保存済みの画像と、取り込んだばかりの一時ファイルが混ざる */
    private val pages = mutableListOf<CardStore.PageFiles>()
    private var selected = 0
    private var saved = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_card)
        store = CardStore(this)
        nameInput = findViewById(R.id.name)
        urlInput = findViewById(R.id.url)
        pagesView = findViewById(R.id.pages)
        pagesScroll = findViewById(R.id.pages_scroll)
        pageDetail = findViewById(R.id.page_detail)
        frontView = findViewById(R.id.front)
        backView = findViewById(R.id.back)
        backEmpty = findViewById(R.id.back_empty)
        progress = findViewById(R.id.progress)

        card = intent.getStringExtra(EXTRA_ID)?.let { store.find(it) }
        if (savedInstanceState != null) {
            restorePages(savedInstanceState)
        } else {
            card?.let {
                nameInput.setText(it.name)
                urlInput.setText(it.url)
                pages += store.pageFiles(it)
            }
        }

        frontView.setOnClickListener { pick(REQ_FRONT) }
        findViewById<View>(R.id.back).setOnClickListener { pick(REQ_BACK) }
        findViewById<Button>(R.id.move_left).setOnClickListener { movePage(-1) }
        findViewById<Button>(R.id.move_right).setOnClickListener { movePage(1) }
        findViewById<Button>(R.id.remove_back).setOnClickListener { removeBack() }
        findViewById<Button>(R.id.remove_page).setOnClickListener { removePage() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete).apply {
            visibility = if (card != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        }
        refresh()

        // 新規作成はまず画像を選んでもらう
        if (card == null && pages.isEmpty() && savedInstanceState == null) pick(REQ_ADD)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_FRONTS, pages.map { it.front.path }.toTypedArray())
        outState.putStringArray(STATE_BACKS, pages.map { it.back?.path ?: "" }.toTypedArray())
        outState.putInt(STATE_SELECTED, selected)
    }

    private fun restorePages(state: Bundle) {
        val fronts = state.getStringArray(STATE_FRONTS) ?: return
        val backs = state.getStringArray(STATE_BACKS) ?: emptyArray()
        fronts.forEachIndexed { i, path ->
            val front = File(path).takeIf { it.exists() } ?: return@forEachIndexed
            val back = backs.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
            pages += CardStore.PageFiles(front, back)
        }
        selected = state.getInt(STATE_SELECTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 保存せずに閉じたら、取り込んだ一時ファイルは消す
        if (isFinishing && !saved) pages.forEach { discard(it.front); it.back?.let(::discard) }
    }

    /** 取り込んだばかりの一時ファイルなら消す（保存済みの画像は保存時に CardStore が片付ける） */
    private fun discard(file: File) {
        if (!store.isStored(file)) file.delete()
    }

    // ---- 画像の選択と取り込み ----

    private fun pick(request: Int) {
        if (busy) return
        if (request != REQ_ADD && pages.isEmpty()) return
        val multiple = request == REQ_ADD
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {   // 写真選択ツール（権限不要）
                if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(MAX_PICK, MediaStore.getPickImagesMaxLimit()))
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, request)
    }

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = if (resultCode == RESULT_OK && data != null) urisOf(data) else emptyList()
        if (uris.isEmpty()) {
            // 新規作成で画像を選ばなかったら、名刺の画面に戻る
            if (requestCode == REQ_ADD && card == null && pages.isEmpty()) finish()
            return
        }
        importImages(requestCode, uris)
    }

    private fun urisOf(data: Intent): List<Uri> {
        val clip = data.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    private fun importImages(request: Int, uris: List<Uri>) {
        setBusy(true)
        thread {
            val files = uris.mapNotNull { runCatching { ImageImport.import(this, it) }.getOrNull() }
            runOnUiThread {
                setBusy(false)
                if (files.size < uris.size) Toast.makeText(this, R.string.image_import_failed, Toast.LENGTH_LONG).show()
                if (isDestroyed) {
                    files.forEach { it.delete() }
                    return@runOnUiThread
                }
                applyImported(request, files)
            }
        }
    }

    private fun applyImported(request: Int, files: List<File>) {
        if (files.isEmpty()) {
            if (request == REQ_ADD && card == null && pages.isEmpty()) finish()
            return
        }
        when (request) {
            REQ_ADD -> {
                pages += files.map { CardStore.PageFiles(it) }
                selected = pages.size - files.size   // 追加した最初のページを選ぶ
            }
            REQ_FRONT -> pages.getOrNull(selected)?.let { page ->
                discard(page.front)
                pages[selected] = page.copy(front = files.first())
            }
            REQ_BACK -> pages.getOrNull(selected)?.let { page ->
                page.back?.let(::discard)
                pages[selected] = page.copy(back = files.first())
            }
        }
        refresh()
    }

    // ---- ページの操作 ----

    private fun movePage(step: Int) {
        val to = selected + step
        if (selected !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(selected))
        selected = to
        refresh()
    }

    private fun removeBack() {
        val page = pages.getOrNull(selected) ?: return
        page.back?.let(::discard)
        pages[selected] = page.copy(back = null)
        refresh()
    }

    private fun removePage() {
        val page = pages.getOrNull(selected) ?: return
        discard(page.front)
        page.back?.let(::discard)
        pages.removeAt(selected)
        refresh()
    }

    // ---- 表示 ----

    private fun refresh() {
        selected = selected.coerceIn(0, maxOf(0, pages.size - 1))
        rebuildPageStrip()

        val page = pages.getOrNull(selected)
        pageDetail.visibility = if (page == null) View.GONE else View.VISIBLE
        frontView.setImageBitmap(page?.front?.let { ImageImport.loadForDisplay(it, PREVIEW_PX) })
        backView.setImageBitmap(page?.back?.let { ImageImport.loadForDisplay(it, PREVIEW_PX) })
        backEmpty.visibility = if (page?.back == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.remove_back).visibility = if (page?.back != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.move_left).isEnabled = selected > 0
        findViewById<View>(R.id.move_right).isEnabled = selected < pages.size - 1
    }

    private fun rebuildPageStrip() {
        pagesView.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (THUMB_DP * density).toInt()
        val margin = (6 * density).toInt()
        val border = (3 * density).toInt()

        pages.forEachIndexed { i, page ->
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                setPadding(border, border, border, border)
                setBackgroundColor(if (i == selected) getColor(R.color.accent) else Color.TRANSPARENT)
                contentDescription = getString(R.string.page_number, i + 1)
                setOnClickListener { selected = i; refresh() }
            }
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x33888888)
                setImageBitmap(ImageImport.loadForDisplay(page.front, THUMB_PX))
            })
            if (page.back != null) {
                // 裏面があるページには印を付ける
                frame.addView(TextView(this).apply {
                    setText(R.string.back_badge)
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    setBackgroundColor(0xAA000000.toInt())
                    setPadding(margin / 2, 0, margin / 2, 0)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END))
            }
            pagesView.addView(frame)
        }

        // 末尾の「＋」で画像を追加（複数選択可）
        pagesView.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            gravity = Gravity.CENTER
            text = "＋"
            textSize = 28f
            setBackgroundColor(0x33888888)
            contentDescription = getString(R.string.add_pages)
            setOnClickListener { pick(REQ_ADD) }
        })
        pagesScroll.post {
            pagesView.getChildAt(selected)?.let { pagesScroll.smoothScrollTo(maxOf(0, it.left - margin), 0) }
        }
    }

    // ---- 保存・削除 ----

    private fun save() {
        if (busy) return
        val url = SharedUrl.normalize(urlInput.text.toString())
        if (url == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.need_image, Toast.LENGTH_SHORT).show()
            return
        }
        val name = nameInput.text.toString()
        val current = card
        val result = if (current == null) store.add(name, url, pages) else store.update(current.id, name, url, pages)
        saved = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, result?.id))
        finish()
    }

    private fun confirmDelete() {
        val current = card ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(current.id)
                setResult(RESULT_OK, Intent())
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        findViewById<View>(R.id.save).isEnabled = !value
    }

    companion object {
        const val EXTRA_ID = "card_id"
        private const val REQ_ADD = 1
        private const val REQ_FRONT = 2
        private const val REQ_BACK = 3
        private const val MAX_PICK = 20
        private const val THUMB_DP = 72
        private const val THUMB_PX = 256
        private const val PREVIEW_PX = 1024
        private const val STATE_FRONTS = "fronts"
        private const val STATE_BACKS = "backs"
        private const val STATE_SELECTED = "selected"
    }
}
