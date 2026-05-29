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
              <el-avatar :size="100" :src="form.avatar || defaultAvatar" class="modal-avatar" />
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
            <el-switch v-model="form.showOnlineStatus" active-color="#14b8a6" />
          </div>

          <div class="toggle-item">
             <div class="toggle-info">
              <span class="toggle-title">{{ $t('profile.showPhone') }}</span>
            </div>
            <el-switch v-model="form.showPhone" active-color="#14b8a6" />
          </div>

           <div class="toggle-item">
             <div class="toggle-info">
              <span class="toggle-title">{{ $t('profile.showEmail') }}</span>
            </div>
            <el-switch v-model="form.showEmail" active-color="#14b8a6" />
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
import { userAPI } from '@/services/api'
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
const defaultAvatar = 'https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png'
const showBackgroundOptions = ref(false)
const bgFileInput = ref(null)

// Presets
const presetGradients = [
  { style: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' },
  { style: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)' },
  { style: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)' },
  { style: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)' },
  { style: 'linear-gradient(135deg, #fa709a 0%, #fee140 100%)' },
  { style: 'linear-gradient(135deg, #30cfd0 0%, #330867 100%)' },
  { style: 'linear-gradient(135deg, #a8edea 0%, #fed6e3 100%)' },
  { style: 'linear-gradient(135deg, #ff9a9e 0%, #fecfef 100%)' },
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
  return { background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }
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
      profileBackground: u.profileBackground || 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
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

      // Update avatar if changed (base64)
      const currentAvatar = userStore.currentUser?.avatar
      if (form.value.avatar && form.value.avatar !== currentAvatar) {
        if (form.value.avatar.startsWith('data:image')) {
          await userAPI.uploadAvatar(userId, form.value.avatar)
        }
      }

      // Update local state
      userStore.currentUser = {
        ...userStore.currentUser,
        ...form.value
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
  height: 120px;
  position: relative;
  background-size: cover;
  background-position: center;
}

.hero-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(0,0,0,0.1), rgba(0,0,0,0.4));
}

.edit-cover-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 32px;
  height: 32px;
  background: rgba(0,0,0,0.4);
  backdrop-filter: blur(4px);
  border-radius: 50%;
  border: 1px solid rgba(255,255,255,0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  cursor: pointer;
  z-index: 10;
  transition: all 0.2s;
}
.edit-cover-btn:hover {
  background: rgba(0,0,0,0.6);
  transform: scale(1.05);
}

.modal-avatar-wrapper {
  position: absolute;
  bottom: -40px;
  left: 20px;
  z-index: 20;
}

.avatar-box {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  padding: 3px;
  background: white;
  position: relative;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  overflow: hidden;
}
[data-theme='dark'] .avatar-box {
  background: #1f2937;
}

.modal-avatar {
  width: 100%;
  height: 100%;
}

.avatar-edit-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  opacity: 0;
  border-radius: 50%;
  transition: opacity 0.2s;
  font-size: 24px;
}
.avatar-box:hover .avatar-edit-overlay {
  opacity: 1;
}

.remove-avatar-btn {
  position: absolute;
  top: 0;
  right: -5px;
  background: #ef4444;
  color: white;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  cursor: pointer;
  border: 2px solid white;
}

/* FORM */
.nexus-form {
  padding: 50px 24px 20px;
}

.form-row {
  display: flex;
  gap: 16px;
}
.half-width {
  flex: 1;
}

.section-label {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  color: #6b7280;
  margin: 20px 0 12px;
  letter-spacing: 0.5px;
}

/* Custom Input Styling Hacks specific to this form to make it "clean" */
.nexus-form :deep(.el-input__wrapper) {
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  padding-left: 12px;
}
.nexus-form :deep(.el-input__prefix-inner) {
  color: #9ca3af;
}

/* Privacy Toggles */
.privacy-block {
  background: #f9fafb;
  border-radius: 12px;
  padding: 16px;
  border: 1px solid #f3f4f6;
}
[data-theme='dark'] .privacy-block {
  background: #111827; /* Darker than dialog bg */
  border-color: #374151;
}

.toggle-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid #e5e7eb;
}
[data-theme='dark'] .toggle-item {
  border-color: #374151;
}
.toggle-item:last-child {
  border-bottom: none;
}

.toggle-title {
  font-size: 14px;
  font-weight: 500;
  color: #374151;
}
[data-theme='dark'] .toggle-title {
  color: #d1d5db;
}

/* Dialog Footer */
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.save-btn {
  background: #3390ec;
  border: none;
  font-weight: 600;
  padding: 8px 24px;
}
.save-btn:hover {
  background: #287fd6;
}

/* BG Options Sub-Dialog */
.bg-options-content {
  padding: 20px;
}

.bg-upload-area {
  border: 2px dashed #e5e7eb;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s;
  background: #f9fafb;
}
.bg-upload-area:hover {
  border-color: #3390ec;
  color: #3390ec;
  background: #eff6ff;
}
.upload-icon {
  font-size: 24px;
}

.gradient-section {
  margin-top: 20px;
}
.sub-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #374151;
}

.gradient-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.gradient-swatch {
  aspect-ratio: 1;
  border-radius: 8px;
  cursor: pointer;
  position: relative;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}
.gradient-swatch:hover {
  transform: scale(1.05);
}
.gradient-swatch.active {
  box-shadow: 0 0 0 2px white, 0 0 0 4px #3390ec;
}

/* Scrollbar */
.custom-scrollbar::-webkit-scrollbar { width: 4px; }
.custom-scrollbar::-webkit-scrollbar-thumb { background: #d1d5db; border-radius: 2px; }
</style>
