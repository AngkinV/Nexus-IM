# 酒馆舞台驱动与验证

更新：2026-11-22。本次在 `global-damselfly` 工作区实现，未覆盖原仓库其他线程正在修改的资源回收代码，未新增页面、路由或 store。

## 数据与职责

真实链路仍为 Java 模型回复 → `CompanionStateTag` → SSE `state/delta` → companion store → `RoleChatView` → 舞台。

- `tavernModelProfile.js`：核对运行时 morph、注册表情及骨骼能力，只注册实际非零的受支持通道。
- `tavernExpression.js`：统一情绪、口型与眨眼，按眉/眼/嘴/元音预算合成。
- `tavernGaze.js`：只管理官方 lookAt 的目标，动作物件优先于对话、舞台内鼠标和微动，不另写眼骨。
- `tavernGestures.js`：手势准备、停留、退出、优先级、轮次去重及取消。
- `tavernCup.js`：掌心握点在 normalized/raw 手骨间标定；抓稳后刚性跟手，回到桌面才脱离。
- `tavernAtmosphere.js`：场景基底与限幅、慢速情绪光感；不改变天气或物件温度。
- `tavernEvents.js`：现有回复的本地路由元数据与中断判断，不增加模型协议。
- `tavernActor.js`：身体姿态、手臂 IK、手腕配方、手指、最终骨骼更新与道具定位。
- `tavernStage.js`：一个 WebGL 渲染器、房间资源、事件入口、暂停及缩减动效。

## 模型与表情边界

SpringSnow 是 VRM0：57 个原始 morph 槽位、14 个原始注册表情组。`EYE_Natural` 是零位移占位。此实现注册并使用 20 个支持的细分眉眼嘴通道，包括模型明确支持的左右眼笑意。缺少完整细分族的模型回退到已有预设，不强行套用 SpringSnow 配方。

- 不用 `HA_*` 当头发；它们主要属于口内/嘴周。头发仍由模型的 spring bones 驱动。
- 不使用 `SkinFung_L/R` 冒充左右嘴角；不宣称支持独立半笑。
- 细分模式不叠整脸情绪预设；同名 max 不是几何冲突防护。
- 眉、眼、嘴分别过渡；口型有总预算，情绪嘴形在说话时退让；闭眼优先于情绪眼形。
- 小幅左右眼差异只在对应真实通道都存在时启用；眨眼仍会压制这些通道。
- 不强制每轮回 `neutral=1`，不等连续两轮才换情绪。
- 当前口型是文字节奏 articulation，不是音频级唇同步。

## 手势、杯子与生命周期

保留九种手势：rest、chin、sip、wipe、wave、shrug、cover、cross、tuck。模型的结构化 gesture 优先；正文括号识别是保守辅助。同轮同名动作不会从 state 和旁白各播一次。

- wave 的手腕配方在 IK 之后真正写入手骨。
- 双手手势有明确部位占用；抱臂的肘部和腕部放在躯干前方，避免被衣服遮住。
- sip 先到桌面握点停留，再抬杯。持杯位置由最终 raw 手骨计算，不再对杯子独立插值。
- Cup 只有一个拥有者；收起或中断会清理短时动作，放杯过程中不会让下一动作抢走握杯手。
- 不可达目标通过 IK 限幅处理，不拉长骨架；程序化手指不等同于完整动作捕捉或碰撞仿真。
- 说话与杯口接近嘴部的阶段互斥。
- 历史 mood/motion 只恢复状态，不重播 nod 或手势。切角色、会话和停止回复时清除旧轮队列。
- 暂停仅保留必要的旁白边界以丢弃旧片段，不保留旧旁白内容或待播动作。
- 减少动效时关闭游离注视和连续模拟；恢复前先同步姿态并重置 spring 尾端。

## 四个房间

房间仅在 sceneKey 改变时重建，mood 只更新参数。

| 场景 | 保留的环境 | 增强 |
|---|---|---|
| rain-fireplace | 雨夜、壁炉 | 灰阶窗面、雨线裁切、局部火光 |
| late-rain | 深夜大雨 | 雨为主、室内低亮度、较少浮尘 |
| empty-bar | 开阔酒馆 | 留白、货架、安静台面 |
| closing-time | 打烊吊灯 | 小幅摆动与相应局部光源，无新增窗或壁炉 |

真实光源与线框透明度分别控制；没有全屏彩色情绪滤镜。没有新增书、蜡烛、默认热气、自动声音或镜头漂移。杯子底部的暗影是风格化接触辅助，不是物理 shadow map。

## Java 状态事件

Filter 在首个有效 state 解析完成时回调，保证状态先于其后的正文发出。同一 provider chunk 内也保持这一顺序。重复状态标记不改变已经发出的状态，保存的 state 与 SSE 来源一致。

协议仍为 mood / motion / doing / gesture，原有枚举、长度限制、partial 标记与旧历史格式保留。正文先于状态标记出现时，不倒放已经交付的正文。

**Java 改动需应用本工作区补丁并重启 Java 服务后才生效。Python 未修改，验证过程没有启停用户后端。**

## 2026-11-22 验证结果

所有测试、构建及渲染验证串行运行。

- 前端：350 项 Node 测试通过，包含真实 GLB POSITION 数据和实际 three-vrm expression bind 的测试。
- Java：78 项通过（StateTag 41、PromptBuilder 10、CompanionExchange 27）。
- 生产构建：通过；仍有既有 Vite CJS API 弃用及大 chunk 警告。
- i18n：en/zh 各 1260 个键，差集为空，本次未新增 UI 文案键。
- 真实 VRM：19 个本地可见、非无头的窗口检查完成，页面错误为 0，窗口结束立即关闭。
- 持杯握点误差为 0，杯口到标定嘴部锚点约 0.0007 场景单位；杯子能回到原桌面锚点。
- 挥手的实际手骨姿态发生变化，非只修改配方数据。
- 历史 nod 的头骨变化约 0.0028 rad，当前 nod 约 0.0336 rad；历史恢复没有启动一次性点头。
- 缩减动效前后帧计数不变、左右眼骨保持静止；恢复时 spring reset 次数增加。
- mood 切换不重建房间；四场景没有新增不属于其设定的雨、窗或壁炉。
- 切到无模型访客后正常显示 silhouette，模型纹理计数降到 0。

渲染检查使用明确标注的校准状态输入，没有创建测试角色、发送假对话或声称模型生成了这些状态。当前预览账号无角色会话，因此没有进行真实模型聊天的端到端联调。侧栏显示故障及未连接的 Chrome 没有被冒称验证成功，最后使用不抢焦点的独立窗口完成校准。

本次临时窗口、测试脚本、日志、浏览器 profile、截图和构建产物均在收尾时清理，仅保留实现、测试与本文档。

## 复测命令

```bash
# 前端，固定单文件并发，避免多个重型检查同时运行
cd nexus-chat-frontend
node --test --test-concurrency=1 $(find src -type f -name '*.test.mjs' | sort)
npx vite build

# 返回项目根目录后运行 Java 契约测试，不启动后台服务
cd ../nexus-chat-backend
MAVEN_OPTS='-Xmx768m' mvn -q \
  -Dtest=CompanionStateTagTest,CompanionPromptBuilderTest,CompanionExchangeTest \
  -DforkCount=1 -DreuseForks=true test
```

模型本体未修改。SpringSnow 自带元数据限制商用与再分发；正式随软件发布前仍需核对实际授权。
