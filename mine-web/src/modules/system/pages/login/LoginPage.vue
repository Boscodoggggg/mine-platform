<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import smartMineTunnelUrl from '@/assets/images/smart-mine-tunnel.svg';
import SystemLogo from '@/components/common/system-logo.vue';
import { useThemeStore } from '@/store/modules/theme';
import { changePassword, login } from '../../api/auth';
import { clearAuthSession, saveAuthSession } from '../../auth/session';

type LoginStage = 'login' | 'password' | 'complete';
type LoginTheme = 'light' | 'dark';

interface DisplayError {
  message: string;
  traceId?: string;
}


const router = useRouter();
const stage = ref<LoginStage>('login');
const submitting = ref(false);
const error = ref<DisplayError | null>(null);
const fieldErrors = reactive<Record<string, string | undefined>>({
  username: undefined,
  password: undefined,
  currentPassword: undefined,
  newPassword: undefined,
  confirmPassword: undefined
});
const loginForm = reactive({ username: '', password: '' });
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' });
const themeStore = useThemeStore();
const loginTheme = ref<LoginTheme>(resolveInitialLoginTheme());
const isDarkTheme = computed(() => loginTheme.value === 'dark');

themeStore.setThemeScheme(loginTheme.value);

const title = computed(() => {
  if (stage.value === 'password') return '修改初始密码';
  if (stage.value === 'complete') return '密码修改成功';
  return '欢迎登录';
});

function resolveInitialLoginTheme(): LoginTheme {
  const savedTheme = window.localStorage.getItem('mine-login-theme');
  if (savedTheme === 'light' || savedTheme === 'dark') return savedTheme;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function toggleLoginTheme() {
  loginTheme.value = isDarkTheme.value ? 'light' : 'dark';
  window.localStorage.setItem('mine-login-theme', loginTheme.value);
  themeStore.setThemeScheme(loginTheme.value);
}

async function submit() {
  if (submitting.value) return;
  error.value = null;
  clearFieldErrors();
  if (stage.value === 'login') {
    await submitLogin();
  } else if (stage.value === 'password') {
    await submitPasswordChange();
  }
}

async function submitLogin() {
  if (!loginForm.username.trim()) fieldErrors.username = '请输入用户名';
  if (!loginForm.password) fieldErrors.password = '请输入密码';
  if (hasFieldErrors()) return;

  submitting.value = true;
  try {
    const response = await login({
      username: loginForm.username.trim(),
      password: loginForm.password
    });
    saveAuthSession(response);
    loginForm.password = '';
    if (response.user.passwordChangeRequired) {
      stage.value = 'password';
      return;
    }
    await router.push('/');
  } catch (reason) {
    error.value = toDisplayError(reason);
  } finally {
    submitting.value = false;
  }
}

async function submitPasswordChange() {
  if (!passwordForm.currentPassword) fieldErrors.currentPassword = '请输入当前密码';
  if (!passwordForm.newPassword) {
    fieldErrors.newPassword = '请输入新密码';
  } else if (passwordForm.newPassword.length < 12) {
    fieldErrors.newPassword = '新密码至少需要 12 个字符';
  } else if (new TextEncoder().encode(passwordForm.newPassword).length > 72) {
    fieldErrors.newPassword = '新密码的 UTF-8 编码不能超过 72 字节';
  }
  if (!passwordForm.confirmPassword) {
    fieldErrors.confirmPassword = '请再次输入新密码';
  } else if (passwordForm.confirmPassword !== passwordForm.newPassword) {
    fieldErrors.confirmPassword = '两次输入的新密码不一致';
  }
  if (hasFieldErrors()) return;

  submitting.value = true;
  try {
    await changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword
    });
    clearAuthSession();
    clearSensitiveFields();
    stage.value = 'complete';
  } catch (reason) {
    error.value = toDisplayError(reason);
  } finally {
    submitting.value = false;
  }
}

function restartLogin() {
  clearAuthSession();
  clearSensitiveFields();
  stage.value = 'login';
  error.value = null;
}

function clearSensitiveFields() {
  loginForm.password = '';
  passwordForm.currentPassword = '';
  passwordForm.newPassword = '';
  passwordForm.confirmPassword = '';
}

function clearFieldErrors() {
  Object.keys(fieldErrors).forEach(key => {
    fieldErrors[key] = undefined;
  });
}

function hasFieldErrors() {
  return Object.values(fieldErrors).some(Boolean);
}

function toDisplayError(reason: unknown): DisplayError {
  if (reason && typeof reason === 'object') {
    const candidate = reason as { message?: unknown; traceId?: unknown };
    return {
      message: typeof candidate.message === 'string' ? candidate.message : '请求处理失败，请稍后重试',
      traceId: typeof candidate.traceId === 'string' ? candidate.traceId : undefined
    };
  }
  return { message: '请求处理失败，请稍后重试' };
}
</script>

<template>
  <main class="login-shell" :class="`login-shell--${loginTheme}`">
    <svg class="liquid-filter-defs" aria-hidden="true" width="0" height="0">
      <defs>
        <symbol id="login-icon-user" viewBox="0 0 24 24">
          <circle cx="12" cy="8" r="3.4" />
          <path d="M5.8 19.2c.5-3.4 2.5-5.2 6.2-5.2s5.7 1.8 6.2 5.2" />
        </symbol>
        <symbol id="login-icon-lock" viewBox="0 0 24 24">
          <rect x="5.5" y="10" width="13" height="9.5" rx="2.2" />
          <path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10M12 14v2.2" />
        </symbol>
        <symbol id="login-icon-enter" viewBox="0 0 24 24">
          <path d="M13.5 5.5h3.2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-3.2M10.5 8.5 14 12l-3.5 3.5M14 12H4.8" />
        </symbol>
        <symbol id="login-icon-service" viewBox="0 0 24 24">
          <circle cx="6" cy="12" r="2.2" />
          <circle cx="18" cy="7" r="2.2" />
          <circle cx="18" cy="17" r="2.2" />
          <path d="m8 11 7.8-3M8 13l7.8 3" />
        </symbol>
        <symbol id="login-icon-sun" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="3.5" />
          <path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M18.7 5.3l-1.4 1.4M6.7 17.3l-1.4 1.4" />
        </symbol>
        <symbol id="login-icon-moon" viewBox="0 0 24 24">
          <path d="M19.4 15.4A8.2 8.2 0 0 1 8.6 4.6 8.2 8.2 0 1 0 19.4 15.4Z" />
        </symbol>
        <filter id="liquid-glass-distortion" x="-20%" y="-20%" width="140%" height="140%">
          <feTurbulence type="fractalNoise" baseFrequency="0.012 0.02" numOctaves="2" seed="8" result="noise" />
          <feGaussianBlur in="noise" stdDeviation="0.65" result="softNoise" />
          <feDisplacementMap
            in="SourceGraphic"
            in2="softNoise"
            scale="18"
            xChannelSelector="R"
            yChannelSelector="B"
          />
        </filter>
      </defs>
    </svg>
    <button
      class="theme-switch"
      type="button"
      data-login-theme-switch
      :aria-label="isDarkTheme ? '切换为亮色模式' : '切换为暗色模式'"
      :title="isDarkTheme ? '切换为亮色模式' : '切换为暗色模式'"
      @click="toggleLoginTheme"
    >
      <svg aria-hidden="true">
        <use :href="isDarkTheme ? '#login-icon-sun' : '#login-icon-moon'" />
      </svg>
    </button>
    <img class="mine-scene-asset" :src="smartMineTunnelUrl" alt="" aria-hidden="true" />

    <section class="brand-stage brand-stage--lower-left" aria-label="平台介绍">
      <h1>智慧矿山数据平台</h1>
      <p>设备智联 · 数据洞察 · 生产协同</p>
    </section>

    <section class="form-panel">
      <div class="form-card login-card liquid-glass">
        <span class="glass-refraction" aria-hidden="true"></span>
        <header class="form-header">
          <span class="form-header__mark" aria-hidden="true">
            <SystemLogo class="form-header__logo" />
          </span>
          <h2>{{ title }}</h2>
          <p v-if="stage === 'login'">使用矿区管理账号登录。连续失败将触发安全限制。</p>
          <p v-else-if="stage === 'password'">当前账号仍在使用初始密码，完成修改后需要重新登录。</p>
          <p v-else>密码修改成功，请重新登录。</p>
        </header>

        <form v-if="stage !== 'complete'" novalidate @submit.prevent="submit">
          <template v-if="stage === 'login'">
            <label class="field">
              <span>用户名</span>
              <div class="field-control">
                <svg class="field-control__icon" data-login-icon="username" aria-hidden="true">
                  <use href="#login-icon-user" />
                </svg>
                <input
                  v-model="loginForm.username"
                  name="username"
                  autocomplete="username"
                  maxlength="64"
                  :aria-invalid="Boolean(fieldErrors.username)"
                  :aria-describedby="fieldErrors.username ? 'username-error' : undefined"
                  placeholder="请输入管理账号"
                />
              </div>
              <small v-if="fieldErrors.username" id="username-error" class="field-error">{{ fieldErrors.username }}</small>
            </label>
            <label class="field">
              <span>密码</span>
              <div class="field-control">
                <svg class="field-control__icon" data-login-icon="password" aria-hidden="true">
                  <use href="#login-icon-lock" />
                </svg>
                <input
                  v-model="loginForm.password"
                  name="password"
                  type="password"
                  autocomplete="current-password"
                  maxlength="256"
                  :aria-invalid="Boolean(fieldErrors.password)"
                  :aria-describedby="fieldErrors.password ? 'password-error' : undefined"
                  placeholder="请输入登录密码"
                />
              </div>
              <small v-if="fieldErrors.password" id="password-error" class="field-error">{{ fieldErrors.password }}</small>
            </label>
          </template>

          <template v-else>
            <div class="security-note">
              <strong>密码安全要求</strong>
              <span>至少 12 个字符，UTF-8 编码不超过 72 字节，不得与当前密码相同。</span>
            </div>
            <label class="field">
              <span>当前密码</span>
              <div class="field-control">
                <svg class="field-control__icon" aria-hidden="true"><use href="#login-icon-lock" /></svg>
                <input
                  v-model="passwordForm.currentPassword"
                  name="currentPassword"
                  type="password"
                  autocomplete="current-password"
                  :aria-invalid="Boolean(fieldErrors.currentPassword)"
                />
              </div>
              <small v-if="fieldErrors.currentPassword" class="field-error">{{ fieldErrors.currentPassword }}</small>
            </label>
            <label class="field">
              <span>新密码</span>
              <div class="field-control">
                <svg class="field-control__icon" aria-hidden="true"><use href="#login-icon-lock" /></svg>
                <input
                  v-model="passwordForm.newPassword"
                  name="newPassword"
                  type="password"
                  autocomplete="new-password"
                  :aria-invalid="Boolean(fieldErrors.newPassword)"
                />
              </div>
              <small v-if="fieldErrors.newPassword" class="field-error">{{ fieldErrors.newPassword }}</small>
            </label>
            <label class="field">
              <span>确认新密码</span>
              <div class="field-control">
                <svg class="field-control__icon" aria-hidden="true"><use href="#login-icon-lock" /></svg>
                <input
                  v-model="passwordForm.confirmPassword"
                  name="confirmPassword"
                  type="password"
                  autocomplete="new-password"
                  :aria-invalid="Boolean(fieldErrors.confirmPassword)"
                />
              </div>
              <small v-if="fieldErrors.confirmPassword" class="field-error">{{ fieldErrors.confirmPassword }}</small>
            </label>
          </template>

          <div v-if="error" class="request-error" role="alert">
            <strong>{{ error.message }}</strong>
            <span v-if="error.traceId">链路编号：{{ error.traceId }}</span>
          </div>

          <button class="submit-button" type="submit" :disabled="submitting">
            <span class="submit-button__content">
              <svg class="submit-button__icon" data-login-icon="submit" aria-hidden="true">
                <use href="#login-icon-enter" />
              </svg>
              <span>{{ submitting ? '正在处理…' : stage === 'login' ? '登录平台' : '确认修改' }}</span>
            </span>
            <span class="submit-button__arrow" aria-hidden="true">→</span>
          </button>
        </form>

        <div v-else class="complete-state" role="status">
          <span class="complete-state__icon" aria-hidden="true">✓</span>
          <p>为确保旧令牌全部失效，系统已安全退出当前会话。</p>
          <button class="submit-button" type="button" @click="restartLogin">
            <span class="submit-button__content">
              <svg class="submit-button__icon" aria-hidden="true"><use href="#login-icon-enter" /></svg>
              <span>重新登录</span>
            </span>
            <span class="submit-button__arrow" aria-hidden="true">→</span>
          </button>
        </div>

        <footer class="form-footer">
          <span class="service-status">
            <svg class="service-status__icon" data-login-icon="service" aria-hidden="true">
              <use href="#login-icon-service" />
            </svg>
            本地服务
          </span>
          <span>连接异常时请联系矿区系统管理员</span>
        </footer>
      </div>
    </section>
  </main>
</template>

<style scoped>
/* 深蓝数据星网主题 */
:global(body) {
  margin: 0;
  font-family: "HarmonyOS Sans SC", MiSans, "Source Han Sans SC", "PingFang SC", sans-serif;
  overflow: hidden;
  background: #07122d;
  color: #eef6ff;
}

.login-shell {
  position: relative;
  isolation: isolate;
  display: grid;
  min-height: 100vh;
  grid-template-columns: 66% 34%;
  overflow: hidden;
  background:
    radial-gradient(circle at 38% 52%, rgb(16 57 116 / 42%), transparent 34%),
    linear-gradient(118deg, #050d25 0%, #081632 58%, #10264b 100%);
  transition: background 260ms cubic-bezier(0.22, 1, 0.36, 1), color 220ms ease;
}

.login-shell::before {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgb(4 10 29 / 12%), transparent 46%, rgb(9 24 53 / 18%)),
    radial-gradient(circle at 75% 42%, rgb(36 126 206 / 8%), transparent 24%);
  content: "";
  pointer-events: none;
  z-index: 0;
}

.theme-switch {
  position: absolute;
  top: clamp(18px, 2.4vw, 34px);
  right: clamp(18px, 2.4vw, 34px);
  z-index: 8;
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid rgb(222 242 255 / 28%);
  border-radius: 50%;
  background: rgb(27 59 98 / 34%);
  color: #d7ecfa;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgb(245 252 255 / 24%),
    0 10px 28px rgb(0 8 28 / 18%);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  transition: transform 180ms ease, border-color 180ms ease, background 180ms ease, color 180ms ease;
}

.theme-switch svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.65;
}

.theme-switch:hover {
  border-color: rgb(222 242 255 / 50%);
  background: rgb(48 86 128 / 48%);
  transform: translateY(-1px);
}

.theme-switch:focus-visible {
  outline: 3px solid rgb(68 180 232 / 28%);
  outline-offset: 3px;
}

.mine-scene-asset {
  position: absolute;
  top: 50%;
  left: clamp(-32px, 1vw, 22px);
  z-index: 0;
  width: min(64vw, 1080px);
  max-width: none;
  height: auto;
  opacity: 0.84;
  pointer-events: none;
  filter: saturate(0.94) contrast(1.06) drop-shadow(0 34px 54px rgb(0 5 23 / 34%));
  mask-image: linear-gradient(90deg, transparent 0%, #000 7%, #000 82%, transparent 100%);
  transform: translateY(-50%);
  transform-origin: 42% 58%;
  animation: mine-scene-breathe 8s cubic-bezier(0.22, 1, 0.36, 1) infinite alternate;
}

.brand-stage {
  position: relative;
  z-index: 2;
  display: grid;
  align-content: end;
  justify-items: start;
  padding: 0 8vw clamp(74px, 10vh, 112px) 7vw;
  color: #f4f8ff;
  text-align: left;
}

.form-header__mark {
  display: grid;
  place-items: center;
  border: 1px solid rgb(92 139 209 / 22%);
  background: rgb(35 55 99 / 74%);
  box-shadow: 0 18px 48px rgb(0 5 24 / 34%);
}

.form-header__logo {
  width: 48px;
  height: 40px;
}

.brand-stage h1 {
  margin: 0 0 8px;
  font-size: clamp(28px, 2vw, 38px);
  font-weight: 650;
  letter-spacing: 0.12em;
  line-height: 1.2;
  text-shadow: 0 8px 24px rgb(0 5 25 / 30%);
}

.brand-stage p {
  margin: 0;
  color: #129fe7;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: clamp(12px, 0.9vw, 15px);
  letter-spacing: 0.08em;
}

.form-panel {
  position: relative;
  z-index: 3;
  display: grid;
  min-width: 0;
  min-height: 100vh;
  place-items: center;
  overflow: visible;
  padding: 48px clamp(44px, 6vw, 104px) 48px 0;
  background: transparent;
}

.form-panel::before {
  position: absolute;
  width: min(31vw, 470px);
  height: min(72vh, 650px);
  border-radius: 46% 54% 58% 42% / 38% 44% 56% 62%;
  background:
    radial-gradient(circle at 30% 18%, rgb(141 205 240 / 48%), transparent 27%),
    radial-gradient(circle at 76% 70%, rgb(28 102 174 / 54%), transparent 36%),
    radial-gradient(circle at 24% 78%, rgb(218 143 64 / 15%), transparent 24%);
  content: '';
  filter: blur(20px);
  opacity: 0.62;
  pointer-events: none;
  transform: translate(4%, -1%) rotate(-7deg);
}

.form-card {
  position: relative;
  left: 0;
  isolation: isolate;
  box-sizing: border-box;
  width: min(100%, 438px);
  border: 1px solid rgb(225 242 255 / 32%);
  border-radius: 34px;
  overflow: hidden;
  background:
    linear-gradient(145deg, rgb(235 248 255 / 10%) 0%, rgb(114 172 218 / 4%) 38%, rgb(4 22 52 / 14%) 80%),
    rgb(19 48 84 / 24%);
  padding: 44px 42px 34px;
  box-shadow:
    0 36px 96px rgb(0 5 27 / 46%),
    0 8px 30px rgb(17 89 151 / 13%),
    inset 0 1px 0 rgb(244 251 255 / 42%),
    inset 0 -1px 0 rgb(57 117 173 / 18%);
  backdrop-filter: blur(22px) saturate(178%) contrast(1.08) brightness(1.05);
  -webkit-backdrop-filter: blur(22px) saturate(178%) contrast(1.08) brightness(1.05);
  animation: form-reveal 760ms cubic-bezier(0.16, 1, 0.3, 1) 100ms both;
}

.form-card::before {
  position: absolute;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(circle at 26% 14%, rgb(249 253 255 / 30%) 0 2%, rgb(210 240 255 / 12%) 8%, transparent 28%),
    linear-gradient(126deg, rgb(250 253 255 / 16%) 0%, transparent 22% 68%, rgb(102 184 231 / 8%) 100%);
  content: '';
  pointer-events: none;
}

.form-card::after {
  position: absolute;
  inset: 1px;
  z-index: 1;
  border: 1px solid rgb(235 248 255 / 17%);
  border-radius: 32px;
  box-shadow:
    inset 13px 11px 22px rgb(230 247 255 / 8%),
    inset -15px -20px 32px rgb(0 17 48 / 22%);
  content: '';
  pointer-events: none;
}

.form-card > * {
  position: relative;
  z-index: 2;
}

.liquid-filter-defs {
  position: absolute;
  pointer-events: none;
}

.form-card > .glass-refraction {
  position: absolute;
  inset: -9%;
  z-index: 1;
  border-radius: 38px;
  background:
    radial-gradient(circle at 26% 14%, rgb(244 252 255 / 26%) 0 2%, transparent 19%),
    conic-gradient(from 214deg at 46% 50%, transparent, rgb(151 215 247 / 10%), transparent 34% 76%, rgb(250 252 255 / 9%));
  filter: url('#liquid-glass-distortion');
  mix-blend-mode: screen;
  opacity: 0.76;
  pointer-events: none;
  transition: opacity 180ms ease;
}

.form-header {
  display: grid;
  justify-items: center;
  margin-bottom: 30px;
  text-align: center;
}

.form-header__mark {
  width: 58px;
  height: 58px;
  border-radius: 15px;
  background: linear-gradient(145deg, #0f8ee1, #3a6ff0);
  box-shadow: 0 14px 34px rgb(2 18 50 / 36%);
}

.form-header h2 {
  margin: 24px 0 8px;
  color: #f3f8ff;
  font-size: 25px;
  font-weight: 650;
  letter-spacing: 0.06em;
}

.form-header p {
  margin: 0;
  color: #8fa6c8;
  font-size: 12px;
  line-height: 1.65;
}

.field {
  position: relative;
  display: block;
  margin-bottom: 18px;
}

.field > span {
  display: block;
  margin-bottom: 7px;
  color: #a9bddd;
  font-size: 12px;
  font-weight: 600;
}

.field-control {
  position: relative;
}

.field-control__icon {
  position: absolute;
  top: 50%;
  left: 15px;
  z-index: 1;
  width: 18px;
  height: 18px;
  fill: none;
  stroke: #89a9ca;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.55;
  pointer-events: none;
  transform: translateY(-50%);
  transition: color 180ms ease, filter 180ms ease;
}

.field-control:focus-within .field-control__icon {
  stroke: #aee9ff;
  filter: drop-shadow(0 0 5px rgb(74 191 235 / 35%));
}

.field input {
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  border: 1px solid rgb(230 246 255 / 24%);
  border-radius: 14px;
  outline: none;
  background:
    linear-gradient(180deg, rgb(235 248 255 / 13%), rgb(125 177 215 / 6%)),
    rgb(25 58 94 / 22%);
  padding: 0 15px 0 44px;
  color: #edf7ff;
  font: inherit;
  box-shadow:
    inset 0 1px 0 rgb(247 253 255 / 22%),
    inset 0 -1px 0 rgb(40 92 143 / 18%),
    0 8px 20px rgb(0 17 50 / 12%);
  backdrop-filter: blur(16px) saturate(145%);
  -webkit-backdrop-filter: blur(16px) saturate(145%);
  transition: border-color 180ms ease, box-shadow 180ms ease, background 180ms ease;
}

.field input::placeholder {
  color: #98afc6;
}

.field input:focus {
  border-color: rgb(148 222 255 / 62%);
  background: rgb(79 134 181 / 20%);
  box-shadow:
    inset 0 1px 0 rgb(248 253 255 / 32%),
    0 0 0 4px rgb(88 190 235 / 15%),
    0 12px 26px rgb(0 29 65 / 18%);
}

.field input[aria-invalid="true"] {
  border-color: #f06f76;
}

.field-error {
  display: block;
  margin-top: 6px;
  color: #ff9da3;
  font-size: 11px;
}

.security-note,
.request-error {
  display: grid;
  gap: 5px;
  margin-bottom: 18px;
  border: 1px solid rgb(47 128 188 / 30%);
  border-radius: 7px;
  background: rgb(8 28 61 / 52%);
  padding: 13px 14px;
  color: #9eb5d5;
  font-size: 12px;
  line-height: 1.55;
}

.security-note strong,
.request-error strong {
  color: #edf6ff;
}

.request-error {
  border-color: rgb(224 95 105 / 44%);
  background: rgb(95 27 47 / 40%);
  color: #ffb2b7;
}

.submit-button {
  position: relative;
  display: flex;
  width: 100%;
  height: 50px;
  align-items: center;
  justify-content: center;
  border: 0;
  border: 1px solid rgb(194 235 255 / 28%);
  border-radius: 14px;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 18%), transparent 46%),
    linear-gradient(100deg, rgb(0 143 220 / 92%), rgb(53 175 229 / 88%));
  padding: 0 18px;
  color: #f5fbff;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: 0 12px 28px rgb(0 105 183 / 22%);
  backdrop-filter: blur(12px) saturate(145%);
  -webkit-backdrop-filter: blur(12px) saturate(145%);
  transition: transform 180ms ease, box-shadow 180ms ease, filter 180ms ease;
}

.submit-button__content {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}

.submit-button__icon {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.submit-button > .submit-button__arrow {
  position: absolute;
  right: 18px;
}

.submit-button::after {
  display: none;
}

.submit-button:hover:not(:disabled) {
  filter: brightness(1.08);
  transform: translateY(-1px);
  box-shadow: 0 16px 34px rgb(0 105 183 / 28%);
}

.submit-button:focus-visible {
  outline: 3px solid rgb(58 177 238 / 32%);
  outline-offset: 3px;
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.62;
}

.complete-state {
  display: grid;
  justify-items: center;
  gap: 20px;
  color: #bed0e8;
  text-align: center;
}

.complete-state__icon {
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  border-radius: 50%;
  background: #1d9f7a;
  color: #f5fbff;
  font-size: 26px;
}

.complete-state p {
  margin: 0;
  color: #a9bdd7;
  line-height: 1.7;
}

.form-footer {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  margin-top: 28px;
  border-top: 1px solid rgb(118 154 204 / 20%);
  padding-top: 18px;
  color: #7991b2;
  font-size: 11px;
}

.form-footer span:first-child {
  color: #a9bdd7;
  font-weight: 600;
}

.service-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.service-status__icon {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: #43d5a2;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.65;
  filter: drop-shadow(0 0 4px rgb(48 196 141 / 32%));
}

/* 矿石日光主题：用中明度岩灰蓝承托场景，玻璃仅做材质而不做发光层。 */
.login-shell--light {
  background:
    radial-gradient(ellipse at 31% 51%, rgb(47 65 82 / 24%), transparent 39%),
    linear-gradient(108deg, #a7b6c3 0%, #bdc9d3 54%, #c8d3dc 72%, #acbdca 100%);
  color: #142a3e;
}

.login-shell--light::before {
  background:
    repeating-linear-gradient(168deg, transparent 0 132px, rgb(52 72 89 / 3%) 133px, transparent 134px 258px),
    linear-gradient(90deg, rgb(36 51 66 / 12%), transparent 48%, rgb(41 65 85 / 8%)),
    radial-gradient(circle at 75% 43%, rgb(219 232 240 / 22%), transparent 28%);
}

.login-shell--light .theme-switch {
  border-color: rgb(32 57 78 / 22%);
  background: rgb(207 220 229 / 42%);
  color: #244761;
  box-shadow:
    inset 0 1px 0 rgb(241 247 250 / 58%),
    0 7px 18px rgb(42 59 73 / 12%);
}

.login-shell--light .theme-switch:hover {
  border-color: rgb(31 76 103 / 34%);
  background: rgb(220 231 238 / 58%);
}

.login-shell--light .mine-scene-asset {
  opacity: 0.96;
  filter: saturate(0.94) contrast(1.06) brightness(0.98) drop-shadow(0 32px 46px rgb(37 50 62 / 30%));
}

.login-shell--light .brand-stage {
  color: #152b42;
}

.login-shell--light .brand-stage h1 {
  text-shadow: 0 8px 20px rgb(40 55 69 / 16%);
}

.login-shell--light .brand-stage p {
  color: #076f98;
}

.login-shell--light .form-panel::before {
  background:
    radial-gradient(circle at 28% 20%, rgb(225 236 242 / 38%), transparent 29%),
    radial-gradient(circle at 74% 68%, rgb(77 111 137 / 27%), transparent 40%),
    radial-gradient(circle at 24% 80%, rgb(109 134 153 / 20%), transparent 27%);
  opacity: 0.62;
  filter: blur(28px);
}

.login-shell--light .form-card {
  border-color: rgb(238 246 250 / 48%);
  background:
    linear-gradient(145deg, rgb(229 239 245 / 32%) 0%, rgb(187 207 220 / 20%) 46%, rgb(105 135 158 / 15%) 100%),
    rgb(166 189 205 / 30%);
  box-shadow:
    0 30px 72px rgb(37 54 68 / 28%),
    0 7px 22px rgb(48 72 90 / 11%),
    inset 0 1px 0 rgb(245 250 252 / 58%),
    inset 0 -1px 0 rgb(68 99 123 / 16%);
  backdrop-filter: blur(22px) saturate(122%) contrast(1.04);
  -webkit-backdrop-filter: blur(22px) saturate(122%) contrast(1.04);
}

.login-shell--light .form-card::before {
  background: linear-gradient(132deg, rgb(244 249 251 / 24%) 0%, transparent 26% 76%, rgb(54 100 130 / 8%) 100%);
}

.login-shell--light .form-card::after {
  border-color: rgb(239 247 250 / 30%);
  box-shadow:
    inset 10px 9px 18px rgb(241 248 251 / 14%),
    inset -13px -18px 26px rgb(53 82 105 / 12%);
}

.login-shell--light .glass-refraction {
  background: radial-gradient(ellipse at 24% 10%, rgb(241 248 251 / 24%), transparent 35%);
  opacity: 0.34;
}

.login-shell--light .form-header h2,
.login-shell--light .security-note strong,
.login-shell--light .request-error strong {
  color: #162f49;
}

.login-shell--light .form-header p {
  color: #435f77;
}

.login-shell--light .field > span {
  color: #29465e;
}

.login-shell--light .field input {
  border-color: rgb(48 78 101 / 27%);
  background:
    linear-gradient(180deg, rgb(232 240 245 / 62%), rgb(205 219 228 / 43%)),
    rgb(215 228 236 / 48%);
  color: #132d43;
  box-shadow:
    inset 0 1px 0 rgb(244 249 251 / 56%),
    inset 0 -1px 0 rgb(54 84 107 / 12%),
    0 5px 14px rgb(45 65 82 / 8%);
}

.login-shell--light .field input::placeholder {
  color: #607a90;
}

.login-shell--light .field input:focus {
  border-color: rgb(8 111 152 / 54%);
  background: rgb(226 238 244 / 72%);
  box-shadow:
    inset 0 1px 0 rgb(246 250 252 / 62%),
    0 0 0 4px rgb(8 111 152 / 12%),
    0 10px 22px rgb(42 65 82 / 10%);
}

.login-shell--light .field-control__icon {
  stroke: #547591;
}

.login-shell--light .field-control:focus-within .field-control__icon {
  stroke: #076f98;
  filter: none;
}

.login-shell--light .submit-button {
  border-color: rgb(222 241 248 / 30%);
  background:
    linear-gradient(180deg, rgb(240 250 252 / 12%), transparent 48%),
    linear-gradient(100deg, #087cac, #1590b8);
  box-shadow: 0 12px 24px rgb(3 85 122 / 22%);
}

.login-shell--light .submit-button:hover:not(:disabled) {
  box-shadow: 0 15px 30px rgb(3 85 122 / 27%);
}

.login-shell--light .form-footer {
  border-top-color: rgb(47 78 101 / 18%);
  color: #506c82;
}

.login-shell--light .form-footer span:first-child {
  color: #294a61;
}

@keyframes form-reveal {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes mine-scene-breathe {
  from {
    opacity: 0.78;
    transform: translateY(-50%) scale(0.99);
  }

  to {
    opacity: 0.88;
    transform: translateY(-51%) scale(1.012);
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}

@media (max-width: 920px) {
  :global(body) {
    overflow: auto;
  }

  .login-shell {
    grid-template-columns: 1fr;
  }

  .mine-scene-asset {
    top: 28%;
    left: -22vw;
    width: 128vw;
    opacity: 0.3;
  }

  .brand-stage {
    min-height: 34vh;
    padding: 54px 24px 20px;
  }

  .brand-stage h1 {
    margin-top: 20px;
  }

  .form-panel {
    min-height: 66vh;
    padding: 24px 20px 56px;
  }

  .form-card {
    left: 0;
    width: min(100%, 438px);
  }
}

@media (max-width: 520px) {
  .brand-stage p {
    font-size: 11px;
  }

  .form-card {
    border-radius: 14px;
    padding: 34px 24px 28px;
  }

  .form-footer {
    flex-direction: column;
    gap: 7px;
  }
}
</style>
