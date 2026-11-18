/**
 * Copy for the Creator's Eye warp, scoped to the overlay component so the
 * transition stays self-contained. `{target}` is the destination name.
 */
export default {
  zh: {
    dialogLabel: '造物主之眼 · 冥渊吞噬转场',
    hudLabel: 'ABYSS_BREACH // 地狱视界撕裂',
    exit: 'ESC 中断深渊凝视',
    observer: 'OBSERVER',
    resolution: 'RESOLUTION',
    resolutionValue: 'FULLSCREEN_UNBOUND',
    targetLabel: 'TARGET',
    targetValue: '酒馆角色次元 · {target}',
    leap: '深渊跃迁',
    defaultTarget: '艾瑞斯 (Iris)',
    defaultTargetShort: '艾瑞斯',
    gaze: {
      phase: 'ABYSS_BREACH // 地狱视界撕裂 • 归来的造物主之眼',
      title: 'ABYSS_BREACH // 地狱视界撕裂 • 归来的造物主之眼',
      subtitle: 'INFERNAL_OCULAR // 无界全屏凝视 • 引力坍缩内卷粒子流',
      step: 'STAGE 1: 冥渊视界撕裂',
      observer: 'INFERNAL_OCULAR // 凝视压迫感已载入'
    },
    collapse: {
      phase: 'SINGULARITY // 眨眼即深渊吞噬 • 锚定「寂静酒馆 · {target}」',
      title: 'SINGULARITY // 深渊吞噬 • 二进制现实瓦解',
      subtitle: 'COLLAPSE // 眼裂合拢动量暴风喷射，多向代码残片撕裂现实矩阵...',
      step: 'STAGE 2: 全向碎片撕裂与深渊脉流吞噬',
      observer: 'ABYSSAL_SHRED // 深渊吞噬中'
    },
    arrived: {
      step: 'STAGE 3: 深渊吞噬完成',
      observer: 'ANCHORED // 角色次元就绪'
    },
    destination: {
      anchor: 'DIMENSIONAL_ANCHOR // BREACH_COMPLETE',
      title: '寂静酒馆 · {target}',
      desc: '现实界面已被造物主之眼彻底吞噬。雨夜吧台已全息构筑，{target}正静候您的下一次交互。',
      enter: '进入酒馆角色对话',
      back: '返回工作台'
    }
  },
  en: {
    dialogLabel: "The Creator's Eye · Abyssal Blink transition",
    hudLabel: 'ABYSS_BREACH // Horizon torn',
    exit: 'ESC Break the gaze',
    observer: 'OBSERVER',
    resolution: 'RESOLUTION',
    resolutionValue: 'FULLSCREEN_UNBOUND',
    targetLabel: 'TARGET',
    targetValue: 'Tavern dimension · {target}',
    leap: 'Abyssal leap',
    defaultTarget: '艾瑞斯 (Iris)',
    defaultTargetShort: '艾瑞斯',
    gaze: {
      phase: "ABYSS_BREACH // Horizon torn • The Creator's Eye returns",
      title: "ABYSS_BREACH // Horizon torn • The Creator's Eye returns",
      subtitle: 'INFERNAL_OCULAR // Unbound fullscreen gaze • Gravitational collapse stream',
      step: 'STAGE 1: Tearing the horizon',
      observer: 'INFERNAL_OCULAR // Gaze pressure loaded'
    },
    collapse: {
      phase: 'SINGULARITY // One blink, swallowed • Anchoring "The Silent Tavern · {target}"',
      title: 'SINGULARITY // Abyssal devour • Binary reality dissolves',
      subtitle: 'COLLAPSE // The lids close; code fragments burst outward and shred the reality matrix...',
      step: 'STAGE 2: Omnidirectional shred and abyssal devour',
      observer: 'ABYSSAL_SHRED // Devouring'
    },
    arrived: {
      step: 'STAGE 3: Devour complete',
      observer: 'ANCHORED // Character dimension ready'
    },
    destination: {
      anchor: 'DIMENSIONAL_ANCHOR // BREACH_COMPLETE',
      title: 'The Silent Tavern · {target}',
      desc: "The Creator's Eye has swallowed the interface. The rain-night bar is holographically built, and {target} is waiting for your next move.",
      enter: 'Enter the tavern chat',
      back: 'Back to workspace'
    }
  }
}
