import { watch } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { describeModelError } from '@/stores/agentProviders'

/** Build text from typed results only; never display untrusted upstream secrets. */
export function providerTestNotification(event, t) {
  const result = event.result || {}
  const ok = result.ok === true
  const text = []
  if (ok) {
    const latency = result.latencyMs
    text.push(typeof latency === 'number' && Number.isFinite(latency) && latency >= 0
      ? t('agent.providers.testOk', { latency }) : t('agent.providers.testSucceeded'))
    if (Number.isInteger(result.embeddingDimension) && result.embeddingDimension > 0) {
      text.push(t('agent.providers.testEmbeddingDimension', { dimension: result.embeddingDimension }))
    }
  } else {
    const error = result.error || {}
    const description = describeModelError(error)
    text.push(description.key ? t(description.key) : t('agent.providers.testRequestFailed'))
    if (Number.isInteger(error.httpStatus) && error.httpStatus >= 100 && error.httpStatus <= 599) {
      text.push(`HTTP ${error.httpStatus}`)
    }
    if (typeof error.retryAfterSeconds === 'number' && Number.isFinite(error.retryAfterSeconds) && error.retryAfterSeconds > 0) {
      text.push(t('companion.session.retryAfter', { seconds: error.retryAfterSeconds }))
    }
  }
  return {
    title: t(ok ? 'agent.providers.testSuccessTitle' : 'agent.providers.testFailureTitle', { name: event.name }),
    message: text.join(' · '),
    type: ok ? 'success' : 'error',
    icon: ok ? CircleCheckFilled : CircleCloseFilled,
    customClass: `provider-test-notification provider-test-notification--${ok ? 'success' : 'error'}`,
    position: 'bottom-right',
    duration: 0,
    showClose: true
  }
}

/** Installed at App's root so navigating away cannot dispose pending feedback. */
export function installProviderTestFeedback(store, t, { notify = ElNotification, message = ElMessage } = {}) {
  const progress = new Map()
  const notifications = new Set()
  function clear() {
    for (const handle of progress.values()) handle?.close?.()
    progress.clear()
    for (const handle of [...notifications]) handle?.close?.()
    notifications.clear()
  }
  // Clear visible messages synchronously on logout or a replaced auth session.
  const stopEpoch = watch(() => store.testEpoch, clear, { flush: 'sync' })
  const stopEvents = watch(() => store.testEvents.length, () => {
    const events = store.takeTestEvents()
    for (const event of events) {
      if (event.phase === 'started') {
        // Skip a stale progress message when completion was already queued.
        if (!store.isTesting(event.providerId)) continue
        progress.set(event.taskId, message({
          type: 'info', message: t('agent.providers.testStarted', { name: event.name }),
          customClass: 'provider-test-progress', duration: 4000, showClose: true
        }))
      } else if (event.phase === 'finished') {
        progress.get(event.taskId)?.close?.()
        progress.delete(event.taskId)
        let handle
        handle = notify({ ...providerTestNotification(event, t), onClose: () => notifications.delete(handle) })
        if (handle) notifications.add(handle)
      }
    }
  }, { immediate: true, flush: 'post' })
  return () => { stopEvents(); stopEpoch(); clear() }
}
