<template>
  <!--
    Dev-only stage for the tavern entrance (route: /dev/warp, never bundled for
    production). Lets the transition be reviewed without a backend or login.
    `?t=3.2` freezes the picture at 3.2s; `?theme=light` starts on the light shell.
  -->
  <div class="wp" :class="{ 'is-light': light }">
    <header class="wp-bar">
      <span class="wp-brand">Nexus</span>
      <span class="wp-crumb">{{ inTavern ? '酒馆（示意）' : '工作台（示意）' }}</span>
      <span class="wp-state" data-testid="warp-phase">phase: {{ state.phase }}</span>
    </header>

    <main v-if="!inTavern" class="wp-main">
      <section class="wp-panel">
        <h1>酒馆入场转场</h1>
        <p>
          从点击处起稿，线稿、墨色、彩色依次铺开，随后整幅画揉成纸团丢开，露出酒馆。首次完整播放约
          4.6 秒，同一窗口内再次进入约 2.3 秒。画面盖住界面时（0.62 秒）自动切到酒馆，不需要确认。
        </p>
        <p>Esc：切换前取消，切换后跳过。点击、回车、空格：跳过。</p>
        <div class="wp-actions">
          <button class="wp-btn wp-btn--primary" type="button" data-testid="warp-trigger" @click="play">
            进入酒馆
          </button>
          <button class="wp-btn" type="button" data-testid="warp-theme" @click="light = !light">
            切换深色 / 浅色
          </button>
        </div>
        <dl class="wp-log">
          <div><dt>entered</dt><dd data-testid="warp-entered">{{ entered }}</dd></div>
          <div><dt>cancelled</dt><dd data-testid="warp-cancelled">{{ cancelled }}</dd></div>
          <div><dt>next</dt><dd data-testid="warp-next">{{ entered ? 'short' : 'full' }}</dd></div>
        </dl>
      </section>

      <section class="wp-feed" aria-hidden="true">
        <div v-for="n in 6" :key="n" class="wp-bubble" :class="{ 'is-mine': n % 3 === 0 }">
          <span></span><span></span>
        </div>
      </section>
    </main>

    <main v-else class="wp-tavern">
      <section class="wp-stage">角色舞台的位置</section>
      <button class="wp-btn" type="button" data-testid="warp-back" @click="inTavern = false">返回</button>
    </main>

    <TavernEntrance ref="stage" />
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import TavernEntrance from '@/components/warp/TavernEntrance.vue'
import { useTavernEntrance } from '@/composables/useTavernEntrance'

const { state, enter } = useTavernEntrance()
const route = useRoute()
const stage = ref(null)
const light = ref(route.query.theme === 'light')
const inTavern = ref(false)
const entered = ref(0)
const cancelled = ref(0)

const play = () => {
  enter({
    onCommit: () => {
      inTavern.value = true
      entered.value += 1
    },
    onCancel: () => { cancelled.value += 1 }
  })
}

onMounted(async () => {
  const at = Number(route.query.t)
  if (!Number.isFinite(at) || route.query.t == null) return
  await nextTick()
  stage.value?.seek(at, { x: 120 })
  // Past 0.62s the app behind the picture is already the tavern.
  if (at >= 0.62) inTavern.value = true
})
</script>

<style scoped>
.wp {
  --wp-bg: #131316;
  --wp-bar: #0e0e11;
  --wp-panel: #1b1b1e;
  --wp-line: rgba(68, 71, 72, 0.3);
  --wp-text: #e4e1e6;
  --wp-muted: #c4c7c8;
  --wp-strong: #fff;
  --wp-on-strong: #09090b;

  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--wp-bg);
  color: var(--wp-text);
  font-family: 'Geist Sans', 'Geist', 'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif;
}

.wp.is-light {
  --wp-bg: #f4f4f5;
  --wp-bar: #fafafa;
  --wp-panel: #fff;
  --wp-line: rgba(9, 9, 11, 0.12);
  --wp-text: #27272a;
  --wp-muted: #52525b;
  --wp-strong: #09090b;
  --wp-on-strong: #fff;
}

.wp-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  height: 3.5rem;
  padding: 0 1.5rem;
  border-bottom: 1px solid var(--wp-line);
  background: var(--wp-bar);
  font-size: 0.8125rem;
}

.wp-brand { font-size: 1.125rem; font-weight: 600; color: var(--wp-strong); }
.wp-crumb { color: var(--wp-muted); }
.wp-state { margin-left: auto; font-family: ui-monospace, Menlo, monospace; color: var(--wp-muted); }

.wp-main {
  display: grid;
  grid-template-columns: minmax(0, 22rem) minmax(0, 1fr);
  gap: 1.5rem;
  flex: 1;
  min-height: 0;
  padding: 1.5rem;
}

.wp-panel {
  padding: 1.5rem;
  border: 1px solid var(--wp-line);
  border-radius: 0.75rem;
  background: var(--wp-panel);
  align-self: start;
}

.wp-panel h1 { margin: 0 0 0.5rem; font-size: 1.25rem; font-weight: 600; color: var(--wp-strong); }
.wp-panel p { margin: 0 0 1rem; font-size: 0.875rem; line-height: 1.5rem; color: var(--wp-muted); }

.wp-actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }

.wp-btn {
  padding: 0.5rem 1rem;
  border: 1px solid var(--wp-line);
  border-radius: 9999px;
  background: transparent;
  color: var(--wp-text);
  font: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}

.wp-btn:hover { border-color: var(--wp-muted); }
.wp-btn--primary { border-color: var(--wp-strong); background: var(--wp-strong); color: var(--wp-on-strong); font-weight: 700; }

.wp-log {
  display: flex;
  gap: 1.5rem;
  margin: 1.25rem 0 0;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  color: var(--wp-muted);
}

.wp-log div { display: flex; gap: 0.5rem; }
.wp-log dd { margin: 0; color: var(--wp-strong); }

.wp-feed { display: flex; flex-direction: column; gap: 1rem; min-width: 0; }

.wp-bubble {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 60%;
  padding: 1rem;
  border: 1px solid var(--wp-line);
  border-radius: 0.75rem;
  background: var(--wp-panel);
}

.wp-bubble.is-mine { align-self: flex-end; background: var(--wp-strong); }
.wp-bubble span { height: 0.5rem; border-radius: 9999px; background: var(--wp-line); }
.wp-bubble span:last-child { width: 60%; }
.wp-bubble.is-mine span { background: var(--wp-muted); }

.wp-tavern {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 1rem;
  min-height: 0;
  padding: 1.5rem;
}

.wp-stage {
  display: flex;
  align-items: flex-end;
  width: 100%;
  height: 45%;
  padding: 1rem;
  border: 1px solid var(--wp-line);
  border-radius: 0.75rem;
  background: var(--wp-panel);
  font-size: 0.8125rem;
  color: var(--wp-muted);
}

@media (max-width: 768px) {
  .wp-main { grid-template-columns: minmax(0, 1fr); }
}
</style>
