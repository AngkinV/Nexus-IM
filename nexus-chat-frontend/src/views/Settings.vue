<template>
  <!--
    Settings — full-window layout, no centred card.
    Header (48px) / left category rail (256px) / scrolling content.
    Every control here is wired to real behaviour; see docs/superpowers/plans/2026-11-21-settings-redesign.md
  -->
  <div class="settings-root">
    <!-- ── Header ───────────────────────────────────────────── -->
    <header class="settings-header">
      <div class="header-left">
        <!-- macOS draws its own traffic lights over the frameless window; this
             spacer keeps the back pill clear of them. Mirrors NavRail's 78px. -->
        <div v-if="isMac" class="traffic-spacer" :style="{ width: `${72 * 100 / settings.zoomPercent}px` }" aria-hidden="true"></div>
        <button type="button" class="back-pill" :title="t('common.back')" @click="goBack">
          <span class="material-symbols-outlined">arrow_back</span>
          <span>{{ t('common.back') }}</span>
          <kbd>ESC</kbd>
        </button>
      </div>

      <div class="header-center">
        <span class="brand-mark">N</span>
        <span class="brand-name">NEXUS-IM</span>
        <span class="crumb-sep">/</span>
        <span class="crumb-current">{{ t('settings.settings') }}</span>
        <span v-if="appVersion" class="version-chip">Desktop Core v{{ appVersion }}</span>
      </div>

      <div class="header-right">
        <div class="header-search">
          <span class="material-symbols-outlined">search</span>
          <input
            ref="searchInput"
            v-model="searchQuery"
            type="text"
            :placeholder="t('settings.searchPlaceholder')"
          />
          <kbd>{{ isMac ? '⌘K' : 'Ctrl K' }}</kbd>
        </div>
        <button
          v-if="settings.zoomPercent !== 100"
          type="button"
          class="zoom-chip"
          :disabled="!sessionVerified"
          :title="t('settings.zoomReset')"
          @click="settings.zoomPercent = 100"
        >
          <span class="material-symbols-outlined">zoom_out_map</span>
          <span>{{ settings.zoomPercent }}%</span>
        </button>
        <!-- Whether the account-wide settings have actually reached the server -->
        <button
          v-if="syncChip"
          type="button"
          class="sync-chip"
          :class="syncChip.tone"
          :disabled="!syncChip.retry"
          :title="syncChip.title"
          @click="settings.retrySync()"
        >
          <span class="material-symbols-outlined" :class="{ spinning: syncChip.spinning }">{{ syncChip.icon }}</span>
          <span>{{ syncChip.label }}</span>
        </button>
        <button type="button" class="reset-pill" :disabled="settings.busy || !settings.ready || !sessionVerified" :title="t('settings.resetHint')" @click="resetDefaults">
          <span class="material-symbols-outlined">history</span>
          <span>{{ t('settings.resetDefaults') }}</span>
        </button>
        <!-- The window is frameless: off macOS it has no native controls, so the
             settings screen must offer its own or the window cannot be closed. -->
        <div v-if="isElectron && !isMac" class="window-controls">
          <button type="button" :title="t('topBar.minimize')" @click="windowAction('minimizeWindow')">
            <span class="material-symbols-outlined">remove</span>
          </button>
          <button type="button" :title="t('topBar.maximize')" @click="windowAction('maximizeWindow')">
            <span class="material-symbols-outlined">crop_square</span>
          </button>
          <button type="button" class="close" :title="t('topBar.close')" @click="windowAction('closeWindow')">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
      </div>
    </header>

    <div v-if="privacyState === 'failed' && !sessionVerified" class="storage-notice" role="alert">
      <span class="material-symbols-outlined">verified_user</span>
      <p>{{ t('settings.sessionUnverified') }}</p>
      <button type="button" class="ghost-btn" @click="loadPrivacy">{{ t('common.retry') }}</button>
    </div>
    <!-- Storage problems are stated, never papered over -->
    <div v-if="settings.storageIssue === 'unreadable'" class="storage-notice" role="alert">
      <span class="material-symbols-outlined">lock_reset</span>
      <p>{{ t('settings.storageUnreadable') }}</p>
      <button type="button" class="ghost-btn" :disabled="!sessionVerified" @click="discardUnreadable">{{ t('settings.storageDiscard') }}</button>
    </div>
    <div v-else-if="settings.localSaveFailed" class="storage-notice" role="alert">
      <span class="material-symbols-outlined">save</span>
      <p>{{ t('settings.localSaveFailed') }}</p>
    </div>

    <div class="settings-body">
      <!-- ── Left category rail ─────────────────────────────── -->
      <aside class="settings-rail">
        <nav class="rail-nav">
          <div class="rail-caption">{{ t('settings.categories') }}</div>
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="rail-item"
            :class="{ active: activeSection === item.key }"
            @click="scrollTo(item.key)"
          >
            <span class="material-symbols-outlined">{{ item.icon }}</span>
            <span class="rail-label">{{ t(item.labelKey) }}</span>
            <span v-if="item.badge" class="rail-badge">{{ item.badge }}</span>
            <span v-if="item.key === 'about'" class="rail-dot"></span>
          </button>
        </nav>

        <div class="rail-footer">
          <div class="gpu-block">
            <div class="gpu-row">
              <span class="gpu-label">
                <span class="material-symbols-outlined">memory</span>
                {{ t('settings.appMemory') }}
              </span>
              <span class="gpu-value">{{ memoryText }}</span>
            </div>
            <div class="gpu-bar" :title="memoryBarTitle"><div class="gpu-bar-fill" :style="{ width: memoryBarWidth }"></div></div>
            <div class="gpu-foot">
              <span>{{ t('settings.webglEngine') }}</span>
              <span :class="['gpu-state', { on: gpuStatus.webglAccelerated }]">{{ accelerationText(gpuStatus.webglAccelerated) }}</span>
            </div>
          </div>

          <button type="button" class="logout-btn" @click="handleLogout">
            <span class="material-symbols-outlined">power_settings_new</span>
            <span>{{ t('settings.logoutCurrent') }}</span>
          </button>
        </div>
      </aside>

      <!-- ── Content ────────────────────────────────────────── -->
      <main
        ref="contentEl"
        class="settings-content prism-scroll"
        @scroll="onScroll"
        @wheel.passive="releasePin"
        @touchmove.passive="releasePin"
        @pointerdown="releasePin"
        @keydown="releasePin"
      >
        <fieldset class="settings-fields" :disabled="settings.busy || !settings.ready || !sessionVerified" :inert="settings.busy || !settings.ready || !sessionVerified">
        <!-- Search filtered everything out: say so instead of showing a blank pane -->
        <div v-if="hasNoMatches" class="no-results">
          <span class="material-symbols-outlined">search_off</span>
          <p>{{ t('settings.noResults', { q: searchQuery.trim() }) }}</p>
          <button type="button" class="ghost-btn" @click="searchQuery = ''">
            {{ t('settings.clearSearch') }}
          </button>
        </div>

        <!-- 1. 通用与界面 -->
        <section v-show="sectionVisible('general')" id="sec-general" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">tune</span>
              <h2>{{ t('settings.sectionGeneral') }}</h2>
            </div>
            <span class="section-sub">General &amp; Liquid Interface</span>
          </div>

          <div class="card-grid cols-2">
            <!-- 语言 -->
            <div class="card" :class="{ filtered: !matches('language') }">
              <div class="card-head">
                <div class="card-heading">
                  <span class="material-symbols-outlined">language</span>
                  <div>
                    <h3>{{ t('settings.language') }}</h3>
                    <p>{{ t('settings.languageDesc') }}</p>
                  </div>
                </div>
              </div>
              <div class="card-body">
                <div class="select-wrap">
                  <select v-model="currentLocale" class="native-select" @change="handleLanguageChange">
                    <option v-for="opt in languageOptions" :key="opt.value" :value="opt.value">
                      {{ opt.label }}
                    </option>
                  </select>
                  <span class="material-symbols-outlined select-caret">unfold_more</span>
                </div>
              </div>
            </div>

            <!-- 时间格式 -->
            <div class="card" :class="{ filtered: !matches('hourCycle') }">
              <div class="card-head">
                <div class="card-heading">
                  <span class="material-symbols-outlined">schedule</span>
                  <div>
                    <h3>{{ t('settings.hourCycle') }}</h3>
                    <p>{{ t('settings.hourCycleDesc') }}</p>
                  </div>
                </div>
              </div>
              <div class="card-body">
                <div class="segmented">
                  <button
                    type="button"
                    :class="{ on: settings.hourCycle === '24' }"
                    @click="settings.hourCycle = '24'"
                  >
                    {{ t('settings.hour24') }}
                  </button>
                  <button
                    type="button"
                    :class="{ on: settings.hourCycle === '12' }"
                    @click="settings.hourCycle = '12'"
                  >
                    {{ t('settings.hour12') }}
                  </button>
                </div>
                <p class="preview">{{ t('settings.preview') }}: {{ timePreview }}</p>
              </div>
            </div>
            <!-- 超感毛玻璃透明度 -->
            <div class="card" :class="{ filtered: !matches('glass') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">blur_on</span>
                <div>
                  <h3>{{ t('settings.glassIntensity') }}</h3>
                  <p>{{ t('settings.glassIntensityDesc') }}</p>
                </div>
              </div>
              <div class="card-body">
                <div class="slider-row">
                  <input
                    v-model.number="settings.glassIntensity"
                    type="range"
                    min="0"
                    max="100"
                    step="5"
                    class="slider"
                  />
                  <span class="slider-value">{{ settings.glassIntensity }}%</span>
                </div>
                <p class="preview">{{ t('settings.glassHint') }}</p>
              </div>
            </div>

            <!-- 视窗缩放与字体微调 -->
            <div class="card" :class="{ filtered: !matches('zoom') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">zoom_in</span>
                <div>
                  <h3>{{ t('settings.zoom') }}</h3>
                  <p>{{ t('settings.zoomDesc') }}</p>
                </div>
              </div>
              <div class="card-body">
                <div class="slider-row">
                  <input
                    v-model.number="settings.zoomPercent"
                    type="range"
                    min="50"
                    max="200"
                    step="10"
                    class="slider"
                    :disabled="!isElectron"
                  />
                  <span class="slider-value">{{ settings.zoomPercent }}%</span>
                </div>
                <p v-if="!isElectron" class="preview">{{ t('settings.desktopOnly') }}</p>
                <button
                  v-if="settings.zoomPercent !== 100"
                  type="button"
                  class="ghost-btn reset-zoom"
                  @click="settings.zoomPercent = 100"
                >
                  <span class="material-symbols-outlined">restart_alt</span>
                  {{ t('settings.zoomReset') }}
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- 2. 桌面宠物与 3D -->
        <section v-show="sectionVisible('3dpet')" id="sec-3dpet" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">smart_toy</span>
              <h2>{{ t('settings.section3d') }}</h2>
            </div>
            <span class="section-sub">WebGL &amp; VRM Pipeline</span>
          </div>

          <div class="card-grid cols-1">
            <div class="card card-row" :class="{ filtered: !matches('desktopPet') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">smart_toy</span>
                <div>
                  <h3>
                    {{ t('settings.desktopPet') }}
                    <span class="chip">VRM / Live2D</span>
                    <span class="chip">Three.js</span>
                  </h3>
                  <p>{{ t('settings.desktopPetHint') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle"
                :class="{ on: settings.petVisible }"
                role="switch"
                :aria-checked="settings.petVisible"
                @click="settings.petVisible = !settings.petVisible"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>
          </div>

          <div v-show="matches('stats')" class="card-grid cols-3">
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.memoryUse') }}</span>
              <b class="mini-value">{{ gpuStatus.available ? gpuStatus.memoryMB + ' MB' : '—' }}</b>
              <span class="mini-note">{{ t('settings.memoryUseNote') }}</span>
            </div>
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.peakMemory') }}</span>
              <b class="mini-value">{{ gpuStatus.available ? gpuStatus.peakMemoryMB + ' MB' : '—' }}</b>
              <span class="mini-note">{{ t('settings.peakMemoryNote') }}</span>
            </div>
            <div class="mini-card">
              <span class="mini-label">{{ t('settings.acceleration') }}</span>
              <b class="mini-value">{{ accelerationText(gpuStatus.compositingAccelerated) }}</b>
              <span class="mini-note">{{ t('settings.accelerationNote') }}</span>
            </div>
          </div>

          <div class="card-grid cols-3">
            <!-- 渲染帧率上限 -->
            <div class="card" :class="{ filtered: !matches('fps') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">speed</span>
                <div>
                  <h3>{{ t('settings.fpsCap') }}</h3>
                  <p>{{ t('settings.fpsCapDesc') }}</p>
                </div>
              </div>
              <div class="card-body">
                <div class="segmented">
                  <button type="button" :class="{ on: settings.fpsCap === 30 }" @click="settings.fpsCap = 30">
                    {{ t('settings.fps30') }}
                  </button>
                  <button type="button" :class="{ on: settings.fpsCap === 60 }" @click="settings.fpsCap = 60">
                    {{ t('settings.fps60') }}
                  </button>
                </div>
              </div>
            </div>

            <!-- 视口渲染分辨率 -->
            <div class="card" :class="{ filtered: !matches('renderScale') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">aspect_ratio</span>
                <div>
                  <h3>{{ t('settings.renderScale') }}</h3>
                  <p>{{ t('settings.renderScaleDesc') }}</p>
                </div>
              </div>
              <div class="card-body">
                <div class="segmented three">
                  <button type="button" :class="{ on: settings.renderScale === '720p' }" @click="settings.renderScale = '720p'">720p</button>
                  <button type="button" :class="{ on: settings.renderScale === '1080p' }" @click="settings.renderScale = '1080p'">1080p</button>
                  <button type="button" :class="{ on: settings.renderScale === 'native' }" @click="settings.renderScale = 'native'">{{ t('settings.scaleNative') }}</button>
                </div>
                <!-- What the chosen tier does on this window, so a tier that changes nothing says so -->
                <p class="preview">{{ renderScaleNote }}</p>
              </div>
            </div>

            <!-- 后台失焦智能挂起 -->
            <div class="card card-row" :class="{ filtered: !matches('suspend') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">bedtime</span>
                <div>
                  <h3>{{ t('settings.suspendOnBlur') }}</h3>
                  <p>{{ t('settings.suspendOnBlurDesc') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle sm"
                :class="{ on: settings.suspendOnBlur }"
                role="switch"
                :aria-checked="settings.suspendOnBlur"
                @click="settings.suspendOnBlur = !settings.suspendOnBlur"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>
          </div>
        </section>

        <!-- 3. 消息与通知 -->
        <section v-show="sectionVisible('notifs')" id="sec-notifs" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">notifications_active</span>
              <h2>{{ t('settings.sectionNotifs') }}</h2>
            </div>
            <span class="section-sub">Desktop Notifications</span>
          </div>

          <div class="card-grid cols-2">
            <div class="card card-row" :class="{ filtered: !matches('notifications') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">desktop_windows</span>
                <div>
                  <h3>{{ t('settings.notifications') }}</h3>
                  <p>{{ t('settings.notificationsDesc') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle"
                :class="{ on: settings.notificationsEnabled }"
                role="switch"
                :aria-checked="settings.notificationsEnabled"
                :disabled="!isElectron"
                @click="settings.notificationsEnabled = !settings.notificationsEnabled"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>

            <div class="card card-row" :class="{ filtered: !matches('unread') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">mark_chat_unread</span>
                <div>
                  <h3>{{ t('settings.unreadBadge') }}</h3>
                  <p>{{ t('settings.unreadBadgeDesc') }}</p>
                </div>
              </div>
              <span class="value-note">{{ unreadTotal }}</span>
            </div>

            <!-- 程序坞/托盘角标 -->
            <div class="card card-row" :class="{ filtered: !matches('badge') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">apps</span>
                <div>
                  <h3>{{ t('settings.dockBadge') }}</h3>
                  <p>{{ t('settings.dockBadgeDesc') }}</p>
                </div>
              </div>
              <button
                type="button"
                class="toggle"
                :class="{ on: settings.badgeEnabled }"
                role="switch"
                :aria-checked="settings.badgeEnabled"
                :disabled="!isElectron"
                @click="settings.badgeEnabled = !settings.badgeEnabled"
              >
                <span class="toggle-knob"></span>
              </button>
            </div>

            <!-- 触感与极简音效 -->
            <div class="card" :class="{ filtered: !matches('sound') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">volume_up</span>
                <div>
                  <h3>{{ t('settings.uiSound') }}</h3>
                  <p>{{ t('settings.uiSoundDesc') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" @click="testSound">
                  <span class="material-symbols-outlined">play_arrow</span>
                  {{ t('settings.soundTest') }}
                </button>
                <button
                  type="button"
                  class="toggle"
                  :class="{ on: settings.soundEnabled }"
                  role="switch"
                  :aria-checked="settings.soundEnabled"
                  @click="settings.soundEnabled = !settings.soundEnabled"
                >
                  <span class="toggle-knob"></span>
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- 4. 角色与模型 / 隐私 -->
        <section v-show="sectionVisible('ai')" id="sec-ai" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">psychology</span>
              <h2>{{ t('settings.sectionAi') }}</h2>
            </div>
            <span class="section-sub">AI Roles &amp; Models</span>
          </div>

          <div class="card-grid cols-1">
            <!-- 角色与模型：内联展开 -->
            <div class="card" :class="{ filtered: !matches('role') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">psychology</span>
                <div>
                  <h3>{{ t('settings.roleAndModel') }}</h3>
                  <p>{{ roleSummary }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" @click="roleExpanded = !roleExpanded">
                  {{ roleExpanded ? t('common.collapse') : t('settings.switchPreset') }}
                  <span class="material-symbols-outlined">{{ roleExpanded ? 'expand_less' : 'chevron_right' }}</span>
                </button>
              </div>
              <div v-if="roleExpanded" class="inline-panel">
                <RoleSettingsPanel :key="userStore.currentUser?.id" />
              </div>
            </div>

          </div>
        </section>

        <!-- 5. 隐私安全与本地存储 -->
        <section v-show="sectionVisible('privacy')" id="sec-privacy" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">lock</span>
              <h2>{{ t('settings.navPrivacy') }}</h2>
            </div>
            <span class="section-sub">Privacy &amp; Local Storage</span>
          </div>

          <div class="card-grid cols-2">
            <!-- 隐私：内联展开 -->
            <div class="card" :class="{ filtered: !matches('privacy') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">enhanced_encryption</span>
                <div>
                  <h3>{{ t('profile.privacySettings') }}</h3>
                  <p>{{ t('settings.privacyDesc') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" @click="privacyExpanded = !privacyExpanded">
                  {{ privacyExpanded ? t('common.collapse') : t('common.expand') }}
                  <span class="material-symbols-outlined">{{ privacyExpanded ? 'expand_less' : 'chevron_right' }}</span>
                </button>
              </div>
              <div v-if="privacyExpanded" class="inline-panel">
                <!-- Nothing is editable until the server's values are on screen:
                     saving defaults over real settings would be a silent change. -->
                <p v-if="privacyState === 'loading'" class="preview">{{ t('common.loading') }}</p>
                <div v-else-if="privacyState === 'failed'" class="inline-notice">
                  <span>{{ t('settings.privacyLoadFailed') }}</span>
                  <button type="button" class="ghost-btn" @click="loadPrivacy">{{ t('common.retry') }}</button>
                </div>
                <fieldset v-else class="settings-fields" :disabled="savingPrivacy" :inert="savingPrivacy">
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showOnlineStatus') }}</h4>
                    <p>{{ t('profile.showOnlineStatusDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showOnlineStatus }"
                    role="switch"
                    :aria-checked="privacySettings.showOnlineStatus"
                    @click="privacySettings.showOnlineStatus = !privacySettings.showOnlineStatus"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showLastSeen') }}</h4>
                    <p>{{ t('profile.showLastSeenDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showLastSeen }"
                    role="switch"
                    :aria-checked="privacySettings.showLastSeen"
                    @click="privacySettings.showLastSeen = !privacySettings.showLastSeen"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showPhone') }}</h4>
                    <p>{{ t('profile.showPhoneDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showPhone }"
                    role="switch"
                    :aria-checked="privacySettings.showPhone"
                    @click="privacySettings.showPhone = !privacySettings.showPhone"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row">
                  <div class="privacy-info">
                    <h4>{{ t('profile.showEmail') }}</h4>
                    <p>{{ t('profile.showEmailDesc') }}</p>
                  </div>
                  <button
                    type="button"
                    class="toggle sm"
                    :class="{ on: privacySettings.showEmail }"
                    role="switch"
                    :aria-checked="privacySettings.showEmail"
                    @click="privacySettings.showEmail = !privacySettings.showEmail"
                  >
                    <span class="toggle-knob"></span>
                  </button>
                </div>
                <div class="privacy-row column">
                  <div class="privacy-info">
                    <h4>{{ t('privacy.friendRequestMode') }}</h4>
                    <p>{{ t('privacy.friendRequestModeDesc') }}</p>
                  </div>
                  <div class="segmented">
                    <button
                      type="button"
                      :class="{ on: privacySettings.friendRequestMode === 'DIRECT' }"
                      @click="privacySettings.friendRequestMode = 'DIRECT'"
                    >
                      {{ t('privacy.directMode') }}
                    </button>
                    <button
                      type="button"
                      :class="{ on: privacySettings.friendRequestMode === 'VERIFY' }"
                      @click="privacySettings.friendRequestMode = 'VERIFY'"
                    >
                      {{ t('privacy.verifyMode') }}
                    </button>
                  </div>
                </div>
                <div class="inline-actions">
                  <button type="button" class="primary-btn" :disabled="savingPrivacy || !privacyDirty" @click="savePrivacySettings">
                    {{ savingPrivacy ? t('common.saving') : t('common.save') }}
                  </button>
                </div>
                </fieldset>
              </div>
            </div>

            <!-- 清空缓存 -->
            <div class="card" :class="{ filtered: !matches('cache') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">cleaning_services</span>
                <div>
                  <h3>{{ t('settings.clearCache') }}</h3>
                  <p>{{ t('settings.clearCacheDesc') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <span class="cache-size">{{ cacheSizeText }}</span>
                <button
                  type="button"
                  class="ghost-btn"
                  :disabled="!isElectron || clearingCache"
                  @click="clearCacheNow"
                >
                  <span class="material-symbols-outlined" :class="{ spinning: clearingCache }">delete_sweep</span>
                  {{ clearingCache ? t('settings.clearing') : t('settings.clearCache') }}
                </button>
              </div>
            </div>

            <!-- 本地端加密存储 -->
            <div class="card" :class="{ filtered: !matches('encrypt') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">enhanced_encryption</span>
                <div>
                  <h3>{{ t('settings.encryptedStorage') }}</h3>
                  <p>{{ t('settings.encryptedStorageDesc') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <span class="cache-size">{{ settings.encryptedStorage ? 'AES-256-GCM' : t('settings.encryptionOff') }}</span>
                <button
                  type="button"
                  class="toggle"
                  :class="{ on: settings.encryptedStorage }"
                  role="switch"
                  :aria-checked="settings.encryptedStorage"
                  :disabled="!encryptionSupported || togglingEncryption || settings.storageIssue === 'unreadable'"
                  @click="toggleEncryption"
                >
                  <span class="toggle-knob"></span>
                </button>
              </div>
              <p class="preview">{{ t('settings.encryptionNote') }}</p>
            </div>
          </div>
        </section>

        <!-- 6. 快捷键 -->
        <section v-show="sectionVisible('shortcuts')" id="sec-shortcuts" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">keyboard</span>
              <h2>{{ t('settings.navShortcuts') }}</h2>
            </div>
            <span class="section-sub">Shortcuts</span>
          </div>

          <div class="card-grid cols-1">
            <!-- 快捷键：只读，列出真实键位 -->
            <div class="card" :class="{ filtered: !matches('shortcuts') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">keyboard</span>
                <div>
                  <h3>{{ t('settings.shortcuts') }}</h3>
                  <p>{{ shortcutPlatform }}</p>
                </div>
              </div>
              <div class="shortcut-list">
                <div v-for="sc in shortcuts" :key="sc.label" class="shortcut-row">
                  <span>{{ t(sc.label) }}</span>
                  <kbd>{{ sc.keys }}</kbd>
                </div>
              </div>
            </div>

          </div>
        </section>

        <!-- 7. 检查更新与关于 -->
        <section v-show="sectionVisible('about')" id="sec-about" class="settings-section">
          <div class="section-head">
            <div class="section-title">
              <span class="material-symbols-outlined">info</span>
              <h2>{{ t('settings.navAbout') }}</h2>
            </div>
            <span class="section-sub">Updates &amp; About</span>
          </div>

          <div class="card-grid cols-1">
            <!-- 版本与更新 -->
            <div class="card" :class="{ filtered: !matches('update') }">
              <div class="card-heading">
                <span class="material-symbols-outlined">verified</span>
                <div>
                  <h3>Nexus-IM Desktop Client</h3>
                  <p>{{ appVersion ? `v${appVersion}` : t('settings.webMode') }} · {{ t('settings.stableChannel') }}</p>
                </div>
              </div>
              <div class="card-foot">
                <button type="button" class="ghost-btn" :disabled="!isElectron || isCheckingUpdate" @click="checkForUpdates">
                  <span class="material-symbols-outlined" :class="{ spinning: isCheckingUpdate }">sync</span>
                  {{ isCheckingUpdate ? t('settings.checking') : t('settings.checkForUpdates') }}
                </button>
              </div>
              <div v-if="updateInfo" class="update-note">
                <template v-if="updateInfo.failed">{{ t('settings.checkUpdateFailed') }}</template>
                <template v-else-if="updateInfo.hasUpdate">{{ t('settings.updateAvailable') }} · {{ updateInfo.latestVersion }}</template>
                <template v-else>{{ t('settings.upToDate') }}</template>
              </div>
            </div>
          </div>
        </section>
        </fieldset>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { goBack as returnToSource, canGoBackOnEscape, hasBlockingOverlay } from '@/router/navigation'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useCompanionStore } from '@/stores/companion'
import { useSettingsStore } from '@/stores/settings'
import { userAPI } from '@/services/api'
import RoleSettingsPanel from '@/components/companion/RoleSettingsPanel.vue'
import { playMessageSound, isSoundSupported } from '@/utils/uiSound'
import { isEncryptionSupported } from '@/utils/secureStorage'
import { describeRenderScale } from '@/utils/renderGovernor'

const router = useRouter()
const { locale, t, te } = useI18n()
const userStore = useUserStore()
const chatStore = useChatStore()
const companionStore = useCompanionStore()
const settings = useSettingsStore()

const isElectron = computed(() => typeof window !== 'undefined' && !!window.electronAPI)
const isMac = computed(() => (typeof window !== 'undefined' && window.electronAPI?.platform) === 'darwin')

/* ── Header ─────────────────────────────────────────────── */
const appVersion = ref('')
const searchQuery = ref('')
const searchInput = ref(null)

const windowAction = (name) => {
  try {
    window.electronAPI?.[name]?.()
  } catch {
    /* the window is closing */
  }
}

/** Sync state of the three account-wide settings, as the user should read it. */
const syncChip = computed(() => {
  switch (settings.syncStatus) {
    case 'syncing':
      return { icon: 'sync', spinning: true, tone: '', retry: false, label: t('settings.syncSaving'), title: t('settings.syncHint') }
    case 'synced':
      return { icon: 'cloud_done', spinning: false, tone: '', retry: false, label: t('settings.syncSaved'), title: t('settings.syncHint') }
    case 'failed':
      return {
        icon: 'cloud_off',
        spinning: false,
        tone: 'warn',
        retry: true,
        label: t('settings.syncFailed'),
        title: t('settings.syncFailedHint', { n: settings.pendingSync })
      }
    default:
      return null
  }
})

/* ── Navigation: seven items, seven sections ────────────── */
const activeSection = ref('general')
const contentEl = ref(null)

const navItems = [
  { key: 'general', icon: 'tune', labelKey: 'settings.sectionGeneral' },
  { key: '3dpet', icon: 'smart_toy', labelKey: 'settings.nav3d', badge: '3D' },
  { key: 'notifs', icon: 'notifications_active', labelKey: 'settings.navNotifs' },
  { key: 'ai', icon: 'psychology', labelKey: 'settings.navAi' },
  { key: 'privacy', icon: 'lock', labelKey: 'settings.navPrivacy' },
  { key: 'shortcuts', icon: 'keyboard', labelKey: 'settings.navShortcuts', badge: '⌘/Ctrl' },
  { key: 'about', icon: 'info', labelKey: 'settings.navAbout' }
]

const sectionEl = (key) => document.getElementById(`sec-${key}`)
const isShown = (el) => !!el && el.offsetParent !== null

/**
 * The item the user clicked stays highlighted until they scroll by hand.
 *
 * A timer cannot do this job: the last sections are short, so after a click on
 * one of them the pane is simply at its end with several sections in view, and
 * any later scroll event would move the highlight to whichever one the
 * position-based rule prefers. Only a real wheel / touch / key / scrollbar
 * gesture releases the pin.
 */
let pinnedSection = null
const releasePin = () => {
  pinnedSection = null
}

const scrollTo = (key) => {
  const el = sectionEl(key)
  if (!isShown(el)) return // filtered out by the search: nothing to go to
  activeSection.value = key
  pinnedSection = key
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** Highlight the section currently in view. */
const onScroll = () => {
  const host = contentEl.value
  if (!host || pinnedSection) return
  const visible = navItems.filter((item) => isShown(sectionEl(item.key)))
  if (!visible.length) return
  // The last sections are too short to ever reach the top of the pane: once the
  // pane is scrolled to its end, the last one is the one being looked at.
  if (host.scrollHeight > host.clientHeight && host.scrollTop + host.clientHeight >= host.scrollHeight - 2) {
    activeSection.value = visible[visible.length - 1].key
    return
  }
  const top = host.getBoundingClientRect().top
  let current = visible[0].key
  for (const item of visible) {
    if (sectionEl(item.key).getBoundingClientRect().top - top <= 80) current = item.key
  }
  activeSection.value = current
}

/* ── Search: filters the cards ──────────────────────────── */
/**
 * Each card is searchable by its own visible title and description (in the
 * current language) plus a few aliases, and belongs to one section.
 */
const CARDS = {
  language: { section: 'general', text: ['settings.language', 'settings.languageDesc'], alias: ['language', '语言', 'locale'] },
  hourCycle: { section: 'general', text: ['settings.hourCycle', 'settings.hourCycleDesc'], alias: ['time', '时间', 'hour', '12', '24'] },
  glass: { section: 'general', text: ['settings.glassIntensity', 'settings.glassIntensityDesc'], alias: ['glass', 'blur', '毛玻璃', '模糊'] },
  zoom: { section: 'general', text: ['settings.zoom', 'settings.zoomDesc'], alias: ['zoom', '缩放'] },
  desktopPet: { section: '3dpet', text: ['settings.desktopPet', 'settings.desktopPetHint'], alias: ['pet', '宠物', 'vrm', '3d'] },
  stats: { section: '3dpet', text: ['settings.memoryUse', 'settings.peakMemory', 'settings.acceleration'], alias: ['memory', '内存', 'gpu', 'ram'] },
  fps: { section: '3dpet', text: ['settings.fpsCap', 'settings.fpsCapDesc'], alias: ['fps', 'frame', '帧率'] },
  renderScale: { section: '3dpet', text: ['settings.renderScale', 'settings.renderScaleDesc'], alias: ['resolution', 'render', '分辨率', '720p', '1080p'] },
  suspend: { section: '3dpet', text: ['settings.suspendOnBlur', 'settings.suspendOnBlurDesc'], alias: ['suspend', 'background', '挂起', '失焦', '后台'] },
  notifications: { section: 'notifs', text: ['settings.notifications', 'settings.notificationsDesc'], alias: ['notification', '通知'] },
  unread: { section: 'notifs', text: ['settings.unreadBadge', 'settings.unreadBadgeDesc'], alias: ['unread', '未读'] },
  badge: { section: 'notifs', text: ['settings.dockBadge', 'settings.dockBadgeDesc'], alias: ['badge', 'dock', '角标', '托盘'] },
  sound: { section: 'notifs', text: ['settings.uiSound', 'settings.uiSoundDesc'], alias: ['sound', 'audio', '音效', '提示音'] },
  role: { section: 'ai', text: ['settings.roleAndModel'], alias: ['role', 'model', '角色', '模型', 'motion', '动作'] },
  privacy: { section: 'privacy', text: ['profile.privacySettings', 'settings.privacyDesc'], alias: ['privacy', '隐私', 'friend', '好友'] },
  cache: { section: 'privacy', text: ['settings.clearCache', 'settings.clearCacheDesc'], alias: ['cache', 'clear', '缓存'] },
  encrypt: { section: 'privacy', text: ['settings.encryptedStorage', 'settings.encryptedStorageDesc'], alias: ['encrypt', 'aes', 'security', '加密', '安全'] },
  shortcuts: { section: 'shortcuts', text: ['settings.shortcuts'], alias: ['shortcut', 'keyboard', '快捷键', '键盘'] },
  update: { section: 'about', text: ['settings.checkForUpdates'], alias: ['update', 'version', 'about', '更新', '版本', '关于'] }
}

const query = computed(() => searchQuery.value.trim().toLowerCase())
watch(query, () => {
  releasePin()
  nextTick(onScroll)
})

/** Recomputed when the language changes, so searching in the visible language works. */
const haystacks = computed(() => {
  locale.value // dependency: translations below follow the active language
  const out = {}
  for (const [key, card] of Object.entries(CARDS)) {
    const words = [...card.alias, ...card.text.filter((k) => te(k)).map((k) => t(k))]
    out[key] = words.join('\n').toLowerCase()
  }
  return out
})

const matches = (key) => !query.value || (haystacks.value[key] || '').includes(query.value)

const sectionVisible = (section) =>
  !query.value || Object.entries(CARDS).some(([key, card]) => card.section === section && matches(key))

/** True when the query is non-empty and every card is filtered out. */
const hasNoMatches = computed(() => !!query.value && !Object.keys(CARDS).some((key) => matches(key)))

/* ── Language ───────────────────────────────────────────── */
const currentLocale = computed({ get: () => settings.locale, set: (value) => { settings.locale = value } })

const languageOptions = computed(() => [
  { value: 'zh', label: t('settings.langZh') },
  { value: 'en', label: t('settings.langEn') }
])

// App.vue persists the locale and updates <html lang> and Element Plus.
const handleLanguageChange = () => {
  // App.vue consumes settings.locale, including after account switches.
}

/* ── Hour cycle preview ─────────────────────────────────── */
const clockTick = ref(Date.now())
const timePreview = computed(() => settings.format(clockTick.value, 'HH:mm'))

/* ── Process memory / GPU features (real readings from Electron) ── */
const gpuStatus = reactive({
  available: false,
  memoryMB: 0,
  peakMemoryMB: 0,
  totalSystemMB: 0,
  webglAccelerated: false,
  compositingAccelerated: false
})

let statusTimer = null

const refreshGpu = async () => {
  if (!isElectron.value || !window.electronAPI.getGpuStatus) return
  try {
    const status = await window.electronAPI.getGpuStatus()
    if (status?.available) {
      Object.assign(gpuStatus, {
        available: true,
        memoryMB: Number(status.memoryMB) || 0,
        peakMemoryMB: Number(status.peakMemoryMB) || 0,
        totalSystemMB: Number(status.totalSystemMB) || 0,
        webglAccelerated: status.webglAccelerated === true,
        compositingAccelerated: status.compositingAccelerated === true
      })
    } else {
      gpuStatus.available = false
    }
  } catch {
    gpuStatus.available = false
  }
}

const memoryText = computed(() => (gpuStatus.available ? `${gpuStatus.memoryMB} MB` : '—'))

/** Share of the machine's installed RAM this app is using — a real denominator. */
const memoryBarWidth = computed(() => {
  if (!gpuStatus.available || !gpuStatus.totalSystemMB) return '0%'
  return `${Math.min(100, (gpuStatus.memoryMB / gpuStatus.totalSystemMB) * 100).toFixed(1)}%`
})

const memoryBarTitle = computed(() =>
  gpuStatus.available && gpuStatus.totalSystemMB
    ? t('settings.appMemoryOf', { used: gpuStatus.memoryMB, total: (gpuStatus.totalSystemMB / 1024).toFixed(0) })
    : ''
)

const accelerationText = (on) => {
  if (!isElectron.value || !gpuStatus.available) return '—'
  return on ? t('settings.accelOn') : t('settings.accelSoftware')
}

/* ── Render resolution: say what the tier does here ─────── */
const viewportTick = ref(0)
const onViewportResize = () => {
  viewportTick.value += 1
}

const renderScaleNote = computed(() => {
  viewportTick.value // recompute when the window is resized
  settings.renderScale // …or the tier changes
  settings.zoomPercent // …or the zoom changes the pixel height
  const info = describeRenderScale()
  if (info.mode === 'native') return t('settings.renderScaleNative', { h: info.physicalHeight })
  if (info.sameAsNative) return t('settings.renderScaleSame', { h: info.physicalHeight, mode: info.mode })
  return t('settings.renderScaleApplied', {
    h: info.physicalHeight,
    to: info.renderedHeight,
    pct: Math.round(info.factor * 100)
  })
})

/* ── Role / privacy inline panels ───────────────────────── */
const roleExpanded = ref(false)
const privacyExpanded = ref(false)
const savingPrivacy = ref(false)

const roleSummary = computed(() => {
  if (String(companionStore.rolesOwnerId) !== String(userStore.currentUser?.id)) return t('settings.roleManageHint')
  const role = companionStore.activeRole
  if (!role) return t('settings.roleEmpty')
  return `${role.name || t('companion.defaultName')} · ${t('settings.roleActive')}`
})

const PRIVACY_FIELDS = ['showOnlineStatus', 'showLastSeen', 'showPhone', 'showEmail', 'friendRequestMode']
/** 'loading' until the server's values are on screen; 'failed' if they could not be read. */
const privacyState = ref('loading')
const sessionVerified = ref(false)
const privacySettings = reactive({
  showOnlineStatus: true,
  showLastSeen: true,
  showPhone: false,
  showEmail: false,
  friendRequestMode: null
})
const privacyBaseline = ref(null)

const applyPrivacy = (source) => {
  privacySettings.showOnlineStatus = source.showOnlineStatus !== false
  privacySettings.showLastSeen = source.showLastSeen !== false
  privacySettings.showPhone = source.showPhone === true
  privacySettings.showEmail = source.showEmail === true
  const mode = source.friendRequestMode
  privacySettings.friendRequestMode = mode === 'DIRECT' || mode === 'VERIFY' ? mode : null
  privacyBaseline.value = { ...privacySettings }
}

const privacyDirty = computed(
  () => !!privacyBaseline.value && PRIVACY_FIELDS.some((key) => privacySettings[key] !== privacyBaseline.value[key])
)

let disposed = false
let privacySequence = 0

const loadPrivacy = async () => {
  const sequence = ++privacySequence
  privacyState.value = 'loading'
  sessionVerified.value = false
  const owner = userStore.currentUser?.id
  // loadUserProfile resolves to undefined when the request fails.
  const profile = owner ? await userStore.loadUserProfile() : null
  if (disposed || sequence !== privacySequence || userStore.currentUser?.id !== owner) return
  if (!profile) {
    privacyState.value = 'failed'
    return
  }
  sessionVerified.value = true
  if (!['showOnlineStatus', 'showLastSeen', 'showPhone', 'showEmail'].every((key) => typeof profile[key] === 'boolean') ||
      !['DIRECT', 'VERIFY'].includes(profile.friendRequestMode)) {
    privacyState.value = 'failed' // missing backend fields are not guessed as defaults
    return
  }
  applyPrivacy(profile)
  privacyState.value = 'ready'
}

const savePrivacySettings = async () => {
  if (privacyState.value !== 'ready' || savingPrivacy.value || !privacyDirty.value) return
  const owner = userStore.currentUser?.id
  const session = userStore.token
  if (!owner) return
  // Submit only actual edits, not stale copies of the other device's fields.
  const payload = {}
  for (const key of PRIVACY_FIELDS) {
    if (privacySettings[key] === privacyBaseline.value?.[key]) continue
    if (key === 'friendRequestMode' && !privacySettings[key]) continue
    payload[key] = privacySettings[key]
  }

  savingPrivacy.value = true
  try {
    const { data } = await userAPI.updatePrivacySettings(owner, payload)
    if (disposed || userStore.currentUser?.id !== owner || userStore.token !== session) return
    if (!data || !['showOnlineStatus', 'showLastSeen', 'showPhone', 'showEmail'].every((key) => typeof data[key] === 'boolean') || !['DIRECT', 'VERIFY'].includes(data.friendRequestMode)) {
      throw new Error('Invalid privacy acknowledgement')
    }
    userStore.updatePrivacySettings(data)
    // Show what the server stored, not what was sent.
    applyPrivacy(data)
    ElMessage.success(t('common.savedSuccess'))
  } catch {
    if (!disposed && userStore.currentUser?.id === owner) ElMessage.error(t('profile.updateFailed'))
  } finally {
    savingPrivacy.value = false
  }
}

/* ── Unread total: the chat store's own count ───────────── */
const unreadTotal = computed(() => String(Math.max(0, Number(chatStore.totalUnreadCount) || 0)))

/* ── Cache size / clearing ──────────────────────────────── */
const cacheBytes = ref(null)
const clearingCache = ref(false)

function formatBytes(bytes) {
  const n = Math.max(0, Number(bytes) || 0)
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

const cacheSizeText = computed(() => {
  if (!isElectron.value) return t('settings.desktopOnly')
  if (cacheBytes.value === null) return '—'
  return t('settings.cacheHttpSize', { size: formatBytes(cacheBytes.value) })
})

const refreshCacheSize = async () => {
  if (!isElectron.value || !window.electronAPI.getCacheInfo) return
  try {
    const info = await window.electronAPI.getCacheInfo()
    cacheBytes.value = info?.available ? Number(info.httpBytes) || 0 : null
  } catch {
    cacheBytes.value = null
  }
}

const clearCacheNow = async () => {
  if (!isElectron.value || clearingCache.value || !window.electronAPI.clearCache) return
  clearingCache.value = true
  try {
    const result = await window.electronAPI.clearCache()
    if (!result?.ok) throw new Error(result?.error || 'clear failed')
    cacheBytes.value = Number(result.remainingBytes) || 0
    if (result.storagesCleared === false) ElMessage.warning(t('settings.cacheClearedPartly'))
    else ElMessage.success(t('settings.cacheClearedFreed', { size: formatBytes(result.freedBytes) }))
  } catch {
    ElMessage.error(t('settings.cacheClearFailed'))
    await refreshCacheSize()
  } finally {
    clearingCache.value = false
  }
}

/* ── UI sound ───────────────────────────────────────────── */
const soundSupported = isSoundSupported()

function testSound() {
  if (!soundSupported) return
  playMessageSound()
}

/* ── Encrypted local storage ────────────────────────────── */
const encryptionSupported = isEncryptionSupported()
const togglingEncryption = ref(false)

/** The store rewrites this account's bucket, reads it back, and only then reports success. */
async function toggleEncryption() {
  if (togglingEncryption.value) return
  const next = !settings.encryptedStorage
  togglingEncryption.value = true
  try {
    const result = await settings.setEncryption(next)
    if (String(result.ownerId) !== String(userStore.currentUser?.id)) return
    ElMessage.success(next ? t('settings.encryptionOn') : t('settings.encryptionTurnedOff'))
  } catch {
    ElMessage.error(t('settings.encryptionFailed'))
  } finally {
    togglingEncryption.value = false
  }
}

async function discardUnreadable() {
  if (!sessionVerified.value) return
  const owner = userStore.currentUser?.id, session = userStore.token
  try {
    await ElMessageBox.confirm(t('settings.storageDiscardConfirm'), t('settings.storageDiscard'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch {
    return
  }
  if (!sessionVerified.value || userStore.currentUser?.id !== owner || userStore.token !== session) return
  settings.discardUnreadableStorage()
}

/* ── Shortcuts: the keys we actually bind ───────────────── */
const shortcutPlatform = computed(() => (isMac.value ? 'macOS' : 'Windows / Linux'))

const shortcuts = computed(() => [
  { label: 'settings.shortcutCommand', keys: isMac.value ? '⌘ K' : 'Ctrl K' },
  { label: 'settings.shortcutBack', keys: 'ESC' }
])

/* ── Update check ───────────────────────────────────────── */
const isCheckingUpdate = ref(false)
const updateInfo = ref(null)

const checkForUpdates = async () => {
  if (!isElectron.value || isCheckingUpdate.value) return
  isCheckingUpdate.value = true
  try {
    const result = await window.electronAPI.checkForUpdates()
    // The main process reports a failed request as { hasUpdate: false, error }.
    // That is "could not check", not "up to date".
    if (!result || result.error) throw new Error(result?.error || 'no result')
    updateInfo.value = { hasUpdate: result.hasUpdate === true, latestVersion: result.latestVersion || '' }
    if (!updateInfo.value.hasUpdate) ElMessage.success(t('settings.upToDate'))
  } catch {
    updateInfo.value = { failed: true }
    ElMessage.error(t('settings.checkUpdateFailed'))
  } finally {
    isCheckingUpdate.value = false
  }
}

/* ── Reset / logout / back ──────────────────────────────── */
const resetDefaults = async () => {
  if (!sessionVerified.value) return
  const owner = userStore.currentUser?.id, session = userStore.token
  try {
    await ElMessageBox.confirm(t('settings.resetConfirm'), t('settings.resetDefaults'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch {
    return
  }
  if (!sessionVerified.value || userStore.currentUser?.id !== owner || userStore.token !== session) return
  const keepLocale = settings.locale
  try {
    const { encryptionPreserved } = settings.resetToDefaults()
    settings.locale = keepLocale
    await settings.flushLocal()
    ElMessage.success(encryptionPreserved ? t('settings.resetDoneEncrypted') : t('settings.resetDone'))
  } catch {
    ElMessage.error(t('settings.localSaveFailed'))
  }
}

const handleLogout = async () => {
  await userStore.logout()
  router.replace('/login')
}

const goBack = () => returnToSource(router)

/* ── Keyboard: ESC back, ⌘K focus search ────────────────── */
const onKeydown = (event) => {
  if (event.defaultPrevented || event.isComposing || hasBlockingOverlay()) return
  const mod = event.metaKey || event.ctrlKey
  if (mod && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
    return
  }
  if (event.key === 'Escape' && document.activeElement === searchInput.value) {
    event.preventDefault()
    event.stopPropagation()
    if (searchQuery.value) searchQuery.value = ''
    else searchInput.value?.blur()
    return
  }
  if (canGoBackOnEscape(event)) {
    event.preventDefault()
    event.stopPropagation()
    goBack()
  }
}

watch(() => userStore.currentUser?.id, () => { if (!disposed) void loadPrivacy() })

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onViewportResize)
  statusTimer = setInterval(() => {
    clockTick.value = Date.now()
    refreshGpu()
  }, 5000)

  loadPrivacy()

  if (isElectron.value && window.electronAPI.getAppVersion) {
    try {
      appVersion.value = await window.electronAPI.getAppVersion()
    } catch {
      /* version chip simply stays hidden */
    }
  }

  await nextTick()
  await Promise.all([refreshGpu(), refreshCacheSize()])
})

onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onViewportResize)
  if (statusTimer) clearInterval(statusTimer)
})
</script>

<style scoped>
/* ── Root: full window, no centred card ───────────────────── */
.settings-fields { border: 0; margin: 0; padding: 0; min-width: 0; }
.settings-fields:disabled { opacity: .65; }

.settings-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100%;
  background: #131316;
  color: #e4e1e6;
  font-family: 'Geist Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  overflow: hidden;
}

/* ── Header ───────────────────────────────────────────────── */
.settings-header {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: rgba(13, 13, 17, 0.65);
  backdrop-filter: blur(calc(28px * var(--glass-blur-scale, 1))) saturate(180%);
  -webkit-backdrop-filter: blur(calc(28px * var(--glass-blur-scale, 1))) saturate(180%);
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  -webkit-app-region: drag;
  user-select: none;
  z-index: 30;
}

.settings-header button,
.settings-header input,
.settings-header .header-search {
  -webkit-app-region: no-drag;
}

/* On macOS the spacer must start at the window edge so the traffic lights sit
   exactly where the system puts them. */
.settings-header:has(.traffic-spacer) {
  padding-left: 0;
}

.header-left,
.header-center,
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Reserved for the macOS traffic lights, which float over this frameless
   window. The trailing hairline matches the reference design's divider. */
.traffic-spacer {
  /* Measured against the live window: the third light ends at x=65, so 72px puts
     the hairline a few pixels past it instead of floating in dead space. */
  width: 72px;
  height: 100%;
  flex-shrink: 0;
  border-right: 1px solid rgba(255, 255, 255, 0.1);
}

.back-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  color: #c4c7c8;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.09);
  box-shadow: inset 0 0.5px 0 rgba(255, 255, 255, 0.12);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.back-pill:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.back-pill .material-symbols-outlined {
  font-size: 16px;
}

.back-pill kbd {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 4px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(196, 199, 200, 0.8);
}

.brand-mark {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.brand-name {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #fff;
}

.crumb-sep {
  color: rgba(255, 255, 255, 0.2);
  font-size: 12px;
}

.crumb-current {
  font-size: 12px;
  font-weight: 500;
  color: #fff;
}

.version-chip {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(196, 199, 200, 0.7);
}

.header-search {
  position: relative;
  display: flex;
  align-items: center;
}

.header-search .material-symbols-outlined {
  position: absolute;
  left: 10px;
  font-size: 15px;
  color: var(--field-icon);
  pointer-events: none;
}

.header-search input {
  width: 156px;
  height: 28px;
  padding: 0 30px 0 30px;
  border-radius: var(--field-radius);
  font-size: 11.5px;
  color: var(--field-text);
  caret-color: #ffffff;
  background: var(--field-bg);
  box-shadow: 0 0 0 1px var(--field-ring) inset;
  outline: none;
  transition: box-shadow var(--field-ease);
}

.header-search input::placeholder {
  color: var(--field-placeholder);
}

.header-search input:focus {
  box-shadow: 0 0 0 1px var(--field-ring-focus) inset;
}

.header-search input:hover:not(:focus) {
  box-shadow: 0 0 0 1px var(--field-ring-hover) inset;
}

.header-search kbd {
  position: absolute;
  right: 10px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  color: var(--field-icon);
  pointer-events: none;
}

/* Always reachable escape hatch: at 200% zoom the card that owns the slider
   may be off-screen, so the header carries a one-click reset. */
.zoom-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10.5px;
  color: #fafafa;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.16);
  cursor: pointer;
  transition: background 0.15s;
}

.zoom-chip:hover {
  background: rgba(255, 255, 255, 0.16);
}

.zoom-chip .material-symbols-outlined {
  font-size: 16px;
}

.reset-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 500;
  color: #c4c7c8;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  cursor: pointer;
  transition: color 0.15s;
}

.reset-pill:hover {
  color: #fff;
}

.reset-pill .material-symbols-outlined {
  font-size: 16px;
}

/* ── Body: rail + content ─────────────────────────────────── */
.settings-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* ── Left rail ────────────────────────────────────────────── */
.settings-rail {
  width: 256px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background: #0e0e11;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}

.rail-nav {
  padding: 8px;
  overflow-y: auto;
}

.rail-caption {
  padding: 6px 12px 4px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(196, 199, 200, 0.45);
}

.rail-item {
  width: 100%;
  height: 32px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  color: #c4c7c8;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.rail-item:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.04);
}

.rail-item.active {
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.1);
}

.rail-item .material-symbols-outlined {
  font-size: 16px;
  flex-shrink: 0;
}

.rail-label {
  flex: 1;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-badge {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.8);
}

.rail-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.9);
  flex-shrink: 0;
}

/* ── Rail footer ──────────────────────────────────────────── */
.rail-footer {
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(14, 14, 17, 0.4);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gpu-block {
  padding: 8px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.gpu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
}

.gpu-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: rgba(196, 199, 200, 0.8);
}

.gpu-label .material-symbols-outlined {
  font-size: 16px;
}

.gpu-value {
  color: #fff;
  font-weight: 500;
}

.gpu-bar {
  width: 100%;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.gpu-bar-fill {
  height: 100%;
  background: #fff;
  transition: width 0.3s ease;
}

.gpu-foot {
  display: flex;
  justify-content: space-between;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  color: rgba(196, 199, 200, 0.6);
  padding-top: 2px;
}

.gpu-state {
  font-weight: 500;
  color: rgba(196, 199, 200, 0.6);
}

.gpu-state.on {
  color: #34d399;
}

.logout-btn {
  width: 100%;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  color: #fca5a5;
  background: rgba(220, 38, 38, 0.1);
  border: 1px solid rgba(248, 113, 113, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.logout-btn:hover {
  background: rgba(220, 38, 38, 0.16);
  color: #fecaca;
}

.logout-btn .material-symbols-outlined {
  font-size: 16px;
}

/* ── Content ──────────────────────────────────────────────── */
.settings-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 24px;
  background: #131316;
  scroll-behavior: smooth;
}

.settings-section {
  margin-bottom: 28px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  padding-bottom: 6px;
  margin-bottom: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title .material-symbols-outlined {
  font-size: 16px;
  color: #fff;
}

.section-title h2 {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  letter-spacing: -0.01em;
  margin: 0;
}

.section-sub {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  letter-spacing: 0.04em;
  color: rgba(196, 199, 200, 0.7);
}

.no-results {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 64px 16px;
  text-align: center;
  color: #a1a1aa;
}

.no-results .material-symbols-outlined {
  font-size: 32px;
  opacity: 0.5;
}

.no-results p {
  font-size: 12px;
  margin: 0;
}

/* ── Card grids ───────────────────────────────────────────── */
.card-grid {
  display: grid;
  gap: 10px;
}

.card-grid.cols-1 {
  grid-template-columns: 1fr;
}

.card-grid.cols-2 {
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.card-grid.cols-3 {
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  margin-top: 10px;
}

/* ── Cards ────────────────────────────────────────────────── */
.card {
  border-radius: 12px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.07);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
  transition: border-color 0.15s;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.card:hover {
  border-color: rgba(255, 255, 255, 0.2);
}

/* Filtered out by the search: gone, not merely dimmed — a dimmed control can
   still be reached with Tab and operated from the keyboard. */
.card.filtered {
  display: none;
}

/* ── Sync chip / window controls / notices ────────────────── */
.sync-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: #a9a7ad;
  font-size: 11px;
  white-space: nowrap;
  -webkit-app-region: no-drag;
}

.sync-chip:disabled {
  cursor: default;
}

.sync-chip .material-symbols-outlined {
  font-size: 16px;
}

.sync-chip.warn {
  color: #e4e1e6;
  border-color: rgba(255, 255, 255, 0.32);
  cursor: pointer;
}

.sync-chip.warn:hover {
  background: rgba(255, 255, 255, 0.1);
}

.window-controls {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: 4px;
  -webkit-app-region: no-drag;
}

.window-controls button {
  width: 32px;
  height: 28px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #a9a7ad;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.window-controls button:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #e4e1e6;
}

.window-controls button.close:hover {
  background: #e4e1e6;
  color: #131316;
}

.window-controls .material-symbols-outlined {
  font-size: 16px;
}

.storage-notice {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  background: #1f1f22;
  border-bottom: 1px solid rgba(255, 255, 255, 0.14);
  color: #e4e1e6;
  font-size: 12px;
  line-height: 1.5;
}

.storage-notice p {
  flex: 1;
  margin: 0;
}

.storage-notice .material-symbols-outlined {
  font-size: 18px;
}

.inline-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: #a9a7ad;
}

.slider:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.card-row {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-heading {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.card-heading > .material-symbols-outlined {
  font-size: 16px;
  color: #fff;
  margin-top: 1px;
  flex-shrink: 0;
}

.card-heading h3 {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;
  color: #fafafa;
  margin: 0 0 2px;
}

.card-heading p {
  font-size: 10.5px;
  line-height: 1.5;
  color: #a1a1aa;
  margin: 0;
}

.chip {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.75);
  font-weight: 400;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 6px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  margin-top: auto;
}

/* ── Mini stat cards ──────────────────────────────────────── */
.mini-card {
  border-radius: 8px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mini-label {
  font-size: 10px;
  color: #a1a1aa;
}

.mini-value {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
}

.mini-note {
  font-size: 9.5px;
  color: rgba(196, 199, 200, 0.55);
  line-height: 1.4;
}

/* ── Native select ────────────────────────────────────────── */
.select-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.native-select {
  width: 100%;
  height: 34px;
  padding: 0 32px 0 14px;
  border: 0;
  border-radius: var(--field-radius);
  font-size: 12px;
  color: var(--field-text);
  background: var(--field-bg);
  box-shadow: 0 0 0 1px var(--field-ring) inset;
  outline: none;
  appearance: none;
  cursor: pointer;
  transition: box-shadow var(--field-ease);
}

.native-select:focus {
  box-shadow: 0 0 0 1px var(--field-ring-focus) inset;
}

.native-select:hover:not(:focus) {
  box-shadow: 0 0 0 1px var(--field-ring-hover) inset;
}

.native-select option {
  background: #1b1b1e;
  color: #fafafa;
}

.select-caret {
  position: absolute;
  right: 8px;
  font-size: 15px;
  color: rgba(196, 199, 200, 0.6);
  pointer-events: none;
}

/* ── Segmented control ────────────────────────────────────── */
.segmented {
  display: inline-flex;
  padding: 2px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.07);
  gap: 2px;
}

.segmented button {
  flex: 1;
  height: 26px;
  padding: 0 10px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #a1a1aa;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.segmented button.on {
  background: #fafafa;
  color: #09090b;
}

.segmented.three button {
  padding: 0 6px;
  font-size: 10px;
}

.reset-zoom {
  align-self: flex-start;
  margin-top: 2px;
}

.preview {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  color: rgba(196, 199, 200, 0.6);
  margin: 0;
}

/* ── Range slider ─────────────────────────────────────────── */
.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slider {
  flex: 1;
  height: 4px;
  -webkit-appearance: none;
  appearance: none;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  outline: none;
  cursor: pointer;
}

.slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fafafa;
  border: none;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}

.slider::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fafafa;
  border: none;
  cursor: pointer;
}

.cache-size {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: #a1a1aa;
}

.slider-value {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 11px;
  color: #fafafa;
  min-width: 38px;
  text-align: right;
}

/* ── Toggle ───────────────────────────────────────────────── */
.toggle {
  width: 44px;
  height: 24px;
  flex-shrink: 0;
  padding: 2px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.1);
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
  display: flex;
  align-items: center;
}

.toggle.on {
  background: #fafafa;
  border-color: #fafafa;
}

.toggle:disabled {
  opacity: 0.4;
  cursor: default;
}

.toggle.sm {
  width: 38px;
  height: 20px;
}

.toggle-knob {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #6f7274;
  transition: transform 0.2s, background 0.2s;
  display: block;
}

.toggle.sm .toggle-knob {
  width: 14px;
  height: 14px;
}

.toggle.on .toggle-knob {
  background: #09090b;
  transform: translateX(20px);
}

.toggle.sm.on .toggle-knob {
  transform: translateX(18px);
}

/* ── Buttons ──────────────────────────────────────────────── */
.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 10.5px;
  font-weight: 500;
  color: #a1a1aa;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.08);
  cursor: pointer;
  transition: color 0.15s, background 0.15s, border-color 0.15s;
}

.ghost-btn:hover:not(:disabled) {
  color: #fafafa;
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.2);
}

.ghost-btn:disabled {
  opacity: 0.45;
  cursor: default;
}

.ghost-btn .material-symbols-outlined {
  font-size: 16px;
}

.primary-btn {
  height: 28px;
  padding: 0 14px;
  border-radius: 8px;
  font-size: 11.5px;
  font-weight: 500;
  color: #09090b;
  background: #fafafa;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s;
}

.primary-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.primary-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

/* ── Inline panels ────────────────────────────────────────── */
.inline-panel {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.privacy-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.privacy-row.column {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.privacy-info h4 {
  font-size: 11.5px;
  font-weight: 500;
  color: #fafafa;
  margin: 0 0 2px;
}

.privacy-info p {
  font-size: 10px;
  color: #a1a1aa;
  margin: 0;
  line-height: 1.45;
}

.inline-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}

/* ── Shortcut list ────────────────────────────────────────── */
.shortcut-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.shortcut-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #c4c7c8;
  padding: 3px 0;
}

.shortcut-row kbd {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #fafafa;
}

.value-note {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 14px;
  font-weight: 500;
  color: #fafafa;
}

.update-note {
  font-size: 10.5px;
  color: #a1a1aa;
  padding-top: 4px;
}

.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── Responsive: rail collapses under 900px ───────────────── */
@media (max-width: 900px) {
  .settings-rail {
    width: 200px;
  }

  .settings-content {
    padding: 16px;
  }

  .header-search {
    display: none;
  }

  /* Narrow windows: keep the back pill but drop its label so the traffic lights
     and the pill never compete for the same pixels. */
  .back-pill span:not(.material-symbols-outlined) {
    display: none;
  }

  .header-center .version-chip {
    display: none;
  }
}
</style>
