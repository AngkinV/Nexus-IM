# 酒馆入场转场

从工作台进入酒馆时播放：山水从点击处起稿，线稿、墨色、彩色依次铺开，随后整幅画揉成纸团丢开，露出酒馆。画面全部由程序生成，没有图片素材。

## 文件

| 文件 | 作用 |
|---|---|
| `src/composables/useTavernEntrance.js` | 转场状态机（全窗口单例）：`idle → playing → (leaving) → idle` |
| `src/composables/useTavernEntrance.test.mjs` | 状态机的单元测试（`node --test`） |
| `src/components/warp/TavernEntrance.vue` | 全屏舞台：两张画布、键盘与点击、深浅判断。Teleport 到 `<body>` |
| `src/components/warp/tavernEntranceScene.js` | 画面本身：Canvas 2D 画山水，WebGL2 把画揉成纸团 |
| `src/views/dev/WarpPreview.vue` | 仅开发环境的预览页 `/dev/warp`，不需要登录和后端；生产构建不打包 |

## 用法

```js
import { useTavernEntrance } from '@/composables/useTavernEntrance'

const entrance = useTavernEntrance()
entrance.enter({
  onCommit: () => { /* 画面盖住界面时自动执行：真正切到酒馆 */ },
  onCancel: () => { /* 可选：切换之前按了 Esc */ }
})
```

页面上挂一个 `<TavernEntrance />` 即可。没有挂载舞台、或系统开启「减少动态效果」时，`enter` 直接执行 `onCommit`，不播放画面。

没有确认步骤：`onCommit` 由舞台在 0.62 秒时自动触发。

## 时间线

首次进入完整播放 4.55 秒；同一窗口内再次进入以两倍速播放，约 2.3 秒。

| 秒 | 画面 |
|---|---|
| 0 | 线稿从点击处向外长出，界面退成纸色 |
| 0.62 | 画面已盖住界面，后面切到酒馆（`onCommit`）。到 0.75 秒之间画面有意静止，酒馆挂载造成的卡顿落在这里 |
| 0.75 | 墨色化开 |
| 1.3 | 彩色化开 |
| 2.35 | 整幅彩色 |
| 2.65 | 起皱，四边向后收拢，团成纸团 |
| 4.1 | 纸团丢向远处并淡出 |
| 4.55 | 结束 |

## 打断

| 操作 | 0.62 秒之前 | 0.62 秒之后 |
|---|---|---|
| Esc | 取消，留在原处（`onCancel`） | 画面淡出，停在酒馆 |
| 点击、回车、空格 | 立即切到酒馆，画面淡出 | 画面淡出，停在酒馆 |
| 窗口大小变化 | 同上 | 同上 |
| 来电 | 取消 | 画面淡出，停在酒馆 |

转场期间应用自己的快捷键不生效，键盘归舞台所有。

## 接入点

- `views/Main.vue`：挂载 `<TavernEntrance />`；`enterTavern(commit)` 包住 `setActiveView('companion')`。从酒馆外面进来才播放；已经在酒馆里时直接执行。来电时调用 `cancel()`。
- `router/index.js`：仅开发环境的 `/dev/warp` 路由。

## 实现要点

- **深浅**：开始时对界面上 5 个点实际绘制的背景色取样，取中位数。深色界面用暖白线起稿、玄墨配色；浅色界面用墨线、素绢配色。
- **起点**：0.6 秒内有过点击就从点击的横坐标起稿，否则（快捷键、命令面板）从屏幕中间。
- **烘焙**：山形与罩染在应用空闲时分片计算一次（挂载 1.5 秒后开始，每片不超过 6 毫秒），两套配色一起出，常驻内存。按窗口尺寸拼出的三张大图（线稿、墨色、彩色）在点击时才生成，转场结束 45 秒后释放。
- **揉纸**：把最后一帧彩色画面作为贴图交给 WebGL。纸面形变和法线都在着色器里按同一个函数计算，折痕来自三层大小不同的折面。
- **退化**：没有 WebGL2 时，彩色画面直接淡出；画布不可用时不播放，直接进酒馆。

## 预览

`/dev/warp` 用两个示意界面演示转场。`?t=3.2` 把画面定格在第 3.2 秒，`?theme=light` 用浅色界面。
