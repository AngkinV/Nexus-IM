# 酒馆入场转场

现有全屏舞台、`useTavernEntrance` 和 `/dev/warp`；不新增页面、路由或 store。画面全部程序生成，原 WebGL2 揉纸 shader / `crumpler.draw` 保留。

## 时间线（统一 scene transport，重复观看不自动加速）

| 秒 | 内容 |
|---|---|
| 0–.25 | 原 PAL 深/浅纸面进入；.25 commit 酒馆 |
| .25–.72 | 墨滴落下，落点限制在画面内部 |
| .72–1.35 | 短湿墨团与局部柔和晕染 |
| 1.35–4.4 | 原画线稿、原灰山逐渐显现；局部墨团消退 |
| 4.4–5.1 | 原灰山停留 |
| 5.1–6.3 | 原灰山向 HEAD 原全彩渐渗 |
| 6.3–6.8 | 原全彩成画落定；上传相同最终画 |
| 6.8–8.7 | 原 WebGL 揉纸；无 WebGL 时原画淡出 |

点击最多扰动墨滴 ±40 CSS px；不平移山、不重新生成峰线。最终无墨团覆盖，恢复原 PAL 纸面、原山与倒影。

## 原画与预算

HEAD 的 PAL、U=1536/RN/LH、LAY/STACK/MIST、buildRidge、bakeBody/bakeFar、buildStrokes、ribbon/strokes、bakeMist、composeLayer、buildPano、silk/hair/RIP、renderWater 与原 WebGL createCrumpler 恢复。原 paint 仅更名 originalPaint，内部单参数 smooth 更名 originalSmooth 避免和时序模块冲突；不改变绘画公式或最终调色。

过渡包装层只把同一 pano 的 ink/colour 按时间混合，再交给原 painter；到 6.3 秒直接使用原 colour。原绘画时间固定在 2.65，以便 seek、最终贴图和揉纸交接拿到同一张画（倒影公式仍为原 renderWater）。原尺寸、构图与完整纹理保留。准备使用 3.5ms 分片目标、尺寸/主题缓存及提前揉纸预热。tavernEntranceGrowth.js 仅保留 timing/clock/origin；错误的低分辨率生成器与密度测试已删除。

**预算是调度目标，不是已测到的耗时保证。** WebGL shader 编译、mesh/texture 上传仍为不可分的驱动调用；`maxSliceMs`、`finalizeMs`、`uploadMs` 暴露实际耗时，需主线程实测。缓存占用也需在目标设备检查。

统一时钟排除准备等待与后台暂停；前台长帧不再 `min(.05)` 慢放。renderGovernor 暂停事件立即 pause 音乐，恢复后的首帧重置基准。主动等待准备最多 20 秒，失败/resize 不计完整首看，优雅进入酒馆；resize 当前选择退出并重新预热，而非强行保留旧尺寸画面。

## 首次、声音与交接

首次不可点击/Enter/Space/Esc 跳过，这些按键阻止默认行为和底层快捷键。之后保留屏幕、按键、真实可访问的跳过按钮；Tab 将焦点留在该按钮，按钮不在 aria-hidden 祖先中。来电仍可 `cancel(true)`。

只有自然 `onDone('done')` 才记 seen；账号 key 在 enter 时冻结。失败、resize、skip/cancel、卸载与 preview seek 不记。没有舞台或 reduced-motion 直接进入也不记首看完成。

`Main.vue` 仅把真实 `settings.soundEnabled` 传给舞台。Scene 使用独立音乐接口：prepare 预热、真实 pointer/key gesture unlock、start(1)、每次 transport 绘制 tick(T)、等待/后台 pause/resume、结束/跳过 stop、卸载 dispose。未使用或更改全局 uiSound context。配乐文件和配乐专属测试本次未改。

## 主线程验收入口（无 sleep）

`/dev/warp` 安装 `window.__tavernEntrancePreview`：

```js
await window.__tavernEntrancePreview.prepare() // boolean，真实准备 promise
window.__tavernEntrancePreview.ready
window.__tavernEntrancePreview.diagnostics()
await window.__tavernEntrancePreview.seek(.2)
await window.__tavernEntrancePreview.seek(.65)
await window.__tavernEntrancePreview.seek(1.1)
await window.__tavernEntrancePreview.seek(2.4)
await window.__tavernEntrancePreview.seek(3.8)
await window.__tavernEntrancePreview.seek(4.7)
await window.__tavernEntrancePreview.seek(5.7)
await window.__tavernEntrancePreview.seek(6.5)
await window.__tavernEntrancePreview.seek(7.6)
```

`?t=.2` / `?t=.65` 也等待准备后精确定格，不把时间强制提到 .5。seek 不触发 destination commit，不记 seen。点击画面解除冻结。`play()` 走真实 composable，不模拟完成。

diagnostics 包含 status / error / seconds / phase / suspended / waiting / artReady / paperReady / frozen / webgl / soundEnabled / coldStarts / warmStarts / prepareMs / slices / maxSliceMs / finalizeMs / uploadMs。聚焦后分别测冷启动、prepare 后暖启动、后台暂停与最终 WebGL 纸团；`suspended:true` 时等待不是绘画 Bug。

## 串行验证命令（本执行通道未运行）

```sh
node --test src/composables/useTavernEntrance.test.mjs
node --test src/components/warp/tavernEntranceGrowth.test.mjs
node --test src/components/warp/tavernEntranceScene.test.mjs
npx vite build
```

相邻测试覆盖 phase/clock/origin、账号冻结、cold/warm ready、精确 seek、无 WebGL 完整时间线、scene 调用音乐生命周期。Canvas mocks 不是视觉验收或性能证据；主线程仍须实看各阶段、真实 WebGL 交接，并在同一受控聚焦视口对照原 baseline。
