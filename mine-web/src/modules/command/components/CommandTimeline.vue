<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { NTimeline, NTimelineItem } from 'naive-ui';
import type { Command, CommandStatus } from '@/modules/command/api/command';

const props = defineProps<{ command: Command }>();
const { t, locale } = useI18n();

const events = computed(() => {
  const result: Array<{
    key: string;
    title: string;
    time: string;
    type: 'default' | 'info' | 'success' | 'warning' | 'error';
  }> = [];
  result.push({ key: 'queued', title: t('mine.commands.timeline.queued'), time: props.command.queuedAt, type: 'info' });
  if (props.command.sentAt && props.command.status !== 'PUBLISHED') {
    result.push({ key: 'sent', title: t('mine.commands.timeline.sent'), time: props.command.sentAt, type: 'info' });
  }
  if (props.command.deviceReceivedAt) {
    result.push({
      key: 'received',
      title: t('mine.commands.timeline.received'),
      time: props.command.deviceReceivedAt,
      type: 'info'
    });
  }
  if (props.command.completedAt) {
    result.push({
      key: 'completed',
      title: statusLabel(props.command.status),
      time: props.command.completedAt,
      type: statusType(props.command.status)
    });
  }
  return result;
});

function statusLabel(status: CommandStatus) {
  return t(`mine.commands.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function statusType(status: CommandStatus) {
  if (status === 'SUCCEEDED' || status === 'PUBLISHED') return 'success' as const;
  if (status === 'REFUSED' || status === 'EXPIRED' || status === 'TIMEOUT') return 'warning' as const;
  if (status === 'FAILED' || status === 'CANCELLED') return 'error' as const;
  return 'default' as const;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}
</script>

<template>
  <NTimeline>
    <NTimelineItem
      v-for="event in events"
      :key="event.key"
      :title="event.title"
      :content="formatTime(event.time)"
      :type="event.type"
    />
  </NTimeline>
</template>
