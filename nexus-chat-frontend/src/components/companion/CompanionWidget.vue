<template>
  <div class="companion-widget" @click="emit('open')">
    <div class="avatar-ring">
      <el-avatar :size="48" :src="activeRole?.avatarUrl">
        {{ avatarFallback }}
      </el-avatar>
      <span class="pulse-dot"></span>
    </div>
    <div class="widget-info">
      <div class="widget-title">
        {{ activeRole?.name || $t('companion.defaultName') }}
      </div>
      <div class="widget-status">
        {{ status?.summary || $t('companion.defaultStatus') }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({
  activeRole: Object,
  status: Object
})

const emit = defineEmits(['open'])

const avatarFallback = computed(() => {
  const name = props.activeRole?.name || t('companion.defaultName')
  return name.slice(0, 1)
})
</script>

<style scoped>
.companion-widget {
  position: absolute;
  right: 24px;
  bottom: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid #e2e8f0;
  border-radius: 18px;
  padding: 10px 14px;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.12);
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  z-index: 30;
}

.companion-widget:hover {
  transform: translateY(-2px);
  box-shadow: 0 16px 32px rgba(15, 23, 42, 0.16);
}

.avatar-ring {
  position: relative;
}

.pulse-dot {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 10px;
  height: 10px;
  background: #14b8a6;
  border-radius: 999px;
  border: 2px solid #fff;
  animation: pulse 2s infinite;
}

.widget-info {
  display: flex;
  flex-direction: column;
  min-width: 120px;
}

.widget-title {
  font-weight: 600;
  color: #0f172a;
  font-size: 14px;
}

.widget-status {
  color: #64748b;
  font-size: 12px;
  margin-top: 2px;
  line-height: 1.3;
}

@keyframes pulse {
  0% { transform: scale(0.9); opacity: 0.6; }
  50% { transform: scale(1.1); opacity: 1; }
  100% { transform: scale(0.9); opacity: 0.6; }
}

[data-theme="dark"] .companion-widget {
  background: rgba(24, 27, 33, 0.92);
  border-color: #2a2f39;
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.4);
}

[data-theme="dark"] .widget-title {
  color: #e2e8f0;
}

[data-theme="dark"] .widget-status {
  color: #94a3b8;
}
</style>
