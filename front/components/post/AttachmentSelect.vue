<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import { MAX_ATTACHMENTS, attachmentFileKey, formatFileSize } from "~/utils/post";

const detail = usePostDetailStore();
</script>

<template>
  <label class="field">
    <span>첨부파일</span>
    <input
      :key="detail.postAttachmentInputKey"
      type="file"
      multiple
      @change="detail.handleCreateAttachmentChange($event)"
    />
  </label>
  <p class="section-meta">
    첨부파일은 최대 {{ MAX_ATTACHMENTS }}개, 파일당 100MB까지 업로드할 수 있습니다.
    (선택: {{ detail.postAttachmentFiles.length }}/{{ MAX_ATTACHMENTS }})
  </p>
  <ul v-if="detail.postAttachmentFiles.length > 0" class="attachment-select-list">
    <li
      v-for="file in detail.postAttachmentFiles"
      :key="attachmentFileKey(file)"
      class="attachment-select-item"
    >
      <span>{{ file.name }} ({{ formatFileSize(file.size) }})</span>
      <button type="button" class="ghost-button" @click="detail.removeCreateAttachment(attachmentFileKey(file))">
        제거
      </button>
    </li>
  </ul>
</template>
