<script setup lang="ts">
import { getApiUrl } from "~/services/api";
import { formatFileSize, isFileConversionMode } from "~/utils/post";
import type { Attachment, PostMode } from "~/types/api";

const props = defineProps<{
  attachments: Attachment[];
  mode: PostMode;
}>();
</script>

<template>
  <div class="attachment-panel">
    <span class="attachment-label">
      {{ isFileConversionMode(props.mode) ? "복원 파일" : `첨부파일 (${props.attachments.length})` }}
    </span>
    <div v-for="attachment in props.attachments" :key="attachment.id" class="attachment-card">
      <div>
        <strong>{{ attachment.originalFilename }}</strong>
        <p class="section-meta">
          {{ formatFileSize(attachment.size) }}{{ attachment.contentType ? ` · ${attachment.contentType}` : "" }}
        </p>
      </div>
      <a
        class="ghost-button attachment-link"
        :href="getApiUrl(attachment.downloadUrl)"
        :download="attachment.originalFilename"
      >
        다운로드
      </a>
    </div>
  </div>
</template>
