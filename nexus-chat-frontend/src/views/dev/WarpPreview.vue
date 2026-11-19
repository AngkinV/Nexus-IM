<template>
  <!--
    Dev-only stage for the Creator's Eye warp (route: /dev/warp, never bundled
    for production). Lets the transition be reviewed without a backend or login.
  -->
  <div class="wp">
    <header class="wp-bar">
      <span class="wp-brand">Nexus</span>
      <span class="wp-crumb">Workspace › Creator's Eye warp</span>
      <span class="wp-state" data-testid="warp-phase">phase: {{ state.phase }}</span>
    </header>

    <main class="wp-main">
      <section class="wp-panel">
        <h1>造物主之眼 · 冥渊吞噬</h1>
        <p>
          进入 AI 会话前的全屏转场。凝视 0.82s → 眨眼坍缩与碎片喷射 → 1.8s 抵达酒馆。
          ESC 可随时中断。
        </p>
        <div class="wp-actions">
          <button class="wp-btn wp-btn--primary" type="button" data-testid="warp-trigger" @click="play()">
            👁 触发转场
          </button>
          <button class="wp-btn" type="button" data-testid="warp-trigger-named" @click="play('调酒师 · Nova')">
            自定义目的地
          </button>
        </div>
        <dl class="wp-log">
          <div><dt>entered</dt><dd data-testid="warp-entered">{{ entered }}</dd></div>
          <div><dt>cancelled</dt><dd data-testid="warp-cancelled">{{ cancelled }}</dd></div>
        </dl>
      </section>

      <section class="wp-feed" aria-hidden="true">
        <div v-for="n in 6" :key="n" class="wp-bubble" :class="{ 'is-mine': n % 3 === 0 }">
          <span></span><span></span>
        </div>
      </section>
    </main>

    <CreatorEyeWarp />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import CreatorEyeWarp from '@/components/warp/CreatorEyeWarp.vue'
import { useCreatorWarp } from '@/composables/useCreatorWarp'

const { state, trigger } = useCreatorWarp()
const entered = ref(0)
const cancelled = ref(0)

const play = (target) => {
  trigger({
    target,
    onEnter: () => { entered.value += 1 },
    onCancel: () => { cancelled.value += 1 }
  })
}
</script>

<style scoped>
.wp {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: #131316;
  color: #e4e1e6;
  font-family: 'Geist Sans', 'Geist', 'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif;
}

.wp-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  height: 3.5rem;
  padding: 0 1.5rem;
  border-bottom: 1px solid rgba(68, 71, 72, 0.3);
  background: #0e0e11;
  font-size: 0.8125rem;
}

.wp-brand { font-size: 1.125rem; font-weight: 600; color: #fff; }
.wp-crumb { color: #c4c7c8; }
.wp-state { margin-left: auto; font-family: ui-monospace, Menlo, monospace; color: #c4c7c8; }

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
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 0.75rem;
  background: #1b1b1e;
  align-self: start;
}

.wp-panel h1 { margin: 0 0 0.5rem; font-size: 1.25rem; font-weight: 600; color: #fff; }
.wp-panel p { margin: 0 0 1.25rem; font-size: 0.875rem; line-height: 1.5rem; color: #c4c7c8; }

.wp-actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }

.wp-btn {
  padding: 0.5rem 1rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 9999px;
  background: transparent;
  color: #e4e1e6;
  font: inherit;
  font-size: 0.8125rem;
  cursor: pointer;
}

.wp-btn:hover { border-color: rgba(255, 255, 255, 0.6); }
.wp-btn--primary { border-color: #fff; background: #fff; color: #09090b; font-weight: 700; }

.wp-log {
  display: flex;
  gap: 1.5rem;
  margin: 1.25rem 0 0;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  color: #c4c7c8;
}

.wp-log div { display: flex; gap: 0.5rem; }
.wp-log dd { margin: 0; color: #fff; }

.wp-feed { display: flex; flex-direction: column; gap: 1rem; min-width: 0; }

.wp-bubble {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  width: 60%;
  padding: 1rem;
  border: 1px solid rgba(68, 71, 72, 0.3);
  border-radius: 0.75rem;
  background: #1f1f22;
}

.wp-bubble.is-mine { align-self: flex-end; background: #fff; }
.wp-bubble span { height: 0.5rem; border-radius: 9999px; background: rgba(228, 225, 230, 0.25); }
.wp-bubble span:last-child { width: 60%; }
.wp-bubble.is-mine span { background: rgba(9, 9, 11, 0.25); }

@media (max-width: 768px) {
  .wp-main { grid-template-columns: minmax(0, 1fr); }
}
</style>
