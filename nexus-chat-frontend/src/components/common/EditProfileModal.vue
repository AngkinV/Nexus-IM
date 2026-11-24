<template>
  <el-dialog
    v-model="dialogVisible"
    :title="$t('settings.editProfile')"
    width="440px"
    class="nexus-dialog"
    align-center
    :close-on-click-modal="false"
    destroy-on-close
  >
    <div class="edit-profile-container custom-scrollbar">
      
      <!-- HERO / AVATAR SECTION -->
      <div class="modal-hero" :style="heroStyle">
        <div class="hero-overlay"></div>
        
        <!-- Background Edit Button -->
        <el-tooltip :content="$t('profile.changeCover')" placement="bottom">
          <div class="edit-cover-btn" @click="showBackgroundOptions = true">
            <el-icon><Picture /></el-icon>
          </div>
        </el-tooltip>

        <!-- Avatar Wrapper -->
        <div class="modal-avatar-wrapper">
          <el-upload
            class="avatar-uploader"
            action="#"
            :show-file-list="false"
            :auto-upload="false"
            :on-change="handleAvatarChange"
          >
            <div class="avatar-box">
              <div class="modal-avatar"><img v-if="form.avatar" class="modal-avatar-img" :src="form.avatar" alt="" /><span v-else class="modal-avatar-fallback">{{ (form.nickname || form.username || "?").charAt(0) }}</span></div>
              <div class="avatar-edit-overlay">
                <el-icon><Camera /></el-icon>
              </div>
            </div>
          </el-upload>
          <div v-if="form.avatar" class="remove-avatar-btn" @click="removeAvatar" :title="$t('profile.removeAvatar')">
            <el-icon><Delete /></el-icon>
          </div>
        </div>
      </div>

      <!-- FORM SECTION -->
      <el-form 
        ref="formRef" 
        :model="form" 
        :rules="rules" 
        label-position="top"
        class="nexus-form"
        hide-required-asterisk
      >
        <div class="form-row">
          <el-form-item :label="$t('auth.nickname')" prop="nickname" class="half-width">
            <el-input v-model="form.nickname" :placeholder="$t('profile.enterNickname')" maxlength="30" />
            <div class="input-icon"><el-icon><User /></el-icon></div>
          </el-form-item>
          
          <el-form-item :label="$t('auth.username')" prop="username" class="half-width">
            <el-input v-model="form.username" disabled placeholder="@username" />
            <div class="input-icon"><span class="text-icon">@</span></div>
          </el-form-item>
        </div>

        <el-form-item :label="$t('profile.bio')" prop="bio">
          <el-input 
            v-model="form.bio" 
            type="textarea" 
            :rows="3" 
            :placeholder="$t('profile.enterBio')" 
            maxlength="150" 
            show-word-limit 
          />
        </el-form-item>

        <div class="section-label">{{ $t('profile.contactInfo') }}</div>
        
        <el-form-item prop="email">
           <el-input v-model="form.email" :placeholder="$t('profile.enterEmail')">
             <template #prefix><el-icon><Message /></el-icon></template>
           </el-input>
        </el-form-item>

        <el-form-item prop="phone">
           <el-input v-model="form.phone" :placeholder="$t('profile.enterPhone')">
             <template #prefix><el-icon><Phone /></el-icon></template>
           </el-input>
        </el-form-item>

        <!-- PRIVACY SETTINGS -->
        <div class="privacy-block">
          <div class="section-label">{{ $t('profile.privacySettings') }}</div>
          
          <div class="toggle-item">
            <div class="toggle-info">
              <span class="toggle-title">{{ $t('profile.showOnlineStatus') }}</span>
            </div>
            <el-switch v-model="form.showOnlineStatus" active-color="#ffffff" />
          </div>

          <div class="toggle-item">
             <div class="toggle-info">
              <span class="toggle-title">{{ $t('profile.showPhone') }}</span>
            </div>
            <el-switch v-model="form.showPhone" active-color="#ffffff" />
          </div>

           <div class="toggle-item">
             <div class="toggle-info">
              <span class="toggle-title">{{ $t('profile.showEmail') }}</span>
            </div>
            <el-switch v-model="form.showEmail" active-color="#ffffff" />
          </div>
        </div>

      </el-form>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="handleClose" class="cancel-btn">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveProfile" :loading="isSaving" class="save-btn">
          {{ $t('common.save') }}
        </el-button>
      </div>
    </template>
  </el-dialog>

  <!-- Background Options Dialog (Nested) -->
  <el-dialog
    v-model="showBackgroundOptions"
    :title="$t('profile.selectBackground')"
    width="360px"
    align-center
    append-to-body
    class="nexus-dialog sub-dialog"
  >
    <div class="bg-options-content">
      <div class="bg-upload-area" @click="$refs.bgFileInput.click()">
        <input
          type="file"
          ref="bgFileInput"
          accept="image/*"
          style="display: none"
          @change="handleBackgroundImageUpload"
        >
        <el-icon class="upload-icon"><Upload /></el-icon>
        <span>{{ $t('profile.uploadImage') }}</span>
      </div>

      <div class="gradient-section">
        <span class="sub-label">{{ $t('profile.presets') }}</span>
        <div class="gradient-grid">
          <div 
            v-for="(gradient, idx) in presetGradients" 
            :key="idx" 
            class="gradient-swatch"
            :style="{ background: gradient.style }"
            @click="selectGradient(gradient.style)"
            :class="{ active: form.profileBackground === gradient.style }"
          >
             <el-icon v-if="form.profileBackground === gradient.style"><Check /></el-icon>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, reactive } from 'vue'
import { Camera, Delete, User, Message, Phone, Lock, Picture, Upload, Check } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { userAPI, resolveFileUrl } from '@/services/api'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible', 'updated'])
const userStore = useUserStore()

const dialogVisible = computed({
  get: () => props.visible,
  set: (val) => emit('update:visible', val)
})

const formRef = ref(null)
const isSaving = ref(false)
const showBackgroundOptions = ref(false)
const bgFileInput = ref(null)

// Presets
const presetGradients = [
  { style: 'linear-gradient(135deg, #2a2a2d 0%, #131316 100%)' },
  { style: 'linear-gradient(135deg, #3a3a3e 0%, #1b1b1e 100%)' },
  { style: 'linear-gradient(135deg, #444748 0%, #222225 100%)' },
  { style: 'linear-gradient(135deg, #1b1b1e 0%, #353438 100%)' },
  { style: 'linear-gradient(135deg, #353438 0%, #0e0e11 100%)' },
  { style: 'linear-gradient(135deg, #6f7274 0%, #131316 100%)' },
  { style: 'linear-gradient(135deg, #8e9192 0%, #222225 100%)' },
  { style: 'linear-gradient(135deg, #c4c7c8 0%, #2a2a2d 100%)' },
]

const form = ref({
  avatar: '',
  nickname: '',
  username: '',
  bio: '',
  phone: '',
  email: '',
  showOnlineStatus: true,
  showLastSeen: true,
  showPhone: false,
  showEmail: false,
  profileBackground: ''
})

const rules = reactive({
  nickname: [
    { required: true, message: t('profile.nicknameRequired'), trigger: 'blur' },
    { min: 2, max: 30, message: t('profile.nicknameLength'), trigger: 'blur' }
  ],
  email: [
    { type: 'email', message: t('auth.invalidEmail'), trigger: 'blur' }
  ]
})

const heroStyle = computed(() => {
  const bg = form.value.profileBackground
  if (bg && (bg.startsWith('http') || bg.startsWith('data:image'))) {
    return { backgroundImage: `url(${bg})`, backgroundSize: 'cover', backgroundPosition: 'center' }
  } else if (bg) {
    return { background: bg }
  }
  return { background: 'linear-gradient(135deg, #2a2a2d 0%, #131316 100%)' }
})

watch(() => props.visible, (val) => {
  if (val && userStore.currentUser) {
    const u = userStore.currentUser
    form.value = {
      avatar: u.avatar || '',
      nickname: u.nickname || '',
      username: u.username || '',
      bio: u.bio || '',
      phone: u.phone || '',
      email: u.email || '',
      showOnlineStatus: u.showOnlineStatus ?? true,
      showLastSeen: u.showLastSeen ?? true,
      showPhone: u.showPhone ?? false,
      showEmail: u.showEmail ?? false,
      profileBackground: u.profileBackground || 'linear-gradient(135deg, #2a2a2d 0%, #131316 100%)'
    }
  }
})

const handleClose = () => { dialogVisible.value = false }

const handleAvatarChange = (file) => {
  if (file.raw.size > 2 * 1024 * 1024) return ElMessage.warning(t('auth.imageSizeWarning'))
  const reader = new FileReader()
  reader.onload = (e) => { form.value.avatar = e.target.result }
  reader.readAsDataURL(file.raw)
}

const removeAvatar = () => { form.value.avatar = '' }

const selectGradient = (gradientStyle) => {
  form.value.profileBackground = gradientStyle
}

const handleBackgroundImageUpload = (event) => {
  const file = event.target.files[0]
  if (!file) return
  if (!file.type.startsWith('image/')) return ElMessage.error(t('profile.pleaseUploadImage'))
  if (file.size > 5 * 1024 * 1024) return ElMessage.error(t('profile.imageMaxSize5MB'))

  const reader = new FileReader()
  reader.onload = (e) => {
    form.value.profileBackground = e.target.result
    showBackgroundOptions.value = false
    ElMessage.success(t('profile.backgroundUpdated'))
  }
  reader.readAsDataURL(file)
}

const saveProfile = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    isSaving.value = true
    try {
      const userId = userStore.currentUser?.id
      if (!userId) {
        ElMessage.error(t('profile.userNotFound'))
        return
      }

      // Update profile (nickname, bio, email, phone)
      await userAPI.updateProfile(userId, {
        nickname: form.value.nickname,
        bio: form.value.bio,
        email: form.value.email,
        phone: form.value.phone
      })

      // Update privacy settings
      await userAPI.updatePrivacySettings(userId, {
        showOnlineStatus: form.value.showOnlineStatus,
        showLastSeen: form.value.showLastSeen,
        showPhone: form.value.showPhone,
        showEmail: form.value.showEmail
      })

      // Update background if changed
      const currentBackground = userStore.currentUser?.profileBackground
      if (form.value.profileBackground && form.value.profileBackground !== currentBackground) {
        await userStore.updateBackground(form.value.profileBackground)
      }

      // The server stores the picture and answers with its URL. Keep that URL, not the
      // picked file's data: it is what every other screen and every contact will load.
      const currentAvatar = userStore.currentUser?.avatar || ''
      let avatar = currentAvatar
      if (form.value.avatar !== currentAvatar) {
        if (form.value.avatar.startsWith('data:image')) {
          const { data } = await userAPI.uploadAvatar(userId, form.value.avatar)
          avatar = resolveFileUrl(data?.avatarUrl)
        } else if (!form.value.avatar) {
          await userAPI.deleteAvatar(userId)
          avatar = ''
        }
      }

      // Update local state
      userStore.currentUser = {
        ...userStore.currentUser,
        ...form.value,
        avatar
      }
      localStorage.setItem('user', JSON.stringify(userStore.currentUser))

      emit('updated', userStore.currentUser)
      ElMessage.success(t('profile.updateSuccess'))
      handleClose()
    } catch (error) {
      console.error('Profile update error:', error)
      ElMessage.error(t('profile.updateFailed'))
    } finally {
      isSaving.value = false
    }
  })
}
</script>

<style>
/* Global Dialog Overrides for "Nexus" Theme */
.nexus-dialog {
  border-radius: var(--tg-dialog-radius);
  overflow: hidden;
  background: var(--tg-dialog-bg);
  border: 1px solid var(--tg-dialog-border);
  box-shadow: var(--tg-dialog-shadow);
}
.nexus-dialog .el-dialog__header {
  margin: 0;
  padding: var(--tg-dialog-header-padding);
  border-bottom: 1px solid var(--tg-dialog-border);
  background: var(--tg-dialog-bg);
}
.nexus-dialog .el-dialog__title {
  font-weight: var(--tg-dialog-title-weight);
  color: var(--tg-text-primary);
  font-size: var(--tg-dialog-title-size);
}
.nexus-dialog .el-dialog__body {
  padding: 0; 
}
.nexus-dialog .el-dialog__footer {
  padding: var(--tg-dialog-footer-padding);
  background: var(--tg-dialog-bg);
  border-top: 1px solid var(--tg-dialog-border);
}
</style>

<style scoped>
.edit-profile-container {
  max-height: 70vh;
  overflow-y: auto;
}

/* HERO */
.modal-hero {
  height: 112px;
  position: relative;
  background-size: cover;
  background-position: center;
  border-bottom: 1px solid rgba(68, 71, 72, 0.25);
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.1), rgba(0, 0, 0, 0.5));
}

.edit-cover-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  background: rgba(14, 14, 17, 0.7);
  border-radius: 4px;
  border: 1px solid rgba(142, 145, 146, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #e4e1e6;
  cursor: pointer;
  z-index: 10;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.edit-cover-btn:hover {
  background: rgba(14, 14, 17, 0.9);
  border-color: rgba(196, 199, 200, 0.6);
}

.edit-cover-btn :deep(.el-icon) {
  font-size: 14px;
}

.modal-avatar-wrapper {
  position: absolute;
  bottom: -38px;
  left: 20px;
  z-index: 20;
}

.avatar-box {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  padding: 3px;
  background: #131316;
  position: relative;
  box-shadow: 0 0 0 1px rgba(142, 145, 146, 0.35);
  cursor: pointer;
  overflow: hidden;
}

.modal-avatar {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #2a2a2d;
}

.modal-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.modal-avatar-fallback {
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 26px;
  color: #c4c7c8;
  text-transform: uppercase;
}

.avatar-edit-overlay {
  position: absolute;
  inset: 0;
  background: rgba(6, 6, 8, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  opacity: 0;
  border-radius: 50%;
  transition: opacity 0.15s ease;
  font-size: 20px;
}

.avatar-box:hover .avatar-edit-overlay {
  opacity: 1;
}

.remove-avatar-btn {
  position: absolute;
  top: 0;
  right: -4px;
  background: #1b1b1e;
  color: #f2b8b5;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid rgba(68, 71, 72, 0.5);
}

.remove-avatar-btn:hover {
  background: #2a2a2d;
  color: #ffffff;
}

/* FORM */
.nexus-form {
  padding: 48px 20px 18px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.half-width {
  flex: 1;
}

.section-label {
  font-size: 11px;
  font-weight: 500;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  text-transform: uppercase;
  color: #6f7274;
  margin: 18px 0 10px;
  letter-spacing: 0.06em;
}

.nexus-form :deep(.el-form-item__label) {
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  color: #8e9192;
  letter-spacing: 0.04em;
  padding-bottom: 4px;
}

.input-icon {
  display: none;
}

/* Privacy Toggles */
.privacy-block {
  background: #16161a;
  border-radius: 8px;
  padding: 4px 14px;
  border: 1px solid rgba(68, 71, 72, 0.25);
}

.toggle-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid rgba(68, 71, 72, 0.2);
}

.toggle-item:last-child {
  border-bottom: none;
}

.toggle-title {
  font-size: 13px;
  color: #c4c7c8;
}

/* Dialog Footer */
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.cancel-btn {
  background: transparent;
  border: 1px solid rgba(68, 71, 72, 0.5);
  color: #c4c7c8;
}

.cancel-btn:hover {
  background: #2a2a2d;
  border-color: rgba(142, 145, 146, 0.6);
  color: #ffffff;
}

.save-btn {
  background: #ffffff;
  border: none;
  color: #2f3131;
  font-weight: 500;
  padding: 8px 22px;
}

.save-btn:hover {
  background: #e2e2e2;
  color: #2f3131;
}

/* BG Options Sub-Dialog */
.bg-options-content {
  padding: 18px;
}

.bg-upload-area {
  border: 1px dashed rgba(68, 71, 72, 0.6);
  border-radius: 8px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #8e9192;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
  background: #16161a;
}

.bg-upload-area:hover {
  border-color: rgba(196, 199, 200, 0.7);
  color: #e4e1e6;
}

.upload-icon {
  font-size: 20px;
}

.gradient-section {
  margin-top: 18px;
}

.sub-label {
  display: block;
  font-size: 11px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin-bottom: 10px;
  color: #6f7274;
}

.gradient-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.gradient-swatch {
  aspect-ratio: 1;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  box-shadow: 0 0 0 1px rgba(68, 71, 72, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  transition: transform 0.15s ease;
}

.gradient-swatch:hover {
  transform: scale(1.04);
}

.gradient-swatch.active {
  box-shadow: 0 0 0 1px #131316, 0 0 0 2px #ffffff;
}

.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(142, 145, 146, 0.3);
  border-radius: 2px;
}
</style>
