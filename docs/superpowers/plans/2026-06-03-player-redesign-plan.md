# 播放页设计改造 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重新设计 Now Playing 页面：顶部栏（歌单名+返回+菜单）、StackedDeckView 全向手势封面区、歌曲信息区、进度条、五按钮控制栏，封面空白区可进入歌词页。

**Architecture:** 自定义 StackedDeckView（FrameLayout 子类）管理 3 张封面卡牌，通过 VelocityTracker 判断手势方向驱动三种动画（切换/不喜欢/收藏）。PlayerFragment 全面重写以适配新布局，MusicController 新增 playlistName 和播放模式状态。

**Tech Stack:** Kotlin, Android View (XML + ViewBinding), ConstraintLayout, VelocityTracker, ObjectAnimator, Glide

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `res/values/colors.xml` | 新增颜色值 |
| `res/values/dimens.xml` | 新增尺寸值 |
| `res/drawable/bg_play_button.xml` | 播放按钮圆形白色背景+阴影 |
| `res/drawable/thumb_seekbar.xml` | 修改：selector 双层圆点（默认/按下） |
| `res/drawable/ic_repeat.xml` | 循环图标 |
| `res/drawable/ic_shuffle.xml` | 随机播放图标 |
| `res/drawable/ic_chevron_down.xml` | 向下箭头图标 |
| `res/drawable/ic_more_vert.xml` | 三点菜单图标 |
| `player/MusicController.kt` | 新增 playlistName、shuffle/repeat 模式状态流 |
| `ui/player/StackedDeckView.kt` | 自定义手势引擎 ViewGroup（卡牌栈+全向手势） |
| `res/layout/fragment_player.xml` | 重写：新布局 |
| `ui/player/PlayerFragment.kt` | 重写：适配新布局和手势 |

---

### Task 1: 资源文件 — colors.xml + dimens.xml

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/dimens.xml`

- [ ] **Step 1: 在 colors.xml 末尾添加新颜色**

在 `</resources>` 前添加：

```xml
    <!-- Deck & SeekBar -->
    <color name="deck_background">#FF080810</color>
    <color name="seekbar_bg">#FF2A2A3A</color>
    <color name="text_time">#FF999999</color>
    <color name="control_inactive">#FFAAAAAA</color>
```

- [ ] **Step 2: 在 dimens.xml 中添加/调整尺寸**

根据现有 grid 系统，`grid_5` 应该就是 40dp（8dp × 5）。将播放页相关尺寸替换为：

```xml
    <!-- Now Playing redesign -->
    <dimen name="player_album_art_size">260dp</dimen>
    <dimen name="player_play_button_size">56dp</dimen>
    <dimen name="player_small_button_size">48dp</dimen>
    <dimen name="text_player_title">22sp</dimen>
    <dimen name="text_player_artist">13sp</dimen>
    <dimen name="player_action_button_size">48dp</dimen>
    <dimen name="player_small_icon">22dp</dimen>

    <dimen name="seekbar_height">3dp</dimen>
    <dimen name="seekbar_thumb_default">8dp</dimen>
    <dimen name="seekbar_thumb_pressed">12dp</dimen>
    <dimen name="text_time">11sp</dimen>
    <dimen name="player_cover_radius">20dp</dimen>
    <dimen name="deck_peek_offset">80dp</dimen>
```

- [ ] **Step 3: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 2: Drawable 资源 — 图标和样式

**Files:**
- Create: `app/src/main/res/drawable/ic_chevron_down.xml`
- Create: `app/src/main/res/drawable/ic_more_vert.xml`
- Create: `app/src/main/res/drawable/ic_repeat.xml`
- Create: `app/src/main/res/drawable/ic_shuffle.xml`
- Create: `app/src/main/res/drawable/bg_play_button.xml`
- Modify: `app/src/main/res/drawable/thumb_seekbar.xml`

- [ ] **Step 1: 创建 ic_chevron_down.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z" />
</vector>
```

- [ ] **Step 2: 创建 ic_more_vert.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" />
</vector>
```

- [ ] **Step 3: 创建 ic_repeat.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z" />
</vector>
```

- [ ] **Step 4: 创建 ic_shuffle.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.42 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z" />
</vector>
```

- [ ] **Step 5: 创建 bg_play_button.xml（白色圆形 + 阴影）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Shadow: offset slightly, dark translucent -->
    <item
        android:left="2dp"
        android:top="4dp"
        android:right="2dp"
        android:bottom="0dp">
        <shape android:shape="oval">
            <solid android:color="#40000000" />
        </shape>
    </item>
    <!-- White circle -->
    <item
        android:left="0dp"
        android:top="0dp"
        android:right="0dp"
        android:bottom="4dp">
        <shape android:shape="oval">
            <solid android:color="@color/white" />
        </shape>
    </item>
</layer-list>
```

- [ ] **Step 6: 修改 thumb_seekbar.xml 为 selector（默认/按下两种尺寸）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape android:shape="oval">
            <solid android:color="@color/button_white" />
            <size android:width="12dp" android:height="12dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="oval">
            <solid android:color="@color/button_white" />
            <size android:width="8dp" android:height="8dp" />
        </shape>
    </item>
</selector>
```

- [ ] **Step 7: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 3: MusicController — 新增状态

**Files:**
- Modify: `app/src/main/java/com/example/tuneplay/player/MusicController.kt`

- [ ] **Step 1: 添加 playlistName、shuffle/repeat 模式状态流**

在现有 `currentNeteaseId` 声明之后添加：

```kotlin
    // Playlist name (for Now Playing title bar)
    private val _playlistName = MutableStateFlow("")
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    // Shuffle / Repeat mode
    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(0) // 0=off, 1=repeat all, 2=repeat one
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
```

- [ ] **Step 2: 在 play() 方法中设置 playlistName**

在 `play()` 方法的 `_currentSong.value = song` 之后添加（playlistName 保持或由外部设置，这里不修改 play()，而是提供一个新方法）：

在 `getCurrentSong()` 之后添加：

```kotlin
    fun setPlaylistName(name: String) {
        _playlistName.value = name
    }

    fun toggleShuffle() {
        _shuffleMode.value = !_shuffleMode.value
    }

    fun toggleRepeat() {
        _repeatMode.value = (_repeatMode.value + 1) % 3 // 0→1→2→0
    }
```

- [ ] **Step 3: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 4: StackedDeckView — 基础卡牌栈显示

**Files:**
- Create: `app/src/main/java/com/example/tuneplay/ui/player/StackedDeckView.kt`

这是一个自定义 FrameLayout，管理 3 张封面 ImageView，显示卡牌栈效果。

- [ ] **Step 1: 创建 StackedDeckView 基础框架**

```kotlin
package com.example.tuneplay.ui.player

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import com.example.tuneplay.R

class StackedDeckView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Callback for deck actions. Implemented by the hosting Fragment.
     */
    interface Callback {
        /** Current song index (from the adapter data set). */
        val currentIndex: Int
        /** Total songs in the current queue. */
        val itemCount: Int
        /** Load cover art for a song at [index] into [imageView]. */
        fun loadCover(index: Int, imageView: ImageView)
        /** Switched to song at [index]. */
        fun onSwitchTo(index: Int)
        /** Like the current song. */
        fun onLike()
        /** Skip (dislike) the current song. */
        fun onSkip()
        /** Open full-screen cover view for the current song. */
        fun onFullScreen()
        /** Open lyrics page. (handled by fragment) */
    }

    var callback: Callback? = null

    // Three card views: left (previous), center (current), right (next)
    private val leftCard: ImageView
    private val centerCard: ImageView
    private val rightCard: ImageView

    // Cover radius in px
    private val coverRadius: Float

    init {
        // Background
        setBackgroundColor(0xFF080810.toInt())

        val density = resources.displayMetrics.density
        coverRadius = 20f * density

        leftCard = createCardView()
        centerCard = createCardView()
        rightCard = createCardView()

        addView(leftCard)
        addView(centerCard)
        addView(rightCard)

        // Default positions will be set in onLayout
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

    /**
     * Layout cards in their default positions.
     * Call this after size is known.
     */
    fun layoutCards() {
        if (width == 0 || height == 0) return

        val cardSize = calculateCardSize()
        val peekOffset = (80f * resources.displayMetrics.density).toInt()

        // Center card — full size, centered
        centerCard.updateLayoutParams<LayoutParams> {
            width = cardSize
            height = cardSize
        }
        centerCard.x = (width - cardSize) / 2f
        centerCard.y = (height - cardSize) / 2f
        centerCard.scaleX = 1f
        centerCard.scaleY = 1f
        centerCard.alpha = 1f

        // Left card — scaled down, right-offset to peek
        leftCard.updateLayoutParams<LayoutParams> {
            width = cardSize
            height = cardSize
        }
        leftCard.x = (width - cardSize) / 2f - peekOffset
        leftCard.y = (height - cardSize) / 2f
        leftCard.scaleX = 0.85f
        leftCard.scaleY = 0.85f
        leftCard.alpha = 0.6f

        // Right card — scaled down, left-offset to peek
        rightCard.updateLayoutParams<LayoutParams> {
            width = cardSize
            height = cardSize
        }
        rightCard.x = (width - cardSize) / 2f + peekOffset
        rightCard.y = (height - cardSize) / 2f
        rightCard.scaleX = 0.85f
        rightCard.scaleY = 0.85f
        rightCard.alpha = 0.6f
    }

    /** Refresh cards from the callback. */
    fun refreshCards() {
        val cb = callback ?: return
        layoutCards()

        // Load covers
        if (cb.itemCount > 0) {
            val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
            val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0

            cb.loadCover(cb.currentIndex, centerCard)
            cb.loadCover(prev, leftCard)
            cb.loadCover(next, rightCard)

            leftCard.visibility = if (cb.itemCount > 1) VISIBLE else INVISIBLE
            rightCard.visibility = if (cb.itemCount > 1) VISIBLE else INVISIBLE
        }
    }

    private fun calculateCardSize(): Int {
        // Square card: use the full available height
        return height
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 5: StackedDeckView — 左右滑动手势

**Files:**
- Modify: `app/src/main/java/com/example/tuneplay/ui/player/StackedDeckView.kt`

在 Task 4 的框架基础上，添加触摸事件处理。

- [ ] **Step 1: 添加触摸追踪字段和 onTouchEvent**

在 init 块之后，layoutCards() 之前添加手势相关字段：

```kotlin
    // --- Gesture tracking ---
    private var downX = 0f
    private var downY = 0f
    private var trackingTouch = false
    private var swipeDelta = 0f
    private var velocityTracker: android.view.VelocityTracker? = null

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val flingThreshold = 800f // px/s
```

- [ ] **Step 2: 实现 onInterceptTouchEvent 和 onTouchEvent**

在 `calculateCardSize()` 之后添加：

```kotlin
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Always intercept — this view owns its entire area
        return true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (callback == null) return true

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingTouch = false
                swipeDelta = 0f
                velocityTracker = android.view.VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)

                if (!trackingTouch) {
                    // Only start tracking if we pass touchSlop
                    if (kotlin.math.max(absDx, absDy) > touchSlop) {
                        trackingTouch = true
                    }
                }

                if (trackingTouch) {
                    swipeDelta = dx
                    // Animate center card following the finger
                    val progress = (dx / (width * 0.5f)).coerceIn(-1f, 1f)
                    centerCard.translationX = dx
                    centerCard.rotation = progress * 5f // subtle tilt

                    // Adjust side cards: left becomes more visible on right swipe, etc.
                    val absProgress = Math.abs(progress)
                    leftCard.translationX = -80f * resources.displayMetrics.density + dx * 0.3f
                    leftCard.scaleX = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    leftCard.scaleY = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    leftCard.alpha = (0.6f + absProgress * 0.4f).coerceIn(0.6f, 1f)

                    rightCard.translationX = 80f * resources.displayMetrics.density + dx * 0.3f
                    rightCard.scaleX = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    rightCard.scaleY = (0.85f + absProgress * 0.15f).coerceIn(0.85f, 1f)
                    rightCard.alpha = (0.6f + absProgress * 0.4f).coerceIn(0.6f, 1f)
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000) // px/s
                }
                val velocityX = velocityTracker?.xVelocity ?: 0f
                val velocityY = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (!trackingTouch) {
                    // It was a tap
                    handleTap(downX, downY)
                    return true
                }

                // Determine gesture direction from velocity
                val dx = swipeDelta
                val dy = event.y - downY
                val angle = Math.toDegrees(Math.atan2(Math.abs(dy).toDouble(), Math.abs(dx).toDouble()))

                when {
                    // Horizontal swipe — switch track
                    Math.abs(dx) > width * 0.25f || Math.abs(velocityX) > flingThreshold -> {
                        if (dx > 0 && callback!!.currentIndex > 0) {
                            animateSwitchRight()
                        } else if (dx < 0 && callback!!.currentIndex < callback!!.itemCount - 1) {
                            animateSwitchLeft()
                        } else {
                            resetCards()
                        }
                    }
                    // Diagonal down-right → like
                    dx > 0 && dy > 0 && angle in 30.0..60.0 && Math.abs(velocityX) > flingThreshold * 0.5f -> {
                        animateLike()
                    }
                    // Upward fling → skip
                    dy < -width * 0.25f || (velocityY < -flingThreshold && Math.abs(dy) > width * 0.15f) -> {
                        animateSkip()
                    }
                    else -> {
                        resetCards()
                    }
                }

                trackingTouch = false
                swipeDelta = 0f
                return true
            }
        }
        return super.onTouchEvent(event)
    }
```

- [ ] **Step 3: 添加动画方法**

在 touch event 处理之后添加：

```kotlin
    private fun handleTap(x: Float, y: Float) {
        val cb = callback ?: return
        val cardSize = calculateCardSize()
        val centerX = (width - cardSize) / 2f
        val peekOffset = (80f * resources.displayMetrics.density).toInt()

        // Check if tap is on left card
        val leftRight = centerX - peekOffset
        if (x < leftRight && x > leftRight - cardSize * 0.85f && cb.itemCount > 1) {
            val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
            cb.onSwitchTo(prev)
            refreshCards()
            return
        }

        // Check if tap is on right card
        val rightLeft = centerX + peekOffset
        if (x > rightLeft && x < rightLeft + cardSize * 0.85f && cb.itemCount > 1) {
            val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0
            cb.onSwitchTo(next)
            refreshCards()
            return
        }

        // Tap on center card → full screen
        cb.onFullScreen()
    }

    private fun animateSwitchLeft() {
        centerCard.animate()
            .translationX(-width.toFloat())
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(250)
            .withEndAction {
                callback?.let { cb ->
                    val next = if (cb.currentIndex < cb.itemCount - 1) cb.currentIndex + 1 else 0
                    cb.onSwitchTo(next)
                }
                resetCardsInstant()
                refreshCards()
            }
            .start()

        // Right card becomes center
        val peekOffset = (80f * resources.displayMetrics.density).toInt()
        rightCard.animate()
            .translationX(-peekOffset.toFloat()) // move from +peek to center
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun animateSwitchRight() {
        centerCard.animate()
            .translationX(width.toFloat())
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(250)
            .withEndAction {
                callback?.let { cb ->
                    val prev = if (cb.currentIndex > 0) cb.currentIndex - 1 else cb.itemCount - 1
                    cb.onSwitchTo(prev)
                }
                resetCardsInstant()
                refreshCards()
            }
            .start()

        val peekOffset = (80f * resources.displayMetrics.density).toInt()
        leftCard.animate()
            .translationX(peekOffset.toFloat())
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private fun animateLike() {
        // Fly toward bottom-right (heart icon position)
        centerCard.animate()
            .translationX(width * 0.5f)
            .translationY(height * 0.5f)
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
            .translationY(-height.toFloat())
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
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f)
            .rotation(0f)
            .alpha(1f)
            .setDuration(200)
            .start()

        val peekOffset = (80f * resources.displayMetrics.density).toInt()
        leftCard.animate()
            .translationX(-peekOffset.toFloat()).translationY(0f)
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(200)
            .start()

        rightCard.animate()
            .translationX(peekOffset.toFloat()).translationY(0f)
            .scaleX(0.85f).scaleY(0.85f)
            .alpha(0.6f)
            .setDuration(200)
            .start()
    }

    private fun resetCardsInstant() {
        centerCard.translationX = 0f
        centerCard.translationY = 0f
        centerCard.scaleX = 1f
        centerCard.scaleY = 1f
        centerCard.rotation = 0f
        centerCard.alpha = 1f

        val peekOffset = (80f * resources.displayMetrics.density).toInt()
        leftCard.translationX = -peekOffset.toFloat()
        leftCard.translationY = 0f
        leftCard.scaleX = 0.85f
        leftCard.scaleY = 0.85f
        leftCard.alpha = 0.6f

        rightCard.translationX = peekOffset.toFloat()
        rightCard.translationY = 0f
        rightCard.scaleX = 0.85f
        rightCard.scaleY = 0.85f
        rightCard.alpha = 0.6f
    }
```

- [ ] **Step 4: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 6: fragment_player.xml — 重写布局

**Files:**
- Modify: `app/src/main/res/layout/fragment_player.xml`

完全替换现有布局。

- [ ] **Step 1: 写入新布局**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/root_player"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/dark_background"
    tools:context=".ui.player.PlayerFragment">

    <!-- ===== TOP BAR ===== -->
    <ImageButton
        android:id="@+id/btn_player_back"
        android:layout_width="@dimen/player_action_button_size"
        android:layout_height="@dimen/player_action_button_size"
        android:layout_marginStart="@dimen/grid_2"
        android:layout_marginTop="@dimen/grid_1"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Back"
        android:src="@drawable/ic_chevron_down"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/tv_playlist_name"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:ellipsize="end"
        android:gravity="center"
        android:maxLines="1"
        android:textColor="@color/text_primary"
        android:textSize="@dimen/text_title"
        android:textStyle="normal"
        android:fontFamily="sans-serif-medium"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_back"
        app:layout_constraintEnd_toStartOf="@+id/btn_player_more"
        app:layout_constraintStart_toEndOf="@+id/btn_player_back"
        app:layout_constraintTop_toTopOf="@+id/btn_player_back"
        tools:text="My Playlist" />

    <ImageButton
        android:id="@+id/btn_player_more"
        android:layout_width="@dimen/player_action_button_size"
        android:layout_height="@dimen/player_action_button_size"
        android:layout_marginEnd="@dimen/grid_2"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Menu"
        android:src="@drawable/ic_more_vert"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_back"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="@+id/btn_player_back" />

    <!-- ===== STACKED DECK (55% height) ===== -->
    <com.example.tuneplay.ui.player.StackedDeckView
        android:id="@+id/deck_view"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/btn_player_back"
        app:layout_constraintHeight_percent="0.55" />

    <!-- ===== LYRICS TRIGGER: blank area between deck and song info ===== -->
    <View
        android:id="@+id/view_lyrics_trigger"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:background="@android:color/transparent"
        android:clickable="true"
        android:focusable="true"
        android:foreground="?attr/selectableItemBackground"
        app:layout_constraintBottom_toTopOf="@+id/tv_player_title"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/deck_view" />

    <!-- ===== SONG INFO ===== -->
    <TextView
        android:id="@+id/tv_player_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/grid_2"
        android:layout_marginTop="20dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="@color/text_primary"
        android:textSize="@dimen/text_player_title"
        android:fontFamily="sans-serif-medium"
        app:layout_constraintEnd_toStartOf="@+id/ll_player_actions"
        app:layout_constraintStart_toStartOf="parent"
        tools:text="Song Title" />

    <TextView
        android:id="@+id/tv_player_artist"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/grid_2"
        android:layout_marginTop="2dp"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="#CCCCCC"
        android:textSize="@dimen/text_player_artist"
        app:layout_constraintEnd_toStartOf="@+id/ll_player_actions"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/tv_player_title"
        tools:text="Artist Name" />

    <LinearLayout
        android:id="@+id/ll_player_actions"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="@dimen/grid_2"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        app:layout_constraintBottom_toBottomOf="@+id/tv_player_artist"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="@+id/tv_player_title">

        <ImageButton
            android:id="@+id/btn_player_like"
            android:layout_width="@dimen/player_action_button_size"
            android:layout_height="@dimen/player_action_button_size"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Like"
            android:src="@drawable/ic_heart" />

        <ImageButton
            android:id="@+id/btn_player_add"
            android:layout_width="@dimen/player_action_button_size"
            android:layout_height="@dimen/player_action_button_size"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="Add to playlist"
            android:src="@drawable/ic_add" />
    </LinearLayout>

    <!-- ===== SEEKBAR ===== -->
    <TextView
        android:id="@+id/tv_player_current_time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="@dimen/grid_2"
        android:layout_marginTop="@dimen/grid_2"
        android:text="0:00"
        android:textColor="@color/text_time"
        android:textSize="@dimen/text_time"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/tv_player_artist" />

    <TextView
        android:id="@+id/tv_player_duration"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="@dimen/grid_2"
        android:text="0:00"
        android:textColor="@color/text_time"
        android:textSize="@dimen/text_time"
        app:layout_constraintBaseline_toBaselineOf="@+id/tv_player_current_time"
        app:layout_constraintEnd_toEndOf="parent" />

    <SeekBar
        android:id="@+id/seek_player"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="0dp"
        android:layout_height="@dimen/seekbar_height"
        android:layout_marginTop="4dp"
        android:max="1000"
        android:progressTint="@color/button_white"
        android:progressBackgroundTint="@color/seekbar_bg"
        android:thumb="@drawable/thumb_seekbar"
        android:splitTrack="false"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/tv_player_current_time" />

    <!-- ===== CONTROLS ===== -->
    <ImageButton
        android:id="@+id/btn_player_shuffle"
        android:layout_width="@dimen/player_small_button_size"
        android:layout_height="@dimen/player_small_button_size"
        android:layout_marginTop="@dimen/grid_4"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Shuffle"
        android:src="@drawable/ic_shuffle"
        android:tint="@color/control_inactive"
        app:layout_constraintEnd_toStartOf="@+id/btn_player_prev"
        app:layout_constraintHorizontal_chainStyle="spread_inside"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/seek_player" />

    <ImageButton
        android:id="@+id/btn_player_prev"
        android:layout_width="@dimen/player_small_button_size"
        android:layout_height="@dimen/player_small_button_size"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Previous"
        android:src="@android:drawable/ic_media_previous"
        android:tint="@color/control_inactive"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_shuffle"
        app:layout_constraintEnd_toStartOf="@+id/btn_player_play_pause"
        app:layout_constraintStart_toEndOf="@+id/btn_player_shuffle"
        app:layout_constraintTop_toTopOf="@+id/btn_player_shuffle" />

    <ImageButton
        android:id="@+id/btn_player_play_pause"
        android:layout_width="@dimen/player_play_button_size"
        android:layout_height="@dimen/player_play_button_size"
        android:layout_marginHorizontal="@dimen/grid_1"
        android:background="@drawable/bg_play_button"
        android:contentDescription="Play/Pause"
        android:elevation="4dp"
        android:src="@android:drawable/ic_media_play"
        android:tint="@color/on_button_white"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_shuffle"
        app:layout_constraintEnd_toStartOf="@+id/btn_player_next"
        app:layout_constraintStart_toEndOf="@+id/btn_player_prev"
        app:layout_constraintTop_toTopOf="@+id/btn_player_shuffle" />

    <ImageButton
        android:id="@+id/btn_player_next"
        android:layout_width="@dimen/player_small_button_size"
        android:layout_height="@dimen/player_small_button_size"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Next"
        android:src="@android:drawable/ic_media_next"
        android:tint="@color/control_inactive"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_shuffle"
        app:layout_constraintEnd_toStartOf="@+id/btn_player_repeat"
        app:layout_constraintStart_toEndOf="@+id/btn_player_play_pause"
        app:layout_constraintTop_toTopOf="@+id/btn_player_shuffle" />

    <ImageButton
        android:id="@+id/btn_player_repeat"
        android:layout_width="@dimen/player_small_button_size"
        android:layout_height="@dimen/player_small_button_size"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="Repeat"
        android:src="@drawable/ic_repeat"
        android:tint="@color/control_inactive"
        app:layout_constraintBottom_toBottomOf="@+id/btn_player_shuffle"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toEndOf="@+id/btn_player_next"
        app:layout_constraintTop_toTopOf="@+id/btn_player_shuffle" />

    <!-- Bottom spacer for controls margin -->
    <Space
        android:layout_width="0dp"
        android:layout_height="@dimen/grid_4"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@+id/btn_player_play_pause" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 7: PlayerFragment.kt — 重写

**Files:**
- Modify: `app/src/main/java/com/example/tuneplay/ui/player/PlayerFragment.kt`

- [ ] **Step 1: 写入新的 PlayerFragment**

```kotlin
package com.example.tuneplay.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.databinding.FragmentPlayerBinding
import com.example.tuneplay.player.MusicController
import com.example.tuneplay.ui.home.formatDuration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: MusicRepository
    private var currentSongId: Long = 0L
    private var isLiked = false
    private var isUserSeeking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao())

        setupDeckView()
        setupSeekBar()
        setupControls()
        setupActionButtons()
        setupLyricsTrigger()
        collectState()
    }

    private fun setupDeckView() {
        binding.deckView.callback = object : StackedDeckView.Callback {
            override val currentIndex: Int
                get() {
                    // Find current song index — simplified: use 0-based position in the implicit queue
                    val song = MusicController.getCurrentSong() ?: return 0
                    // We use a simple list of songs from the current playlist
                    return currentQueue().indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                }

            override val itemCount: Int
                get() = currentQueue().size

            override fun loadCover(index: Int, imageView: ImageView) {
                val songs = currentQueue()
                if (index in songs.indices) {
                    Glide.with(this@PlayerFragment)
                        .load(songs[index].coverArtPath)
                        .placeholder(R.drawable.ic_music_note)
                        .centerCrop()
                        .into(imageView)
                }
            }

            override fun onSwitchTo(index: Int) {
                val songs = currentQueue()
                if (index in songs.indices) {
                    MusicController.play(requireContext(), songs, index)
                }
            }

            override fun onLike() {
                lifecycleScope.launch {
                    val likedPlaylist = repository.getPlaylistByName(
                        getString(R.string.playlist_liked)
                    ) ?: return@launch
                    repository.toggleSongInPlaylist(likedPlaylist.id, currentSongId)
                    isLiked = true
                    updateLikeButton()
                }
            }

            override fun onSkip() {
                MusicController.skipNext(requireContext())
            }

            override fun onFullScreen() {
                // Existing full-screen dialog logic — reuse
                val song = MusicController.getCurrentSong() ?: return
                val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val imageView = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { dialog.dismiss() }
                }
                Glide.with(this@PlayerFragment)
                    .load(song.coverArtPath)
                    .placeholder(R.drawable.ic_music_note)
                    .into(imageView)
                dialog.setContentView(imageView)
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                dialog.show()
            }
        }
    }

    /** Simple in-memory queue cache. */
    private var _queue: List<com.example.tuneplay.data.local.entity.Song> = emptyList()

    private fun currentQueue(): List<com.example.tuneplay.data.local.entity.Song> {
        val current = MusicController.getCurrentSong()
        if (current != null && _queue.none { it.id == current.id }) {
            _queue = listOf(current)
        }
        return _queue
    }

    fun setQueue(songs: List<com.example.tuneplay.data.local.entity.Song>) {
        _queue = songs
        binding.deckView.refreshCards()
    }

    private fun setupSeekBar() {
        binding.seekPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvPlayerCurrentTime.text = formatDuration(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.progress?.let { progress ->
                    MusicController.seekTo(requireContext(), progress.toLong())
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnPlayerPrev.setOnClickListener { MusicController.skipPrevious(requireContext()) }
        binding.btnPlayerPlayPause.setOnClickListener { MusicController.togglePlayPause(requireContext()) }
        binding.btnPlayerNext.setOnClickListener { MusicController.skipNext(requireContext()) }

        binding.btnPlayerShuffle.setOnClickListener {
            MusicController.toggleShuffle()
        }

        binding.btnPlayerRepeat.setOnClickListener {
            MusicController.toggleRepeat()
        }
    }

    private fun setupActionButtons() {
        binding.btnPlayerLike.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val likedPlaylist = repository.getPlaylistByName(
                    getString(R.string.playlist_liked)
                ) ?: return@launch
                isLiked = repository.toggleSongInPlaylist(likedPlaylist.id, currentSongId)
                updateLikeButton()
            }
        }
        binding.btnPlayerAdd.setOnClickListener {
            if (currentSongId != 0L) {
                PlaylistSelectSheetFragment.newInstance(currentSongId)
                    .show(parentFragmentManager, "PlaylistSelectSheet")
            }
        }
    }

    private fun setupLyricsTrigger() {
        // Tap the blank area between deck and song info → open lyrics
        binding.viewLyricsTrigger.setOnClickListener {
            findNavController().navigate(R.id.action_playerFragment_to_lyricsFragment)
        }

        // Back button
        binding.btnPlayerBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun updateLikeButton() {
        binding.btnPlayerLike.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MusicController.currentSong.collect { song ->
                        if (song != null) {
                            binding.tvPlayerTitle.text = song.title
                            binding.tvPlayerArtist.text = song.artist

                            if (song.id != currentSongId) {
                                currentSongId = song.id
                                lifecycleScope.launch {
                                    val likedPlaylist = repository.getPlaylistByName(
                                        getString(R.string.playlist_liked)
                                    )
                                    if (likedPlaylist != null) {
                                        isLiked = repository.isSongInPlaylist(likedPlaylist.id, song.id)
                                        updateLikeButton()
                                    }
                                }
                                // Refresh deck cards on song change
                                binding.deckView.refreshCards()
                            }
                        }
                    }
                }
                launch {
                    MusicController.isPlaying.collect { playing ->
                        binding.btnPlayerPlayPause.setImageResource(
                            if (playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                }
                launch {
                    combine(
                        MusicController.position,
                        MusicController.duration
                    ) { pos, dur -> Pair(pos, dur) }.collect { (pos, dur) ->
                        if (!isUserSeeking) {
                            binding.tvPlayerCurrentTime.text = formatDuration(pos)
                            binding.tvPlayerDuration.text = formatDuration(dur)
                            if (dur > 0) {
                                binding.seekPlayer.max = dur.toInt()
                                binding.seekPlayer.progress = pos.toInt()
                            }
                        }
                    }
                }
                launch {
                    MusicController.playlistName.collect { name ->
                        binding.tvPlaylistName.text = name
                    }
                }
                launch {
                    MusicController.shuffleMode.collect { enabled ->
                        binding.btnPlayerShuffle.imageTintList =
                            if (enabled) android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                            else android.content.res.ColorStateList.valueOf(0xFFAAAAAA.toInt())
                    }
                }
                launch {
                    MusicController.repeatMode.collect { mode ->
                        binding.btnPlayerRepeat.imageTintList = when (mode) {
                            0 -> android.content.res.ColorStateList.valueOf(0xFFAAAAAA.toInt())
                            1 -> android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                            2 -> android.content.res.ColorStateList.valueOf(0xFF1E90FF.toInt()) // repeat one: blue
                            else -> android.content.res.ColorStateList.valueOf(0xFFAAAAAA.toInt())
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

---

### Task 8: 检查导航和歌词路由

**Files:**
- Check: `app/src/main/res/navigation/*.xml` (nav graph)
- May modify: nav graph XML

- [ ] **Step 1: 查看 nav graph**

```bash
find D:/TunePlay/app/src/main/res -name "*.xml" -path "*navigation*" 2>/dev/null
```

- [ ] **Step 2: 确认 PlayerFragment 到 LyricsFragment 的 action 存在**

如果 nav graph 中没有 `action_playerFragment_to_lyricsFragment`，需要新增。读取 nav graph 文件后根据实际情况添加：

```xml
<action
    android:id="@+id/action_playerFragment_to_lyricsFragment"
    app:destination="@id/lyricsFragment" />
```

- [ ] **Step 3: 验证编译**

```bash
cd D:/TunePlay && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

---

### Task 9: 集成验证 — 构建 APK

- [ ] **Step 1: 完整构建 debug APK**

```bash
cd D:/TunePlay && ./gradlew :app:assembleDebug 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

- [ ] **Step 2: 检查 APK 产出**

```bash
ls -la D:/TunePlay/app/build/outputs/apk/debug/*.apk
```
