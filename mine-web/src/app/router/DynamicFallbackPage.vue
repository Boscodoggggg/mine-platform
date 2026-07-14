<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const componentKey = computed(() => route.meta.componentKey || '未配置');
</script>

<template>
  <div class="dynamic-fallback">
    <div class="dynamic-fallback__code">MODULE / NOT READY</div>
    <h1>功能模块尚未接入</h1>
    <p>当前菜单配置已生效，但组件标识“{{ componentKey }}”没有对应的本地页面。</p>
    <p>请联系平台开发人员完成组件注册后重新发布前端版本。</p>
  </div>
</template>

<style scoped>
.dynamic-fallback {
  min-height: 420px;
  padding: clamp(36px, 7vw, 88px);
  border: 1px solid rgb(22 29 25 / 10%);
  border-radius: 12px;
  background: linear-gradient(135deg, #f7f7f3, #ecefe9);
  color: #222923;
}

.dynamic-fallback__code {
  margin-bottom: 28px;
  color: #a85f1e;
  font: 650 12px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace;
  letter-spacing: 0.16em;
}

h1 {
  margin: 0 0 18px;
  font-size: clamp(28px, 4vw, 44px);
  font-weight: 520;
}

p {
  max-width: 720px;
  margin: 8px 0;
  color: #5d665f;
  font-size: 15px;
  line-height: 1.8;
}
</style>
