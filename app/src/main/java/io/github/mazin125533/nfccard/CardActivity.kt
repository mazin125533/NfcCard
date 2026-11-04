package io.github.mazin125533.nfccard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 名刺を全画面で表示する。
 * - 上部のタブで名刺を切り替え、表示中の名刺の URL を NFC で渡す
 * - 左右スワイプで同じ名刺の画像（ページ）を切り替え
 * - 裏面のあるページはダブルタップか「裏返す」で裏返す
 * - 画面タップで「追加」「編集」ボタンを表示する
 */
class CardActivity : Activity() {

    private lateinit var store: CardStore
    private lateinit var root: View
    private lateinit var cardView: ImageView
    private lateinit var empty: View
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var tabs: LinearLayout
    private lateinit var controls: View
    private lateinit var editButton: View
    private lateinit var flipButton: View
    private lateinit var dots: TextView
    private lateinit var nfcWarning: View
    private lateinit var nfcWarningText: TextView
    private var nfcAdapter: NfcAdapter? = null

    private var cards: List<CardStore.Card> = emptyList()
    private var cardIndex = 0
    private var pageIndex = 0
    private var showingBack = false
    private var shownBitmap: Bitmap? = null

    /** 表示中の名刺の ID（編集画面から戻ったときなどに位置を復元するため） */
    private var currentId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { controls.animate().alpha(0f).withEndAction { controls.visibility = View.GONE } }

    private val currentCard get() = cards.getOrNull(cardIndex)
    private val currentPage get() = currentCard?.pages?.getOrNull(pageIndex)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 見せている間は画面を消さない

        store = CardStore(this)
        root = findViewById(R.id.root)
        cardView = findViewById(R.id.card)
        empty = findViewById(R.id.empty)
        tabsScroll = findViewById(R.id.tabs_scroll)
        tabs = findViewById(R.id.tabs)
        controls = findViewById(R.id.controls)
        editButton = findViewById(R.id.edit)
        flipButton = findViewById(R.id.flip)
        dots = findViewById(R.id.dots)
        nfcWarning = findViewById(R.id.nfc_warning)
        nfcWarningText = findViewById(R.id.nfc_warning_text)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        findViewById<Button>(R.id.add).setOnClickListener { openEditor(null) }
        findViewById<Button>(R.id.empty_add).setOnClickListener { openEditor(null) }
        editButton.setOnClickListener { currentCard?.let { openEditor(it.id) } }
        flipButton.setOnClickListener { flip(); showControlsBriefly() }
        findViewById<Button>(R.id.nfc_settings).setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                flip()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 500) return false
                movePage(if (vx < 0) 1 else -1)   // 左へ払うと次の画像
                return true
            }
        })
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        // ノッチ・パンチホールにタブが隠れないようにする
        val topBar = findViewById<View>(R.id.top_bar)
        topBar.setOnApplyWindowInsetsListener { v, insets ->
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout?.safeInsetTop ?: 0 else 0
            v.setPadding(0, top, 0, 0)
            insets
        }

        // 前回表示していた名刺から始める
        val prefs = getPreferences(MODE_PRIVATE)
        currentId = savedInstanceState?.getString(STATE_ID) ?: prefs.getString(STATE_ID, null)
        pageIndex = savedInstanceState?.getInt(STATE_PAGE) ?: prefs.getInt(STATE_PAGE, 0)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        cards = store.load()
        cardIndex = cards.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        pageIndex = pageIndex.coerceIn(0, maxOf(0, (currentCard?.pages?.size ?: 1) - 1))
        showingBack = false
        rebuildTabs()
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
        getPreferences(MODE_PRIVATE).edit().putString(STATE_ID, currentId).putInt(STATE_PAGE, pageIndex).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ID, currentId)
        outState.putInt(STATE_PAGE, pageIndex)
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
            val id = data?.getStringExtra(EditCardActivity.EXTRA_ID)
            if (id != currentId) pageIndex = 0
            currentId = id
        }
    }

    private fun openEditor(id: String?) {
        val intent = Intent(this, EditCardActivity::class.java)
        if (id != null) intent.putExtra(EditCardActivity.EXTRA_ID, id)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EDIT)
    }

    // ---- 切り替え ----

    private fun selectCard(index: Int) {
        if (index == cardIndex) return
        val step = if (index > cardIndex) 1 else -1
        cardIndex = index
        pageIndex = 0
        showingBack = false
        showCurrent(animateFrom = step)
    }

    private fun movePage(step: Int) {
        val pages = currentCard?.pages ?: return
        if (pages.size < 2) return
        pageIndex = (pageIndex + step + pages.size) % pages.size   // 端まで行ったら反対側へ
        showingBack = false
        showCurrent(animateFrom = step)
    }

    /** 表と裏を入れ替える（裏面のないページでは何もしない） */
    private fun flip() {
        if (currentPage?.back == null) return
        showingBack = !showingBack
        val bitmap = loadCurrentBitmap()
        cardView.animate().cancel()
        cardView.cameraDistance = 8000 * resources.displayMetrics.density
        cardView.animate().rotationY(90f).setDuration(140).withEndAction {
            setBitmap(bitmap)
            cardView.rotationY = -90f
            cardView.animate().rotationY(0f).setDuration(140).start()
        }.start()
        updateIndicators()
    }

    // ---- 表示 ----

    /** @param animateFrom 1: 右から入る / -1: 左から入る / 0: アニメーションなし */
    private fun showCurrent(animateFrom: Int) {
        val card = currentCard
        empty.visibility = if (card == null) View.VISIBLE else View.GONE
        editButton.visibility = if (card == null) View.GONE else View.VISIBLE
        updateIndicators()

        if (card == null) {
            currentId = null
            SharedUrl.clear()
            setBitmap(null)
            return
        }
        currentId = card.id
        SharedUrl.set(card.url)

        val bitmap = loadCurrentBitmap()
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

    private fun loadCurrentBitmap(): Bitmap? {
        val page = currentPage ?: return null
        val name = if (showingBack) page.back ?: page.front else page.front
        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        return ImageImport.loadForDisplay(store.file(name), maxPx).also {
            if (it == null) Toast.makeText(this, R.string.image_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setBitmap(bitmap: Bitmap?) {
        cardView.setImageBitmap(bitmap)
        cardView.translationX = 0f
        cardView.rotationY = 0f
        cardView.alpha = 1f
        shownBitmap?.takeIf { it !== bitmap }?.recycle()
        shownBitmap = bitmap
    }

    /** タブの選択、ページの点、「裏返す」ボタンを今の状態に合わせる */
    private fun updateIndicators() {
        val pages = currentCard?.pages.orEmpty()
        val pageDots = if (pages.size > 1) pages.indices.joinToString("") { if (it == pageIndex) "●" else "○" } else ""
        dots.text = listOf(pageDots, if (showingBack) getString(R.string.showing_back) else "")
            .filter { it.isNotEmpty() }.joinToString("  ")
        flipButton.visibility = if (currentPage?.back != null) View.VISIBLE else View.GONE

        for (i in 0 until tabs.childCount) styleTab(tabs.getChildAt(i) as TextView, i == cardIndex)
        tabs.getChildAt(cardIndex)?.let { tab ->
            tabsScroll.post { tabsScroll.smoothScrollTo(maxOf(0, tab.left - (tabsScroll.width - tab.width) / 2), 0) }
        }
    }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        tabsScroll.visibility = if (cards.size >= 2) View.VISIBLE else View.GONE
        if (cards.size < 2) return
        val density = resources.displayMetrics.density
        cards.forEachIndexed { i, card ->
            tabs.addView(TextView(this).apply {
                text = card.label
                maxWidth = (160 * density).toInt()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = 14f
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                setOnClickListener { selectCard(i); showControlsBriefly() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * density).toInt() })
        }
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.background = GradientDrawable().apply {
            cornerRadius = 100f
            if (selected) setColor(getColor(R.color.accent)) else setStroke(2, 0x88FFFFFF.toInt())
        }
        tab.setTextColor(if (selected) Color.WHITE else 0xCCFFFFFF.toInt())
        tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
        const val STATE_PAGE = "current_page"
        const val CONTROLS_HIDE_MS = 3000L
    }
}
