# 播放页设计改造

## 概述

重新设计 Now Playing 页面。五个区域：顶部栏、封面区域（全向手势引擎）、歌曲信息区、进度条区域、播放控制区。

---

## 一、顶部栏

| 位置 | 组件 | 说明 |
|------|------|------|
| 左侧 | ImageButton | chevron_down，收起/返回 |
| 中间 | TextView | 歌单名称，白色 #FFFFFF，16-18sp，font-weight 500 |
| 右侧 | ImageButton | more_vert，三点菜单入口 |

数据：`MusicController` 新增 `playlistName: StateFlow<String>`

---

## 二、封面区域 — StackedDeckView

### 视觉

- 正方形封面，圆角 20dp，区域占屏幕约 55% 高度
- 背景色 #080810
- 卡牌栈：当前封面居中 scale 1.0，左右露出相邻封面边缘（scale 0.85，alpha 0.6，偏移 80dp）

### 全向手势

```
         ↑ 90°  向上猛滑 → 不喜欢（跳过）
         │
← 180°──┼── 0°→  左右 → 切换曲目
         │
          ╲ 315-330° → 快速收藏
```

| 手势 | 角度 | 动作 |
|------|------|------|
| 左/右滑 | 水平 ±25° | 切换上/下一首 |
| 向上猛滑 | 60°-120° | 不喜欢：卡片向上飞出+渐隐，skip next |
| 向右下滑 | 300°-345° | 收藏：卡片缩放飞向爱心位置 |

### 动画

- **左右切换**：跟手 + 弹性回弹，~250ms，新卡片滑入 scale 0.85→1.0
- **向上飞出**：scale 缩小 + translationY 负值 + alpha→0，~300ms
- **右下收藏**：沿贝塞尔曲线缩放至爱心图标，scale→0.3 + alpha→0，~400ms，爱心放大+填充反馈

### 点击

- 当前封面 → 全屏查看
- 露出封面 → 切换对应方向

---

## 三、歌曲信息区

| 位置 | 组件 | 样式 |
|------|------|------|
| 左，第一行 | tv_player_title | #FFFFFF，22sp，font-weight 600 |
| 左，第二行 | tv_player_artist | #CCCCCC，13sp |
| 右 | ll_player_actions | ♡ 爱心 + ＋ 加入歌单，灰白色线框 |

封面下方 marginTop 20dp，左右 padding 16dp。

**封面与歌曲信息区之间的 20dp 空白**：可点击，点击后打开歌词页。

歌词触发范围仅限此处空白，不包括歌曲信息区、进度条、控制栏、以及控制栏与底部之间的区域。

---

## 四、进度条区域

| 属性 | 值 |
|------|-----|
| 高度 | 3dp，圆角 1.5dp |
| progressTint | #FFFFFF |
| progressBackgroundTint | #2A2A3A |
| thumb 默认 | 白色圆点，8dp |
| thumb 按下 | 放大至 12dp，~150ms |
| 时间文字 | #999999，11sp |

SeekBar 可拖拽，左右 margin 0dp 齐平。

---

## 五、播放控制区

五个按钮水平均等分布，左右 padding 24dp，底边距 40dp：

| 按钮 | 图标 | 样式 |
|------|------|------|
| 随机 🔀 | ic_shuffle | #AAAAAA，22dp |
| 上一首 ⏮ | ic_media_previous | #AAAAAA，22dp |
| 播放/暂停 ▶⏸ | ic_media_play/pause | 白色圆形背景 56dp，深色图标，elevation 4dp |
| 下一首 ⏭ | ic_media_next | #AAAAAA，22dp |
| 循环 🔁 | ic_repeat | #AAAAAA，22dp |

---

## 六、新增资源

```xml
<!-- colors.xml -->
<color name="deck_background">#080810</color>
<color name="seekbar_bg">#2A2A3A</color>
<color name="text_time">#999999</color>
<color name="control_inactive">#AAAAAA</color>

<!-- dimens.xml -->
<dimen name="seekbar_height">3dp</dimen>
<dimen name="seekbar_thumb_default">8dp</dimen>
<dimen name="seekbar_thumb_pressed">12dp</dimen>
<dimen name="text_time">11sp</dimen>
<dimen name="player_cover_radius">20dp</dimen>
<dimen name="deck_peek_offset">80dp</dimen>
<dimen name="player_play_button_size">56dp</dimen>
<dimen name="player_small_icon">22dp</dimen>
```

---

## 七、文件清单

| 文件 | 操作 |
|------|------|
| `res/layout/fragment_player.xml` | 重写 |
| `res/drawable/bg_play_button.xml` | 新增 |
| `res/drawable/thumb_seekbar.xml` | 修改 |
| `res/drawable/ic_repeat.xml` | 新增 |
| `res/drawable/ic_shuffle.xml` | 新增 |
| `res/values/colors.xml` | 修改 |
| `res/values/dimens.xml` | 修改 |
| `ui/player/StackedDeckView.kt` | 新增 |
| `ui/player/PlayerFragment.kt` | 重写 |
| `player/MusicController.kt` | 修改 |

---

## 八、风险

- StackedDeckView 自定义手势引擎是核心复杂度所在
- "不喜欢"当前无后端支持，暂时跳过歌曲+动画
- 循环/随机模式需要新增状态管理
