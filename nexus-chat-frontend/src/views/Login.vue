<template>
  <div class="login-container">
    <!-- Drag region for window dragging -->
    <div class="drag-region"></div>
    <div class="login-card">
      <div class="logo-area">
        <div class="logo-circle">
          <img src="@/assets/images/nexus-emblem.png" alt="Nexus" class="logo-img" />
        </div>
        <h1>{{ $t('app.name') }}</h1>
        <p>{{ $t('app.tagline') }}</p>
      </div>

      <div class="form-area">
        <!-- Avatar Upload (Register Mode Only) -->
        <div
          v-if="!isLoginMode"
          class="avatar-upload"
          role="button"
          tabindex="0"
          :aria-label="$t('auth.addPhoto')"
          @click="triggerFileInput"
          @keydown.enter="triggerFileInput"
          @keydown.space.prevent="triggerFileInput"
        >
          <img v-if="avatarPreview" :src="avatarPreview" class="avatar-preview" />
          <div v-else class="avatar-placeholder">
            <el-icon :size="40"><Camera /></el-icon>
            <span>{{ $t('auth.addPhoto') }}</span>
          </div>
          <input
            type="file"
            ref="fileInput"
            accept="image/*"
            class="hidden-input"
            @change="handleFileChange"
          />
        </div>

        <!-- Login Form -->
        <div v-if="isLoginMode" class="input-group">
          <el-input
            v-model="loginForm.usernameOrEmail"
            :placeholder="$t('auth.usernameOrEmail')"
            size="large"
            class="custom-input"
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
          <el-input
            v-model="loginForm.password"
            :placeholder="$t('auth.password')"
            size="large"
            type="password"
            class="custom-input mt-3"
            show-password
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </div>

        <!-- Register Form -->
        <div v-else class="input-group">
          <el-input
            v-model="registerForm.username"
            :placeholder="$t('auth.username')"
            size="large"
            class="custom-input"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>

          <div class="email-code-row">
            <el-input
              v-model="registerForm.email"
              :placeholder="$t('auth.email')"
              size="large"
              class="custom-input email-input"
            >
              <template #prefix>
                <el-icon><Message /></el-icon>
              </template>
            </el-input>
            <el-button
              type="primary"
              size="large"
              class="send-code-btn"
              :loading="sendingCode"
              :disabled="!canSendCode"
              @click="handleSendCode"
            >
              {{ countdown > 0 ? `${countdown}s` : $t('auth.sendCode') }}
            </el-button>
          </div>

          <el-input
            v-model="registerForm.verificationCode"
            :placeholder="$t('auth.enterCode')"
            size="large"
            class="custom-input mt-3"
            maxlength="6"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>

          <el-input
            v-model="registerForm.nickname"
            :placeholder="$t('auth.nickname')"
            size="large"
            class="custom-input mt-3"
          >
            <template #prefix>
              <el-icon><Postcard /></el-icon>
            </template>
          </el-input>

          <el-input
            v-model="registerForm.phone"
            :placeholder="$t('auth.phone') + ' ' + $t('auth.optional')"
            size="large"
            class="custom-input mt-3"
          >
            <template #prefix>
              <el-icon><Iphone /></el-icon>
            </template>
          </el-input>

          <el-input
            v-model="registerForm.password"
            :placeholder="$t('auth.password')"
            size="large"
            type="password"
            class="custom-input mt-3"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>

          <el-input
            v-model="registerForm.confirmPassword"
            :placeholder="$t('auth.confirmPassword')"
            size="large"
            type="password"
            class="custom-input mt-3"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </div>

        <!-- Action Buttons -->
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="isLoginMode ? handleLogin() : handleRegister()"
          :disabled="isLoginMode ? !isLoginValid : !isRegisterValid"
        >
          {{ isLoginMode ? $t('auth.loginButton') : $t('auth.registerButton') }}
        </el-button>

        <!-- Toggle Mode -->
        <div class="toggle-mode">
          <span>{{ isLoginMode ? $t('auth.noAccount') : $t('auth.haveAccount') }}</span>
          <a href="#" @click.prevent="toggleMode">{{ isLoginMode ? $t('auth.register') : $t('auth.login') }}</a>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loginDestination } from '@/router/navigation'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import { authAPI, userAPI, resolveFileUrl } from '@/services/api'
import { Camera, User, Lock, Message, Iphone, Postcard, Key } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const isLoginMode = ref(true)
const loading = ref(false)
const sendingCode = ref(false)
const avatarPreview = ref(null)
const avatarFile = ref(null)  // 保存原始文件对象用于上传
const fileInput = ref(null)
const countdown = ref(0)
let countdownTimer = null

const loginForm = reactive({
  usernameOrEmail: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  email: '',
  nickname: '',
  phone: '',
  password: '',
  confirmPassword: '',
  verificationCode: ''
})

const isLoginValid = computed(() => {
  return loginForm.usernameOrEmail && loginForm.password
})

const isRegisterValid = computed(() => {
  return registerForm.username &&
         registerForm.email &&
         registerForm.nickname &&
         registerForm.password &&
         registerForm.confirmPassword &&
         registerForm.verificationCode
})

const canSendCode = computed(() => {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  return emailRegex.test(registerForm.email) && countdown.value === 0 && !sendingCode.value
})

const toggleMode = () => {
  isLoginMode.value = !isLoginMode.value
  // Reset forms
  loginForm.usernameOrEmail = ''
  loginForm.password = ''
  Object.keys(registerForm).forEach(key => registerForm[key] = '')
  avatarPreview.value = null
  avatarFile.value = null  // 重置文件对象
  countdown.value = 0
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

const triggerFileInput = () => {
  fileInput.value.click()
}

const handleFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    if (file.size > 2 * 1024 * 1024) {
      ElMessage.warning(t('auth.imageSizeWarning'))
      return
    }
    avatarFile.value = file  // 保存文件对象用于上传
    const reader = new FileReader()
    reader.onload = (e) => {
      avatarPreview.value = e.target.result
    }
    reader.readAsDataURL(file)
  }
}

const startCountdown = () => {
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

const handleSendCode = async () => {
  if (!canSendCode.value) return

  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  if (!emailRegex.test(registerForm.email)) {
    ElMessage.error(t('auth.invalidEmail'))
    return
  }

  sendingCode.value = true
  try {
    await authAPI.sendVerificationCode(registerForm.email, 'REGISTER')
    ElMessage.success(t('auth.codeSent'))
    startCountdown()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || t('auth.codeSendFailed'))
  } finally {
    sendingCode.value = false
  }
}

const handleLogin = async () => {
  if (!isLoginValid.value) return

  loading.value = true
  try {
    await userStore.login(loginForm.usernameOrEmail, loginForm.password)
    if (route.name === 'Login') await router.replace(loginDestination(router, route.query))
  } catch (error) {
    ElMessage.error(error || t('auth.loginFailed'))
  } finally {
    loading.value = false
  }
}

const handleRegister = async () => {
  if (!isRegisterValid.value) return

  if (registerForm.password !== registerForm.confirmPassword) {
    ElMessage.error(t('auth.passwordMismatch'))
    return
  }

  // Basic email validation
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  if (!emailRegex.test(registerForm.email)) {
    ElMessage.error(t('auth.invalidEmail'))
    return
  }

  if (!registerForm.verificationCode) {
    ElMessage.error(t('auth.codeRequired'))
    return
  }

  loading.value = true
  try {
    const chosenAvatar = avatarFile.value ? avatarPreview.value : null
    await userStore.register({
      username: registerForm.username,
      email: registerForm.email,
      nickname: registerForm.nickname,
      password: registerForm.password,
      phone: registerForm.phone || null,
      verificationCode: registerForm.verificationCode
    })
    // Uploading needs a signed-in account, so the picture can only be sent once the
    // account exists. Sending it before registering was always refused.
    if (chosenAvatar?.startsWith('data:image/')) {
      const owner = userStore.currentUser?.id
      try {
        const { data } = await userAPI.uploadAvatar(owner, chosenAvatar)
        if (userStore.currentUser?.id === owner) await userStore.updateAvatar(resolveFileUrl(data?.avatarUrl))
      } catch (uploadError) {
        console.error('Avatar upload failed:', uploadError)
        // 头像上传失败不阻止注册，但提示用户可稍后设置
        ElMessage.warning(t('auth.avatarUploadFailed'))
      }
    }
    if (route.name === 'Login') await router.replace(loginDestination(router, route.query))
    ElMessage.success(t('auth.registerSuccess') || 'Registration successful')
  } catch (error) {
    ElMessage.error(error || t('auth.registerFailed'))
  } finally {
    loading.value = false
  }
}

onUnmounted(() => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
  }
})
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #131316;
  font-family: 'Geist Sans', sans-serif;
  position: relative;
}

/* Drag region for window dragging */
.drag-region {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  z-index: 1000;
}

.login-card {
  background: #1b1b1e;
  border: 1px solid rgba(68, 71, 72, 0.25);
  width: 100%;
  max-width: 420px;
  border-radius: 12px;
  box-shadow: 0 24px 48px -24px rgba(0, 0, 0, 0.8);
  padding: 40px;
  text-align: center;
  max-height: 90vh;
  overflow-y: auto;
}

.logo-area {
  margin-bottom: 32px;
}

.logo-circle {
  width: 80px;
  height: 80px;
  display: flex;
  justify-content: center;
  align-items: center;
  margin: 0 auto 16px;
}

.logo-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

h1 {
  font-size: 24px;
  font-weight: 700;
  color: #e4e1e6;
  margin-bottom: 4px;
}

p {
  color: #8e9192;
  font-size: 14px;
}

.avatar-upload {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: #222225;
  margin: 0 auto 24px;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  overflow: hidden;
  transition: all 0.3s ease;
  border: 1px dashed rgba(68, 71, 72, 0.6);
}

.avatar-upload:hover {
  border-color: #ffffff;
  background: rgba(255, 255, 255, 0.06);
}

.avatar-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #6f7274;
  font-size: 10px;
}

.hidden-input {
  display: none;
}

.input-group {
  margin-bottom: 24px;
}

.mt-3 {
  margin-top: 12px;
}

.email-code-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.email-input {
  flex: 1;
}

.send-code-btn {
  flex-shrink: 0;
  min-width: 100px;
  height: 44px;
  border-radius: 999px;
  font-size: 13px;
}

/* The fields take the shared capsule from prism.css (44px at this size); the
   two buttons beside and below them are rounded to match. */

.login-btn {
  width: 100%;
  height: 44px;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 600;
  background: #ffffff;
  color: #2f3131;
  border: none;
  margin-bottom: 16px;
}

.login-btn:hover {
  background: #e2e2e2;
}

.login-btn:disabled {
  background: #444748;
}

.toggle-mode {
  font-size: 14px;
  color: #8e9192;
}

.toggle-mode a {
  color: #ffffff;
  text-decoration: none;
  font-weight: 600;
  margin-left: 4px;
}

.toggle-mode a:hover {
  text-decoration: underline;
}

/* Scrollbar styling for the card */
.login-card::-webkit-scrollbar {
  width: 4px;
}

.login-card::-webkit-scrollbar-thumb {
  background-color: #353438;
  border-radius: 2px;
}

/* Mobile Responsive Styles */
@media (max-width: 768px) {
  .login-container {
    padding: 20px 16px;
    align-items: flex-start;
    padding-top: 60px;
  }

  .drag-region {
    height: 44px;
  }

  .login-card {
    max-width: 100%;
    padding: 24px 20px;
    border-radius: 20px;
    max-height: calc(100vh - 100px);
  }

  .logo-area {
    margin-bottom: 24px;
  }

  .logo-circle {
    width: 64px;
    height: 64px;
    margin-bottom: 12px;
  }

  h1 {
    font-size: 20px;
  }

  p {
    font-size: 13px;
  }

  .avatar-upload {
    width: 72px;
    height: 72px;
    margin-bottom: 20px;
  }

  .input-group {
    margin-bottom: 20px;
  }

  .custom-input :deep(.el-input__inner) {
    font-size: 16px; /* Prevent iOS zoom */
  }

  .email-code-row {
    flex-direction: column;
    gap: 12px;
  }

  .send-code-btn {
    width: 100%;
    min-width: unset;
  }

  .login-btn {
    height: 48px;
    font-size: 15px;
  }

  .toggle-mode {
    font-size: 13px;
  }
}

/* Small phones */
@media (max-width: 375px) {
  .login-container {
    padding: 16px 12px;
    padding-top: 50px;
  }

  .login-card {
    padding: 20px 16px;
  }

  .logo-circle {
    width: 56px;
    height: 56px;
  }

  h1 {
    font-size: 18px;
  }
}
</style>
