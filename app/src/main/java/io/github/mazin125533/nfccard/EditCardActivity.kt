package io.github.mazin125533.nfccard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * 名刺の追加・編集。EXTRA_ID があれば編集、無ければ新規作成（開くとすぐ画像選択を出す）。
 * 保存したら EXTRA_ID に名刺の ID を入れて RESULT_OK で戻る（削除したときは ID なし）。
 */
class EditCardActivity : Activity() {

    private lateinit var store: CardStore
    private lateinit var preview: ImageView
    private lateinit var pickButton: Button
    private lateinit var urlInput: EditText
    private lateinit var progress: View

    private var card: CardStore.Card? = null
    /** 取り込んだがまだ保存していない画像 */
    private var pendingImage: File? = null
    private var previewBitmap: Bitmap? = null
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_card)
        store = CardStore(this)
        preview = findViewById(R.id.preview)
        pickButton = findViewById(R.id.pick)
        urlInput = findViewById(R.id.url)
        progress = findViewById(R.id.progress)

        card = intent.getStringExtra(EXTRA_ID)?.let { store.find(it) }
        pendingImage = savedInstanceState?.getString(STATE_PENDING)?.let(::File)?.takeIf { it.exists() }

        pickButton.setOnClickListener { pickImage() }
        preview.setOnClickListener { pickImage() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete).apply {
            visibility = if (card != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        }

        if (savedInstanceState == null) card?.let { urlInput.setText(it.url) }
        refreshPreview()

        // 新規作成はまず画像を選んでもらう
        if (card == null && pendingImage == null && savedInstanceState == null) pickImage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImage?.let { outState.putString(STATE_PENDING, it.path) }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewBitmap?.recycle()
        // 保存せずに閉じたら、取り込んだ一時ファイルは消す
        if (isFinishing && !saved) pendingImage?.delete()
    }

    private fun pickImage() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)   // 写真選択ツール（権限不要）
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            // 新規作成で画像を選ばなかったら、名刺の画面に戻る
            if (card == null && pendingImage == null) finish()
            return
        }
        importImage(uri)
    }

    private fun importImage(uri: Uri) {
        setBusy(true)
        thread {
            val result = runCatching { ImageImport.import(this, uri) }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { file ->
                    pendingImage?.delete()
                    pendingImage = file
                    refreshPreview()
                }.onFailure {
                    Toast.makeText(this, R.string.image_import_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshPreview() {
        val file = pendingImage ?: card?.let { store.imageFile(it) }
        previewBitmap?.recycle()
        previewBitmap = file?.let { ImageImport.loadForDisplay(it, 1280) }
        preview.setImageBitmap(previewBitmap)
        pickButton.setText(if (file == null) R.string.pick_image else R.string.change_image)
    }

    private fun save() {
        val url = SharedUrl.normalize(urlInput.text.toString())
        if (url == null) {
            urlInput.error = getString(R.string.invalid_url)
            return
        }
        val current = card
        val result = if (current == null) {
            val image = pendingImage ?: run {
                Toast.makeText(this, R.string.need_image, Toast.LENGTH_SHORT).show()
                return
            }
            store.add(url, image)
        } else {
            store.update(current.id, url, pendingImage)
        }
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

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pickButton.isEnabled = !busy
        findViewById<View>(R.id.save).isEnabled = !busy
    }

    companion object {
        const val EXTRA_ID = "card_id"
        private const val REQ_PICK = 1
        private const val STATE_PENDING = "pending_image"
    }
}
