<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import {
  NAlert,
  NAvatar,
  NButton,
  NCard,
  NDivider,
  NFormItem,
  NH1,
  NInput,
  NResult,
  NSpace,
  NTag,
  NText,
  NThing
} from 'naive-ui';
import LangSwitch from '@/components/common/lang-switch.vue';
import SystemLogo from '@/components/common/system-logo.vue';
import ThemeSchemaSwitch from '@/components/common/theme-schema-switch.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { changePassword, login } from '../../api/auth';
import { clearAuthSession, saveAuthSession } from '../../auth/session';

type LoginStage = 'login' | 'password' | 'complete';

interface DisplayError {
  message: string;
  traceId?: string;
}

const { t } = useI18n();
const router = useRouter();
const appStore = useAppStore();
const themeStore = useThemeStore();
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

const title = computed(() => t(`mine.login.title.${stage.value}`));
const description = computed(() => t(`mine.login.description.${stage.value}`));
const submitLabel = computed(() => {
  if (submitting.value) return t('mine.login.action.processing');
  return stage.value === 'login' ? t('mine.login.action.login') : t('mine.login.action.changePassword');
});

async function submit() {
  if (submitting.value) return;
  error.value = null;
  clearFieldErrors();
  if (stage.value === 'login') await submitLogin();
  if (stage.value === 'password') await submitPasswordChange();
}

async function submitLogin() {
  if (!loginForm.username.trim()) fieldErrors.username = t('mine.login.validation.usernameRequired');
  if (!loginForm.password) fieldErrors.password = t('mine.login.validation.passwordRequired');
  if (hasFieldErrors()) return;

  submitting.value = true;
  try {
    const response = await login({ username: loginForm.username.trim(), password: loginForm.password });
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
  if (!passwordForm.currentPassword) fieldErrors.currentPassword = t('mine.login.validation.currentPasswordRequired');
  if (!passwordForm.newPassword) {
    fieldErrors.newPassword = t('mine.login.validation.newPasswordRequired');
  } else if (passwordForm.newPassword.length < 12) {
    fieldErrors.newPassword = t('mine.login.validation.passwordTooShort');
  } else if (new TextEncoder().encode(passwordForm.newPassword).length > 72) {
    fieldErrors.newPassword = t('mine.login.validation.passwordTooLong');
  }
  if (!passwordForm.confirmPassword) {
    fieldErrors.confirmPassword = t('mine.login.validation.confirmPasswordRequired');
  } else if (passwordForm.confirmPassword !== passwordForm.newPassword) {
    fieldErrors.confirmPassword = t('mine.login.validation.passwordMismatch');
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
      message: typeof candidate.message === 'string' ? candidate.message : t('mine.common.requestFailed'),
      traceId: typeof candidate.traceId === 'string' ? candidate.traceId : undefined
    };
  }
  return { message: t('mine.common.requestFailed') };
}
</script>

<template>
  <main class="min-h-screen flex items-center justify-center bg-layout p-4 sm:p-8">
    <div class="absolute right-4 top-4 flex items-center gap-2 sm:right-8 sm:top-8">
      <LangSwitch :lang="appStore.locale" :lang-options="appStore.localeOptions" @change-lang="appStore.changeLocale" />
      <ThemeSchemaSwitch
        data-login-theme-switch
        :theme-schema="themeStore.themeScheme"
        @switch="themeStore.toggleThemeScheme"
      />
    </div>

    <NCard class="w-full max-w-480px" size="large" :bordered="false">
      <NSpace vertical :size="24">
        <NThing>
          <template #avatar>
            <NAvatar round size="large">
              <SystemLogo class="h-8 w-8" />
            </NAvatar>
          </template>
          <template #header>{{ t('mine.login.brand') }}</template>
          <template #description>{{ t('mine.login.slogan') }}</template>
        </NThing>

        <div>
          <NH1 class="m-0 text-2xl">{{ title }}</NH1>
          <NText depth="3">{{ description }}</NText>
        </div>

        <NAlert v-if="stage === 'password'" type="warning" :title="t('mine.login.security.title')">
          {{ t('mine.login.security.description') }}
        </NAlert>

        <NAlert v-if="error" type="error" :title="error.message">
          <template v-if="error.traceId">{{ t('mine.common.traceId', { traceId: error.traceId }) }}</template>
        </NAlert>

        <form v-if="stage !== 'complete'" novalidate @submit.prevent="submit">
          <template v-if="stage === 'login'">
            <NFormItem
              :label="t('mine.login.field.username')"
              :validation-status="fieldErrors.username ? 'error' : undefined"
              :feedback="fieldErrors.username"
            >
              <NInput
                v-model:value="loginForm.username"
                :input-props="{ name: 'username', autocomplete: 'username' }"
                maxlength="64"
                :placeholder="t('mine.login.placeholder.username')"
              >
                <template #prefix><SvgIcon icon="material-symbols:person-outline" /></template>
              </NInput>
            </NFormItem>
            <NFormItem
              :label="t('mine.login.field.password')"
              :validation-status="fieldErrors.password ? 'error' : undefined"
              :feedback="fieldErrors.password"
            >
              <NInput
                v-model:value="loginForm.password"
                type="password"
                show-password-on="click"
                :input-props="{ name: 'password', autocomplete: 'current-password' }"
                maxlength="256"
                :placeholder="t('mine.login.placeholder.password')"
              >
                <template #prefix><SvgIcon icon="material-symbols:lock-outline" /></template>
              </NInput>
            </NFormItem>
          </template>

          <template v-else>
            <NFormItem
              :label="t('mine.login.field.currentPassword')"
              :validation-status="fieldErrors.currentPassword ? 'error' : undefined"
              :feedback="fieldErrors.currentPassword"
            >
              <NInput
                v-model:value="passwordForm.currentPassword"
                type="password"
                show-password-on="click"
                :input-props="{ name: 'currentPassword', autocomplete: 'current-password' }"
              />
            </NFormItem>
            <NFormItem
              :label="t('mine.login.field.newPassword')"
              :validation-status="fieldErrors.newPassword ? 'error' : undefined"
              :feedback="fieldErrors.newPassword"
            >
              <NInput
                v-model:value="passwordForm.newPassword"
                type="password"
                show-password-on="click"
                :input-props="{ name: 'newPassword', autocomplete: 'new-password' }"
              />
            </NFormItem>
            <NFormItem
              :label="t('mine.login.field.confirmPassword')"
              :validation-status="fieldErrors.confirmPassword ? 'error' : undefined"
              :feedback="fieldErrors.confirmPassword"
            >
              <NInput
                v-model:value="passwordForm.confirmPassword"
                type="password"
                show-password-on="click"
                :input-props="{ name: 'confirmPassword', autocomplete: 'new-password' }"
              />
            </NFormItem>
          </template>

          <NButton block type="primary" attr-type="submit" :loading="submitting">
            <template #icon><SvgIcon icon="material-symbols:login" /></template>
            {{ submitLabel }}
          </NButton>
        </form>

        <NResult
          v-else
          status="success"
          :title="t('mine.login.complete.title')"
          :description="t('mine.login.complete.description')"
        >
          <template #footer>
            <NButton type="primary" @click="restartLogin">{{ t('mine.login.action.restart') }}</NButton>
          </template>
        </NResult>

        <NDivider />
        <NSpace justify="space-between">
          <NTag type="success" :bordered="false">{{ t('mine.login.service.local') }}</NTag>
          <NText depth="3">{{ t('mine.login.service.help') }}</NText>
        </NSpace>
      </NSpace>
    </NCard>
  </main>
</template>
