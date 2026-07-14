<script setup lang="ts">
import { useAuthStore } from '@/store/modules/auth';

const authStore = useAuthStore();
</script>

<template>
  <main class="overview">
    <header class="overview-header">
      <div>
        <span class="overview-header__eyebrow">MINE OPERATIONS / OVERVIEW</span>
        <h1>矿区运行总览</h1>
        <p>{{ authStore.userInfo.displayName || authStore.userInfo.userName }}，这里将汇总设备接入、数据链路与现场告警。</p>
      </div>
      <div class="environment-badge"><i></i>矿区本地环境</div>
    </header>

    <section class="metric-grid" aria-label="核心运行指标">
      <article>
        <span>在线设备</span>
        <strong>—</strong>
        <small>等待设备台账接入</small>
      </article>
      <article>
        <span>今日遥测</span>
        <strong>—</strong>
        <small>等待遥测服务接入</small>
      </article>
      <article>
        <span>活动告警</span>
        <strong>—</strong>
        <small>等待告警规则接入</small>
      </article>
      <article>
        <span>弱网积压</span>
        <strong>—</strong>
        <small>等待边缘节点接入</small>
      </article>
    </section>

    <section class="workspace-grid">
      <article class="panel pipeline-panel">
        <header>
          <div>
            <span class="panel__index">01 / 数据链路</span>
            <h2>采集链路状态</h2>
          </div>
          <span class="pending-tag">待接入运行指标</span>
        </header>
        <div class="pipeline" aria-label="设备数据处理链路">
          <div><i class="pending"></i><strong>设备与车辆</strong><span>MQTT / TCP</span></div>
          <b>→</b>
          <div><i class="ready"></i><strong>接入网关</strong><span>mine-gateway</span></div>
          <b>→</b>
          <div><i class="ready"></i><strong>消息中枢</strong><span>RabbitMQ</span></div>
          <b>→</b>
          <div><i class="ready"></i><strong>遥测处理</strong><span>mine-telemetry</span></div>
        </div>
        <p class="panel-note">接入真实设备后，本区域显示吞吐、延迟、离线缓存和失败重试情况。</p>
      </article>

      <article class="panel readiness-panel">
        <header>
          <div>
            <span class="panel__index">02 / 建设状态</span>
            <h2>Phase 0 基础能力</h2>
          </div>
        </header>
        <ul>
          <li><i></i><span>管理端身份认证</span><strong>已就绪</strong></li>
          <li><i></i><span>首次登录强制改密</span><strong>已就绪</strong></li>
          <li><i></i><span>RabbitMQ 双虚拟主机</span><strong>已就绪</strong></li>
          <li class="pending"><i></i><span>设备与物模型管理</span><strong>待建设</strong></li>
        </ul>
      </article>
    </section>
  </main>
</template>

<style scoped>
.overview {
  --mine-orange: #ed6500;
  --mine-coal: #282620;
  min-height: 100%;
  color: var(--mine-coal);
}

.overview-header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.overview-header__eyebrow,
.panel__index {
  color: var(--mine-orange);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.overview-header h1 {
  margin: 8px 0 6px;
  font-size: clamp(28px, 3vw, 42px);
  letter-spacing: -0.04em;
}

.overview-header p,
.panel-note {
  margin: 0;
  color: #736d63;
}

.environment-badge,
.pending-tag {
  border: 1px solid #d7d0c4;
  background: #fff;
  padding: 9px 12px;
  color: #625d54;
  font-size: 12px;
  white-space: nowrap;
}

.environment-badge i {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 8px;
  border-radius: 50%;
  background: #1c9959;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.metric-grid article,
.panel {
  border: 1px solid #e2ddd4;
  background: #fff;
  box-shadow: 0 8px 24px rgb(50 45 37 / 4%);
}

.metric-grid article {
  padding: 22px;
}

.metric-grid span,
.metric-grid small {
  display: block;
  color: #7b756b;
}

.metric-grid strong {
  display: block;
  margin: 14px 0 8px;
  font-size: 32px;
}

.metric-grid small {
  font-size: 12px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(320px, 0.8fr);
  gap: 16px;
  margin-top: 16px;
}

.panel {
  min-height: 280px;
  padding: 24px;
}

.panel > header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid #ece8e1;
  padding-bottom: 18px;
}

.panel h2 {
  margin: 7px 0 0;
  font-size: 20px;
}

.pending-tag {
  border-color: #f2cfb5;
  background: #fff7f0;
  color: #ad4a05;
}

.pipeline {
  display: grid;
  grid-template-columns: repeat(7, auto);
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 38px 0 32px;
}

.pipeline div {
  display: grid;
  min-width: 112px;
  gap: 6px;
}

.pipeline i,
.readiness-panel li i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1c9959;
  box-shadow: 0 0 0 4px rgb(28 153 89 / 10%);
}

.pipeline i.pending,
.readiness-panel li.pending i {
  background: #d4a047;
  box-shadow: 0 0 0 4px rgb(212 160 71 / 12%);
}

.pipeline span {
  color: #8a8378;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
}

.pipeline b {
  color: #aaa397;
  font-weight: 400;
}

.panel-note {
  border-left: 3px solid var(--mine-orange);
  background: #f7f4ef;
  padding: 12px 14px;
  font-size: 13px;
}

.readiness-panel ul {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.readiness-panel li {
  display: grid;
  grid-template-columns: 12px 1fr auto;
  align-items: center;
  gap: 11px;
  border-bottom: 1px solid #f0ece6;
  padding: 15px 2px;
}

.readiness-panel li strong {
  color: #1c7f4d;
  font-size: 12px;
}

.readiness-panel li.pending strong {
  color: #9b6c1e;
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workspace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .overview-header {
    align-items: start;
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }

  .pipeline {
    grid-template-columns: 1fr;
  }

  .pipeline b {
    transform: rotate(90deg);
  }
}
</style>
