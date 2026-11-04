package io.github.mazin125533.nfccard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 名刺を全画面で表示する。左右スワイプで切り替え、表示中の名刺の URL を NFC で渡す。
 * 画面タップで「追加」「編集」ボタンを表示する。
 */
class CardActivity : Activity() {

    private lateinit var store: CardStore
    private lateinit var cardView: ImageView
    private lateinit var empty: View
    private lateinit var controls: View
    private lateinit var editButton: View
    private lateinit var dots: TextView
    private lateinit var nfcWarning: View
    private lateinit var nfcWarningText: TextView
    private var nfcAdapter: NfcAdapter? = null

    private var cards: List<CardStore.Card> = emptyList()
    private var index = 0
    private var shownBitmap: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { controls.animate().alpha(0f).withEndAction { controls.visibility = View.GONE } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 見せている間は画面を消さない

        store = CardStore(this)
        cardView = findViewById(R.id.card)
        empty = findViewById(R.id.empty)
        controls = findViewById(R.id.controls)
        editButton = findViewById(R.id.edit)
        dots = findViewById(R.id.dots)
        nfcWarning = findViewById(R.id.nfc_warning)
        nfcWarningText = findViewById(R.id.nfc_warning_text)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        findViewById<Button>(R.id.add).setOnClickListener { openEditor(null) }
        findViewById<Button>(R.id.empty_add).setOnClickListener { openEditor(null) }
        editButton.setOnClickListener { cards.getOrNull(index)?.let { openEditor(it.id) } }
        findViewById<Button>(R.id.nfc_settings).setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 500) return false
                move(if (vx < 0) 1 else -1)   // 左へ払うと次の名刺
                return true
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        // 前回表示していた名刺から始める
        currentId = savedInstanceState?.getString(STATE_ID) ?: getPreferences(MODE_PRIVATE).getString(STATE_ID, null)
    }

    /** 表示中の名刺の ID（編集画面から戻ったときに位置を復元するため） */
    private var currentId: String? = null

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        cards = store.load()
        index = cards.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        showCurrent(animateFrom = 0)
        showControlsBriefly()

        SharedUrl.active = true
        SharedUrl.onSent = {
            Haptics.sent(this)
            runOnUiThread { Toast.makeText(this, R.string.status_sent, Toast.LENGTH_SHORT).show() }
        }
        // 同じ AID を使う他のアプリより、この画面を開いている間はこのアプリを優先する
        cardEmulation()?.setPreferredService(this, ComponentName(this, NdefHostApduService::class.java))
        updateNfcWarning()
    }

    override fun onPause() {
        super.onPause()
        SharedUrl.active = false
        SharedUrl.onSent = null
        cardEmulation()?.unsetPreferredService(this)
        handler.removeCallbacks(hideControls)
        getPreferences(MODE_PRIVATE).edit().putString(STATE_ID, currentId).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ID, currentId)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK) {
            // 保存した名刺を表示する（削除したときは ID なし → 先頭へ）
            currentId = data?.getStringExtra(EditCardActivity.EXTRA_ID)
        }
    }

    private fun openEditor(id: String?) {
        val intent = Intent(this, EditCardActivity::class.java)
        if (id != null) intent.putExtra(EditCardActivity.EXTRA_ID, id)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EDIT)
    }

    private fun move(step: Int) {
        if (cards.size < 2) return
        index = (index + step + cards.size) % cards.size   // 端まで行ったら反対側へ
        showCurrent(animateFrom = step)
    }

    /** @param animateFrom 1: 右から入る / -1: 左から入る / 0: アニメーションなし */
    private fun showCurrent(animateFrom: Int) {
        val card = cards.getOrNull(index)
        empty.visibility = if (card == null) View.VISIBLE else View.GONE
        editButton.visibility = if (card == null) View.GONE else View.VISIBLE
        dots.text = if (cards.size > 1) cards.indices.joinToString("") { if (it == index) "●" else "○" } else ""

        if (card == null) {
            currentId = null
            SharedUrl.clear()
            setBitmap(null)
            return
        }
        currentId = card.id
        SharedUrl.set(card.url)

        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val bitmap = ImageImport.loadForDisplay(store.imageFile(card), maxPx)
        if (bitmap == null) Toast.makeText(this, R.string.image_load_failed, Toast.LENGTH_SHORT).show()

        if (animateFrom == 0) {
            setBitmap(bitmap)
            return
        }
        val width = cardView.width.toFloat()
        cardView.animate().cancel()
        cardView.animate().translationX(-animateFrom * width).alpha(0f).setDuration(120).withEndAction {
            setBitmap(bitmap)
            cardView.translationX = animateFrom * width
            cardView.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun setBitmap(bitmap: Bitmap?) {
        cardView.setImageBitmap(bitmap)
        cardView.translationX = 0f
        cardView.alpha = 1f
        shownBitmap?.takeIf { it !== bitmap }?.recycle()
        shownBitmap = bitmap
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE && controls.alpha > 0f) {
            handler.removeCallbacks(hideControls)
            hideControls.run()
        } else {
            showControlsBriefly()
        }
    }

    private fun showControlsBriefly() {
        handler.removeCallbacks(hideControls)
        controls.animate().cancel()
        controls.alpha = 1f
        controls.visibility = View.VISIBLE
        if (cards.isNotEmpty()) handler.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun updateNfcWarning() {
        val adapter = nfcAdapter
        val hce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val message = when {
            adapter == null || !hce -> R.string.status_no_nfc
            !adapter.isEnabled -> R.string.status_nfc_off
            else -> null
        }
        nfcWarning.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { nfcWarningText.setText(it) }
        findViewById<View>(R.id.nfc_settings).visibility =
            if (adapter != null && hce) View.VISIBLE else View.GONE
    }

    private fun cardEmulation(): CardEmulation? {
        val adapter = nfcAdapter ?: return null
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
        return CardEmulation.getInstance(adapter)
    }

    /** ステータスバーとナビゲーションバーを隠す（端からスワイプで一時的に出せる） */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private companion object {
        const val REQ_EDIT = 1
        const val STATE_ID = "current_id"
        const val CONTROLS_HIDE_MS = 3000L
    }
}
