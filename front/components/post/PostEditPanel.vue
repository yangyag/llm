<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import { getApiUrl } from "~/services/api";
import {
  MAX_ATTACHMENTS,
  attachmentFileKey,
  formatFileSize,
  getPostBodyHelp,
  getPostBodyLabel
} from "~/utils/post";

const detail = usePostDetailStore();

function onTitle(event: Event) {
  detail.postEditForm.title = (event.target as HTMLInputElement).value;
}
function onBody(event: Event) {
  detail.postEditForm.body = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form class="form-grid compact-form action-panel" @submit.prevent="detail.handleUpdatePost()">
    <label class="field">
      <span>제목</span>
      <input :value="detail.postEditForm.title" maxlength="200" required @input="onTitle" />
    </label>
    <label class="field">
      <span>{{ getPostBodyLabel() }}</span>
      <textarea :value="detail.postEditForm.body" rows="8" @input="onBody" />
    </label>
    <p class="section-meta">{{ getPostBodyHelp() }}</p>

    <div v-if="detail.selectedPost && detail.selectedPost.attachments.length > 0" class="attachment-panel">
      <span class="attachment-label">현재 첨부파일 ({{ detail.selectedPost.attachments.length }})</span>
      <div v-for="attachment in detail.selectedPost.attachments" :key="attachment.id" class="attachment-card">
        <div>
          <strong :class="{ 'attachment-marked-remove': detail.removeAttachmentIds.has(attachment.id) }">
            {{ attachment.originalFilename }}
          </strong>
          <p class="section-meta">{{ formatFileSize(attachment.size) }}</p>
        </div>
        <div class="inline-actions">
          <a
            class="ghost-button attachment-link"
            :href="getApiUrl(attachment.downloadUrl)"
            :download="attachment.originalFilename"
          >
            다운로드
          </a>
          <label class="checkbox-field">
            <input
              type="checkbox"
              :checked="detail.removeAttachmentIds.has(attachment.id)"
              @change="detail.toggleRemoveExistingAttachment(attachment.id)"
            />
            <span>삭제</span>
          </label>
        </div>
      </div>
    </div>

    <label class="field">
      <span>첨부파일 추가</span>
      <input
        :key="detail.postEditAttachmentInputKey"
        type="file"
        multiple
        @change="detail.handleEditAttachmentChange($event)"
      />
    </label>
    <p class="section-meta">
      최대 {{ MAX_ATTACHMENTS }}개까지 등록할 수 있습니다. (현재
      {{ (detail.selectedPost?.attachments ?? []).filter((a) => !detail.removeAttachmentIds.has(a.id)).length + detail.postEditAttachmentFiles.length }}/{{ MAX_ATTACHMENTS }})
    </p>
    <ul v-if="detail.postEditAttachmentFiles.length > 0" class="attachment-select-list">
      <li
        v-for="file in detail.postEditAttachmentFiles"
        :key="attachmentFileKey(file)"
        class="attachment-select-item"
      >
        <span>새 파일: {{ file.name }} ({{ formatFileSize(file.size) }})</span>
        <button type="button" class="ghost-button" @click="detail.removeEditAttachment(attachmentFileKey(file))">
          제거
        </button>
      </li>
    </ul>

    <p v-if="detail.postActionError" class="panel-error">{{ detail.postActionError }}</p>
    <div class="action-form-actions">
      <button type="submit" class="ghost-button" :disabled="detail.submitting">게시글 수정</button>
      <button type="button" class="ghost-button" @click="detail.closePostActionPanel()">취소</button>
    </div>
  </form>
</template>
