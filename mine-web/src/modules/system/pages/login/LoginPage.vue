<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import mineLandscapeUrl from '@/assets/images/login-mine-landscape.jpg';
import { changePassword, login } from '../../api/auth';
import { clearAuthSession, saveAuthSession } from '../../auth/session';

type LoginStage = 'login' | 'password' | 'complete';

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
const mineParticles = Array.from({ length: 144 }, (_, index) => {
  return {
    id: index,
    x: 2 + ((index * 47) % 96),
    y: 12 + ((index * 31) % 76),
    size: 1 + (index % 4) * 0.7,
    opacity: 0.3 + (index % 6) * 0.09,
    travelX: -22 + ((index * 13) % 46),
    travelY: -14 + ((index * 17) % 30),
    duration: 7 + (index % 8) * 0.9,
    delay: -(index % 18) * 0.55
  };
});

const title = computed(() => {
  if (stage.value === 'password') return '修改初始密码';
  if (stage.value === 'complete') return '密码修改成功';
  return '进入管理平台';
});

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
  <main class="login-shell">
    <section class="site-panel" aria-label="平台介绍">
      <img class="mine-visual" :src="mineLandscapeUrl" alt="" />
      <div class="site-panel__grid" aria-hidden="true"></div>
      <div class="particle-field" aria-hidden="true">
        <i
          v-for="particle in mineParticles"
          :key="particle.id"
          class="mine-particle"
          :style="{
            left: `${particle.x}%`,
            top: `${particle.y}%`,
            width: `${particle.size}px`,
            height: `${particle.size}px`,
            opacity: particle.opacity,
            '--travel-x': `${particle.travelX}px`,
            '--travel-y': `${particle.travelY}px`,
            '--particle-duration': `${particle.duration}s`,
            animationDelay: `${particle.delay}s`
          }"
        ></i>
      </div>
      <div class="site-panel__grain" aria-hidden="true"></div>
      <div class="brand">
        <span class="brand__mark" aria-hidden="true">
          <svg viewBox="0 0 48 48" role="img">
            <path d="M7 34h34v5H7zM11 30l8-17h10l8 17h-7l-3-8h-6l-3 8z" />
            <circle cx="17" cy="39" r="4" />
            <circle cx="33" cy="39" r="4" />
          </svg>
        </span>
        <span>智慧矿山数据平台</span>
      </div>
      <div class="site-copy">
        <h1><span>智联矿山</span><span>洞见现场</span></h1>
        <i aria-hidden="true"></i>
      </div>
      <div class="coordinates" aria-hidden="true"><span>E 118.3456</span><span>N 34.5678</span></div>
    </section>

    <section class="form-panel">
      <div class="form-card">
        <header class="form-header">
          <span class="form-header__index">控制台 / 01</span>
          <h2>{{ title }}</h2>
          <p v-if="stage === 'login'">使用矿区管理账号登录。连续失败将触发安全限制。</p>
          <p v-else-if="stage === 'password'">当前账号仍在使用初始密码，完成修改后需要重新登录。</p>
          <p v-else>密码修改成功，请重新登录。</p>
        </header>

        <form v-if="stage !== 'complete'" novalidate @submit.prevent="submit">
          <template v-if="stage === 'login'">
            <label class="field">
              <span>用户名</span>
              <input
                v-model="loginForm.username"
                name="username"
                autocomplete="username"
                maxlength="64"
                :aria-invalid="Boolean(fieldErrors.username)"
                :aria-describedby="fieldErrors.username ? 'username-error' : undefined"
                placeholder="请输入管理账号"
              />
              <small v-if="fieldErrors.username" id="username-error" class="field-error">{{ fieldErrors.username }}</small>
            </label>
            <label class="field">
              <span>密码</span>
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
              <input
                v-model="passwordForm.currentPassword"
                name="currentPassword"
                type="password"
                autocomplete="current-password"
                :aria-invalid="Boolean(fieldErrors.currentPassword)"
              />
              <small v-if="fieldErrors.currentPassword" class="field-error">{{ fieldErrors.currentPassword }}</small>
            </label>
            <label class="field">
              <span>新密码</span>
              <input
                v-model="passwordForm.newPassword"
                name="newPassword"
                type="password"
                autocomplete="new-password"
                :aria-invalid="Boolean(fieldErrors.newPassword)"
              />
              <small v-if="fieldErrors.newPassword" class="field-error">{{ fieldErrors.newPassword }}</small>
            </label>
            <label class="field">
              <span>确认新密码</span>
              <input
                v-model="passwordForm.confirmPassword"
                name="confirmPassword"
                type="password"
                autocomplete="new-password"
                :aria-invalid="Boolean(fieldErrors.confirmPassword)"
              />
              <small v-if="fieldErrors.confirmPassword" class="field-error">{{ fieldErrors.confirmPassword }}</small>
            </label>
          </template>

          <div v-if="error" class="request-error" role="alert">
            <strong>{{ error.message }}</strong>
            <span v-if="error.traceId">链路编号：{{ error.traceId }}</span>
          </div>

          <button class="submit-button" type="submit" :disabled="submitting">
            <span>{{ submitting ? '正在处理…' : stage === 'login' ? '登录平台' : '确认修改' }}</span>
            <span aria-hidden="true">→</span>
          </button>
        </form>

        <div v-else class="complete-state" role="status">
          <span class="complete-state__icon" aria-hidden="true">✓</span>
          <p>为确保旧令牌全部失效，系统已安全退出当前会话。</p>
          <button class="submit-button" type="button" @click="restartLogin">
            <span>重新登录</span><span aria-hidden="true">→</span>
          </button>
        </div>

        <footer class="form-footer">
          <span><i class="status-dot"></i>本地服务</span>
          <span>连接异常时请联系矿区系统管理员</span>
        </footer>
      </div>
    </section>
  </main>
</template>

<style scoped>
:global(body) {
  margin: 0;
  background: #e9ecec;
  color: #202523;
  font-family: "HarmonyOS Sans SC", MiSans, "Source Han Sans SC", "PingFang SC", sans-serif;
}

.login-shell {
  --coal: #202523;
  --coal-soft: #454c49;
  --stone: #e9ecec;
  --surface: #f7f8f7;
  --line: #c9cece;
  --signal: #c97824;
  display: grid;
  min-height: 100vh;
  grid-template-columns: minmax(480px, 1.16fr) minmax(460px, 0.84fr);
  background: var(--stone);
}

.site-panel {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  overflow: hidden;
  padding: clamp(32px, 5vw, 72px);
  background:
    radial-gradient(circle at 72% 58%, rgb(201 120 36 / 8%), transparent 32%),
    linear-gradient(145deg, #181b17, #0c0e0c 72%);
  color: #f1f3ef;
}

.site-panel::before {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgb(7 8 7 / 30%), transparent 48%, rgb(7 8 7 / 8%));
  content: "";
  pointer-events: none;
  z-index: 1;
}

.site-panel__grid {
  position: absolute;
  inset: 0;
  opacity: 0.16;
  background-image:
    linear-gradient(rgb(204 211 201 / 16%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(204 211 201 / 16%) 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: linear-gradient(145deg, black, transparent 78%);
}

.site-panel__grain {
  position: absolute;
  inset: 0;
  opacity: 0.06;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 180 180' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.72'/%3E%3C/svg%3E");
  pointer-events: none;
  z-index: 2;
}

.mine-visual {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 54% center;
  opacity: 0;
  filter: contrast(1.04) saturate(0.92);
  animation: scene-reveal 1.4s cubic-bezier(0.16, 1, 0.3, 1) 100ms forwards;
}

.particle-field {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 2;
}

.mine-particle {
  position: absolute;
  border-radius: 50%;
  background: #e29a49;
  box-shadow:
    0 0 5px rgb(218 139 55 / 88%),
    0 0 12px rgb(201 120 36 / 36%);
  transform: translate3d(0, 0, 0) scale(0.72);
  will-change: transform, opacity;
  animation: particle-drift var(--particle-duration) ease-in-out infinite alternate;
}

.brand,
.site-copy,
.coordinates {
  position: relative;
  z-index: 3;
}

.brand {
  display: flex;
  align-items: center;
  gap: 13px;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: 0.06em;
}

.brand__mark {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  background: var(--signal);
  box-shadow: 0 12px 36px rgb(201 120 36 / 18%);
}

.brand__mark svg {
  width: 26px;
  fill: #f4f5f1;
}

.site-copy {
  margin: clamp(170px, 30vh, 330px) 0 0 clamp(0px, 1.5vw, 22px);
}

.site-copy h1 {
  display: grid;
  gap: 8px;
  margin: 0;
  font-size: clamp(38px, 4vw, 62px);
  font-weight: 450;
  letter-spacing: 0.08em;
  line-height: 1.28;
  text-shadow: 0 3px 24px rgb(0 0 0 / 48%);
}

.site-copy i {
  display: block;
  width: 44px;
  height: 1px;
  margin-top: 28px;
  background: var(--signal);
}

.coordinates {
  position: absolute;
  top: clamp(130px, 18vh, 210px);
  right: clamp(34px, 5vw, 74px);
  display: grid;
  gap: 3px;
  color: #d18a3c;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 10px;
  letter-spacing: 0.13em;
}

.form-panel {
  position: relative;
  display: grid;
  min-height: 100vh;
  overflow: hidden;
  place-items: center;
  padding: clamp(32px, 6vw, 100px);
  background:
    radial-gradient(circle at 72% 18%, rgb(255 255 255 / 76%), transparent 34%),
    linear-gradient(145deg, #f1f3f2, #e5e9e9);
}

.form-panel::before {
  position: absolute;
  inset: 0;
  opacity: 0.18;
  background-image:
    linear-gradient(rgb(91 102 98 / 8%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(91 102 98 / 8%) 1px, transparent 1px);
  background-size: 80px 80px;
  content: "";
  mask-image: linear-gradient(120deg, transparent 22%, black 100%);
  pointer-events: none;
}

.form-card {
  position: relative;
  z-index: 1;
  width: min(100%, 470px);
  animation: form-reveal 760ms cubic-bezier(0.16, 1, 0.3, 1) 120ms both;
}

.form-header {
  margin-bottom: 42px;
}

.form-header__index {
  color: var(--signal);
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.form-header h2 {
  margin: 18px 0 12px;
  color: var(--coal);
  font-size: clamp(34px, 3.2vw, 48px);
  font-weight: 520;
  letter-spacing: -0.045em;
}

.form-header p {
  margin: 0;
  color: #68706d;
  font-size: 14px;
  line-height: 1.7;
}

.field {
  display: block;
  margin-bottom: 22px;
}

.field > span {
  display: block;
  margin-bottom: 9px;
  color: #414845;
  font-size: 14px;
  font-weight: 650;
}

.field input {
  box-sizing: border-box;
  width: 100%;
  height: 52px;
  border: 1px solid var(--line);
  border-radius: 0;
  outline: none;
  background: rgb(247 248 247 / 84%);
  padding: 0 16px;
  color: var(--coal);
  font: inherit;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 70%);
  transition: border-color 180ms ease, box-shadow 180ms ease, background 180ms ease;
}

.field input::placeholder {
  color: #969e9b;
}

.field input:focus {
  border-color: var(--signal);
  background: #fafbfa;
  box-shadow:
    inset 3px 0 0 rgb(201 120 36 / 82%),
    0 0 0 3px rgb(201 120 36 / 7%);
}

.field input[aria-invalid="true"] {
  border-color: #b42318;
}

.field-error {
  display: block;
  margin-top: 7px;
  color: #b42318;
  font-size: 12px;
}

.security-note,
.request-error {
  display: grid;
  gap: 5px;
  margin-bottom: 24px;
  border-left: 3px solid var(--signal);
  background: #dde2e0;
  padding: 14px 16px;
  color: #525b57;
  font-size: 13px;
  line-height: 1.55;
}

.security-note strong,
.request-error strong {
  color: var(--coal);
}

.request-error {
  border-left-color: #b42318;
  background: #fff0ed;
  color: #8a2c23;
}

.submit-button {
  position: relative;
  display: flex;
  width: 100%;
  height: 54px;
  align-items: center;
  justify-content: space-between;
  border: 0;
  overflow: hidden;
  background: linear-gradient(100deg, #252a27, #171a18);
  padding: 0 20px;
  color: #f4f5f2;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.submit-button::after {
  position: absolute;
  right: 40px;
  width: 110px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--signal));
  box-shadow: 22px 0 12px 2px rgb(201 120 36 / 24%);
  content: "";
  transform: scaleX(0.5);
  transform-origin: right;
  transition: transform 400ms cubic-bezier(0.16, 1, 0.3, 1);
}

.submit-button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 12px 30px rgb(31 37 34 / 18%);
}

.submit-button:hover:not(:disabled)::after {
  transform: scaleX(1);
}

.submit-button:focus-visible {
  outline: 3px solid rgb(237 101 0 / 28%);
  outline-offset: 3px;
}

.submit-button:disabled {
  cursor: wait;
  opacity: 0.62;
}

.complete-state {
  display: grid;
  gap: 22px;
}

.complete-state__icon {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  background: #1b7f4b;
  color: #f5f7f4;
  font-size: 28px;
}

.complete-state p {
  margin: 0;
  color: #59615e;
  line-height: 1.7;
}

.form-footer {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-top: 34px;
  border-top: 1px solid var(--line);
  padding-top: 18px;
  color: #7b8581;
  font-size: 12px;
}

.form-footer span:first-child {
  color: #48504d;
  font-weight: 700;
}

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 7px;
  border-radius: 50%;
  background: #1b9b5a;
  box-shadow: 0 0 0 3px rgb(27 155 90 / 13%);
}

@keyframes scene-reveal {
  from { opacity: 0; transform: scale(1.025); }
  to { opacity: 1; transform: scale(1); }
}

@keyframes form-reveal {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes particle-drift {
  0% { transform: translate3d(0, 0, 0) scale(0.72); filter: brightness(0.82); }
  48% { opacity: 0.92; }
  100% { transform: translate3d(var(--travel-x), var(--travel-y), 0) scale(1.28); filter: brightness(1.22); }
}

@media (max-width: 920px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .site-panel {
    min-height: 46vh;
    padding: 28px;
  }

  .site-copy {
    margin: auto 0 22px;
  }

  .site-copy h1 {
    gap: 2px;
    font-size: clamp(34px, 8vw, 52px);
  }

  .form-panel {
    min-height: auto;
    padding: 52px 24px 64px;
  }
}

@media (max-width: 520px) {
  .site-panel {
    padding: 22px;
  }

  .coordinates {
    display: none;
  }

  .form-panel {
    padding-inline: 20px;
  }

  .form-footer {
    flex-direction: column;
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }

  .mine-particle {
    animation: none !important;
  }
}
</style>
