<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import SystemLogo from '@/components/common/system-logo.vue';
import { changePassword, login } from '../../api/auth';
import { clearAuthSession, saveAuthSession } from '../../auth/session';

type LoginStage = 'login' | 'password' | 'complete';

interface DisplayError {
  message: string;
  traceId?: string;
}

interface NetworkNode {
  x: number;
  y: number;
  velocityX: number;
  velocityY: number;
  radius: number;
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
const networkCanvas = ref<HTMLCanvasElement | null>(null);
let stopNetwork: (() => void) | undefined;

const title = computed(() => {
  if (stage.value === 'password') return '修改初始密码';
  if (stage.value === 'complete') return '密码修改成功';
  return '欢迎登录';
});

onMounted(() => {
  if (networkCanvas.value) stopNetwork = startParticleNetwork(networkCanvas.value);
});

onBeforeUnmount(() => stopNetwork?.());

function startParticleNetwork(canvas: HTMLCanvasElement) {
  const context = canvas.getContext('2d');
  if (!context) return () => undefined;

  const nodes: NetworkNode[] = [];
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  let width = 0;
  let height = 0;
  let animationFrame = 0;

  const resize = () => {
    const bounds = canvas.getBoundingClientRect();
    width = bounds.width || window.innerWidth || 1440;
    height = bounds.height || window.innerHeight || 900;
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);

    if (!nodes.length) {
      const count = Math.max(42, Math.min(78, Math.round((width * height) / 24000)));
      for (let index = 0; index < count; index += 1) {
        nodes.push({
          x: ((index * 71) % count) / count * width,
          y: ((index * 43) % count) / count * height,
          velocityX: ((index % 7) - 3) * 0.035,
          velocityY: ((index % 5) - 2) * 0.04,
          radius: 1.1 + (index % 4) * 0.45
        });
      }

      // 固定一组近距离节点，确保低分辨率或稀疏节点时仍能表达连接关系。
      nodes[1].x = nodes[0].x + Math.min(72, width * 0.08);
      nodes[1].y = nodes[0].y + Math.min(48, height * 0.08);
    }
  };

  const draw = () => {
    context.clearRect(0, 0, width, height);

    for (let sourceIndex = 0; sourceIndex < nodes.length; sourceIndex += 1) {
      const source = nodes[sourceIndex];
      for (let targetIndex = sourceIndex + 1; targetIndex < nodes.length; targetIndex += 1) {
        const target = nodes[targetIndex];
        const distance = Math.hypot(source.x - target.x, source.y - target.y);
        if (distance > 175) continue;

        context.beginPath();
        context.moveTo(source.x, source.y);
        context.lineTo(target.x, target.y);
        context.strokeStyle = `rgb(31 132 210 / ${0.17 * (1 - distance / 175)})`;
        context.lineWidth = 0.7;
        context.stroke();
      }

      context.beginPath();
      context.arc(source.x, source.y, source.radius, 0, Math.PI * 2);
      context.fillStyle = 'rgb(29 142 219 / 0.76)';
      context.fill();

      if (!reduceMotion) {
        source.x += source.velocityX;
        source.y += source.velocityY;
        if (source.x < 0 || source.x > width) source.velocityX *= -1;
        if (source.y < 0 || source.y > height) source.velocityY *= -1;
      }
    }

    if (!reduceMotion) animationFrame = requestAnimationFrame(draw);
  };

  resize();
  draw();
  window.addEventListener('resize', resize);

  return () => {
    window.removeEventListener('resize', resize);
    cancelAnimationFrame(animationFrame);
  };
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
  <main class="login-shell">
    <canvas ref="networkCanvas" class="network-canvas" aria-hidden="true"></canvas>

    <section class="brand-stage" aria-label="平台介绍">
      <div class="brand-stage__mark" aria-hidden="true">
        <SystemLogo class="brand-stage__logo" />
      </div>
      <h1>智慧矿山数据平台</h1>
      <p>设备智联 · 数据洞察 · 生产协同</p>
    </section>

    <section class="form-panel">
      <div class="form-card login-card">
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

.network-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
}

.brand-stage {
  position: relative;
  z-index: 1;
  display: grid;
  align-content: center;
  justify-items: center;
  padding: 0 8vw 2vh 4vw;
  color: #f4f8ff;
  text-align: center;
}

.brand-stage__mark,
.form-header__mark {
  display: grid;
  place-items: center;
  border: 1px solid rgb(92 139 209 / 22%);
  background: rgb(35 55 99 / 74%);
  box-shadow: 0 18px 48px rgb(0 5 24 / 34%);
}

.brand-stage__mark {
  width: 66px;
  height: 66px;
  border-radius: 17px;
}

.brand-stage__logo {
  width: 54px;
  height: 46px;
}

.form-header__logo {
  width: 48px;
  height: 40px;
}

.brand-stage h1 {
  margin: 28px 0 10px;
  font-size: clamp(30px, 2.25vw, 42px);
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
  z-index: 1;
  display: grid;
  min-width: 0;
  min-height: 100vh;
  place-items: center;
  overflow: visible;
  padding: 48px clamp(44px, 6vw, 104px) 48px 0;
  background: transparent;
}

.form-panel::before {
  display: none;
}

.form-card {
  position: relative;
  left: 0;
  box-sizing: border-box;
  width: min(100%, 438px);
  border: 1px solid rgb(27 102 160 / 46%);
  border-radius: 18px;
  background: rgb(18 50 93 / 84%);
  padding: 44px 42px 34px;
  box-shadow:
    0 28px 80px rgb(0 6 30 / 30%),
    inset 0 1px 0 rgb(120 185 235 / 8%);
  backdrop-filter: blur(12px);
  animation: form-reveal 760ms cubic-bezier(0.16, 1, 0.3, 1) 100ms both;
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

.field input {
  box-sizing: border-box;
  width: 100%;
  height: 48px;
  border: 1px solid rgb(108 143 193 / 44%);
  border-radius: 7px;
  outline: none;
  background: #dce7f8;
  padding: 0 15px;
  color: #17213b;
  font: inherit;
  box-shadow: inset 0 1px 3px rgb(15 35 67 / 12%);
  transition: border-color 180ms ease, box-shadow 180ms ease, background 180ms ease;
}

.field input::placeholder {
  color: #71819c;
}

.field input:focus {
  border-color: #27a9eb;
  background: #edf4ff;
  box-shadow: 0 0 0 3px rgb(28 157 228 / 16%);
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
  border-radius: 7px;
  overflow: hidden;
  background: linear-gradient(100deg, #078fdc, #32a7df);
  padding: 0 18px;
  color: #f5fbff;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: 0 12px 28px rgb(0 105 183 / 22%);
  transition: transform 180ms ease, box-shadow 180ms ease, filter 180ms ease;
}

.submit-button span:last-child {
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

.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 7px;
  border-radius: 50%;
  background: #30c48d;
  box-shadow: 0 0 0 3px rgb(48 196 141 / 12%);
}

@keyframes form-reveal {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
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
