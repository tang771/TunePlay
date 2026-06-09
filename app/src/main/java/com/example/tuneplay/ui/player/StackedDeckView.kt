package com.example.tuneplay.ui.player

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * 卡片堆叠视图 — 仿 Tinder 风格的三层封面交互。
 * 手势映射：
 * - 左滑 → 下一首，右滑 → 上一首
 * - 右下对角线 → 收藏（缩小飞向爱心图标位置）
 * - 上滑 → 跳过（卡片飞向上方）
 * - 点击左/右侧卡片 → 切换，点击中央卡片 → 全屏
 * 支持 VelocityTracker 快速滑动（超过阈值自动触发），
 * 动画期间使用 resetCardsInstant() 避免动画冲突。
 */
class StackedDeckView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callback {
        val currentIndex: Int
        val itemCount: Int
        fun loadCover(index: Int, imageView: ImageView)
        fun onSwitchTo(index: Int)
        fun onLike()
        fun onSkip()
        fun onFullScreen()
    }

    var callback: Callback? = null

    private val leftCard: ImageView
    private val centerCard: ImageView
    private val rightCard: ImageView

    private val coverRadius: Float
    private val density: Float
    private val peekOffsetPx: Int

    // Base card position (set by layoutCards, used for resets and drag)
    private var baseCenterX = 0f
    private var baseCenterY = 0f
    private var baseLeftX = 0f
    private var baseRightX = 0f

    // Gesture tracking
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = -1
    private var trackingTouch = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop: Int
    private val flingThreshold: Float

    init {
        setBackgroundColor(0xFF080810.toInt())

        density = resources.displayMetrics.density
        coverRadius = 20f * density
        peekOffsetPx = (40f * density).toInt()
        touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        flingThreshold = 800f * density

        leftCard = createCardView()
        centerCard = createCardView()
        rightCard = createCardView()

        addView(leftCard)
        addView(centerCard)
        addView(rightCard)
    }

    private fun createCardView(): ImageView {
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadius)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutCards()
        if (callback != null) refreshCards()
    }

    private fun layoutCards() {
        if (width == 0 || height == 0) return

        val cardSize = minOf(width, height)
        val centerX = (width - cardSize) / 2f
        val yOffset = cardSize * CARD_Y_BIAS
        val cardY = (height - cardSize) / 2f + yOffset

        // Store base positions for resets and drag handling
        // Side cards: only peekOffsetPx visible from behind center card edges
        baseCenterX = centerX
        baseCenterY = cardY
        baseLeftX = centerX - cardSize + peekOffsetPx
        baseRightX = centerX + cardSize - peekOffsetPx

        centerCard.apply {
            val lp = layoutParams ?: LayoutParams(cardSize, cardSize)
            lp.width = cardSize
            lp.height = cardSize
            layoutParams = lp
            translationX = centerX
            translationY = cardY
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            rotation = 0f
        }

        leftCard.apply {
            val lp = layoutParams ?: LayoutParams(cardSize, cardSize)
            lp.width = cardSize
            lp.height = cardSize
            layoutParams = lp
            translationX = baseLeftX
            translationY = cardY
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0.6f
        }

        rightCard.apply {
            val lp = layoutParams ?: LayoutParams(cardSize, cardSize)
            lp.width = cardSize
            lp.height = cardSize
            layoutParams = lp
            translationX = baseRightX
            translationY = cardY
            scaleX = 0.85f
            scaleY = 0.85f
            alpha = 0.6f
        }

        // Center card must always be on top
        centerCard.bringToFront()
    }

    fun refreshCards() {
        val cb = callback ?: return

        if (cb.itemCount == 0) {
            centerCard.setImageResource(com.example.tuneplay.R.drawable.ic_music_note)
            leftCard.visibility = INVISIBLE
            rightCard.visibility = INVISIBLE
            return
        }

        cb.loadCover(cb.currentIndex, centerCard)

        if (cb.itemCount == 2) {
            // Only show one side card for 2 songs (prev/next are the same song)
            val other = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
            leftCard.visibility = VISIBLE
            rightCard.visibility = INVISIBLE
            // Reset left card visual state in case it carries stale animation values
            leftCard.alpha = 0.6f
            leftCard.scaleX = 0.85f
            leftCard.scaleY = 0.85f
            leftCard.translationY = baseCenterY
            leftCard.translationX = baseRightX
            cb.loadCover(other, leftCard)
        } else if (cb.itemCount > 2) {
            val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
            val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0
            leftCard.visibility = VISIBLE
            rightCard.visibility = VISIBLE
            // Reset side card visual states
            leftCard.alpha = 0.6f
            leftCard.scaleX = 0.85f
            leftCard.scaleY = 0.85f
            leftCard.translationY = baseCenterY
            leftCard.translationX = baseLeftX
            rightCard.alpha = 0.6f
            rightCard.scaleX = 0.85f
            rightCard.scaleY = 0.85f
            rightCard.translationY = baseCenterY
            rightCard.translationX = baseRightX
            cb.loadCover(prev, leftCard)
            cb.loadCover(next, rightCard)
        } else {
            leftCard.visibility = INVISIBLE
            rightCard.visibility = INVISIBLE
        }
        centerCard.bringToFront()
    }

    // ── Touch Handling ──────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (callback == null || callback!!.itemCount == 0) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activePointerId = event.getPointerId(0)
                trackingTouch = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                velocityTracker?.addMovement(event)

                val dx = event.x - downX
                val dy = event.y - downY

                if (!trackingTouch && maxOf(abs(dx), abs(dy)) > touchSlop) {
                    trackingTouch = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (trackingTouch) {
                    val progress = (dx / (width * 0.5f)).coerceIn(-1f, 1f)
                    centerCard.translationX = baseCenterX + dx
                    centerCard.rotation = progress * 5f

                    val absProgress = abs(progress)
                    leftCard.translationX = baseLeftX + dx * 0.3f
                    leftCard.scaleX = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    leftCard.scaleY = leftCard.scaleX
                    leftCard.alpha = (0.6f + absProgress * 0.4f).coerceIn(0.6f, 1f)

                    rightCard.translationX = baseRightX + dx * 0.3f
                    rightCard.scaleX = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    rightCard.scaleY = rightCard.scaleX
                    rightCard.alpha = (0.6f + absProgress * 0.4f).coerceIn(0.6f, 1f)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                }
                val vx = velocityTracker?.xVelocity ?: 0f
                val vy = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (!trackingTouch) {
                    handleTap(downX, downY)
                    return true
                }

                val deltaX = centerCard.translationX - baseCenterX
                val deltaY = centerCard.translationY - baseCenterY
                val absDx = abs(deltaX)
                val absDy = abs(deltaY)

                when {
                    // Down-right diagonal → like
                    deltaX > touchSlop && deltaY > touchSlop &&
                        absDx > width * 0.15f && absDy > width * 0.1f &&
                        absDy > absDx * 0.3f && absDy < absDx * 2f -> {
                        animateLike()
                    }
                    // Strong upward fling → skip
                    vy < -flingThreshold && absDy > width * 0.15f && absDy > absDx -> {
                        animateSkip()
                    }
                    // Horizontal swipe: right → previous, left → next
                    absDx > width * 0.25f || abs(vx) > flingThreshold -> {
                        if (deltaX > 0 || vx > flingThreshold) {
                            animateSwitchRight()  // → prev song
                        } else {
                            animateSwitchLeft()   // → next song
                        }
                    }
                    // Otherwise → reset
                    else -> resetCards()
                }

                trackingTouch = false
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val newIndex = if (event.actionIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        downX = event.getX(newIndex)
                        downY = event.getY(newIndex)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Tap ─────────────────────────────────────────────────────────

    private fun handleTap(tx: Float, ty: Float) {
        val cb = callback ?: return

        // Use view's actual visual bounds (x/y include translation, width/height are layout size)
        if (cb.itemCount > 1) {
            // Left card — check its 1:1 square bounds
            if (tx >= leftCard.x && tx <= leftCard.x + leftCard.width &&
                ty >= leftCard.y && ty <= leftCard.y + leftCard.height) {
                val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
                cb.onSwitchTo(prev)
                refreshCards()
                return
            }
            // Right card
            if (tx >= rightCard.x && tx <= rightCard.x + rightCard.width &&
                ty >= rightCard.y && ty <= rightCard.y + rightCard.height) {
                val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0
                cb.onSwitchTo(next)
                refreshCards()
                return
            }
        }

        // Center card — 1:1 square, full screen only when tapped inside this square
        if (tx >= centerCard.x && tx <= centerCard.x + centerCard.width &&
            ty >= centerCard.y && ty <= centerCard.y + centerCard.height) {
            cb.onFullScreen()
        }
    }

    // ── Animations ──────────────────────────────────────────────────

    private fun animateSwitchLeft() {
        centerCard.animate()
            .translationX(baseCenterX - width.toFloat())
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .rotation(0f)
            .setDuration(250)
            .withEndAction {
                val cb = callback ?: return@withEndAction
                val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0
                cb.onSwitchTo(next)
                resetCardsInstant()
                refreshCards()
            }
            .start()

        rightCard.animate()
            .translationX(baseCenterX)
            .translationY(baseCenterY)
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun animateSwitchRight() {
        centerCard.animate()
            .translationX(baseCenterX + width.toFloat())
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .rotation(0f)
            .setDuration(250)
            .withEndAction {
                val cb = callback ?: return@withEndAction
                val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
                cb.onSwitchTo(prev)
                resetCardsInstant()
                refreshCards()
            }
            .start()

        leftCard.animate()
            .translationX(baseCenterX)
            .translationY(baseCenterY)
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun animateLike() {
        val heartX = width * 0.85f  // approximate heart icon position (right side)
        val heartY = height * 0.6f

        centerCard.animate()
            .translationX(heartX)
            .translationY(heartY)
            .scaleX(0.3f).scaleY(0.3f)
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                callback?.onLike()
                resetCardsInstant()
                refreshCards()
            }
            .start()
    }

    private fun animateSkip() {
        centerCard.animate()
            .translationY(baseCenterY - height.toFloat())
            .scaleX(0.6f).scaleY(0.6f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                callback?.onSkip()
                resetCardsInstant()
                refreshCards()
            }
            .start()
    }

    private fun resetCards() {
        centerCard.animate()
            .translationX(baseCenterX).translationY(baseCenterY)
            .scaleX(1f).scaleY(1f)
            .rotation(0f)
            .alpha(1f)
            .setDuration(200)
            .start()

        leftCard.animate()
            .translationX(baseLeftX).translationY(baseCenterY)
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(200)
            .start()

        rightCard.animate()
            .translationX(baseRightX).translationY(baseCenterY)
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(200)
            .start()

        centerCard.bringToFront()
    }

    private fun resetCardsInstant() {
        centerCard.translationX = baseCenterX
        centerCard.translationY = baseCenterY
        centerCard.scaleX = 1f
        centerCard.scaleY = 1f
        centerCard.rotation = 0f
        centerCard.alpha = 1f

        leftCard.translationX = baseLeftX
        leftCard.translationY = baseCenterY
        leftCard.scaleX = 0.85f
        leftCard.scaleY = 0.85f
        leftCard.alpha = 0.6f

        rightCard.translationX = baseRightX
        rightCard.translationY = baseCenterY
        rightCard.scaleX = 0.85f
        rightCard.scaleY = 0.85f
        rightCard.alpha = 0.6f

        centerCard.bringToFront()
    }

    companion object {
        /** 卡片垂直偏移系数 — 负值表示卡片略高于布局垂直中心 */
    /** Shift cards below vertical center by this fraction of card size */
        private const val CARD_Y_BIAS = -0.08f
    }
}
