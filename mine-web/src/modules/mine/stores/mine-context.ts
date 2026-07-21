import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { $t } from '@/locales';
import { getMineContext } from '../api/mine-api';
import type { MineContextResponse } from '../api/mine-api';

type OrganizationContext = MineContextResponse['organizations'][number];

/**
 * 维护当前会话的矿区作用域。
 *
 * 授权候选只来自服务端 `/mine/context`，不持久化到浏览器，避免撤权后继续使用过期上下文。
 */
export const useMineContextStore = defineStore('mine-context', () => {
  const organizations = ref<OrganizationContext[]>([]);
  const selectedSiteId = ref<string | null>(null);
  const loading = ref(false);
  const sites = computed(() => organizations.value.flatMap(item => item.sites));
  const selectedSite = computed(() => sites.value.find(site => site.id === selectedSiteId.value) ?? null);

  /** 重新向服务端校验当前用户的矿区授权。 */
  async function refreshContext() {
    const previousSiteId = selectedSiteId.value;
    loading.value = true;
    try {
      const response = await getMineContext();
      organizations.value = response.organizations;
      if (previousSiteId && !sites.value.some(site => site.id === previousSiteId)) {
        selectedSiteId.value = null;
        window.$message?.warning($t('mine.context.scopeRevoked'));
      }
      return response;
    } catch (error) {
      organizations.value = [];
      selectedSiteId.value = null;
      throw error;
    } finally {
      loading.value = false;
    }
  }

  /** 只选择服务端最近一次授权快照中的矿区。 */
  function selectSite(siteId: string | null) {
    if (siteId === null) {
      selectedSiteId.value = null;
      return true;
    }
    if (!sites.value.some(site => site.id === siteId)) {
      selectedSiteId.value = null;
      window.$message?.warning($t('mine.context.invalidSelection'));
      return false;
    }
    selectedSiteId.value = siteId;
    return true;
  }

  /** 清理当前会话矿区上下文。 */
  function reset() {
    organizations.value = [];
    selectedSiteId.value = null;
  }

  return { organizations, sites, selectedSiteId, selectedSite, loading, refreshContext, selectSite, reset };
});
