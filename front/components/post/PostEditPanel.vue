<script setup lang="ts">
import { computed, onBeforeUnmount } from "vue";
import AttachmentDropzone from "./AttachmentDropzone.vue";
import PostDocumentEditor from "./PostDocumentEditor.client.vue";
import { usePostDetailStore } from "~/stores/postDetail";
import { getApiUrl } from "~/services/api";
import {
  buildInlineImageSources,
  collectInlineImageKeys,
  filterDownloadAttachments
} from "~/utils/postDocument";
import { useInlineImageDraft } from "~/composables/useInlineImageDraft";
import type { InlineImageDraftRegistrationResult } from "~/composables/useInlineImageDraft";
import {
  MAX_ATTACHMENTS,
  attachmentFileKey,
  formatFileSize,
  getPostBodyHelp,
  getPostBodyLabel
} from "~/utils/post";

const detail = usePostDetailStore();

const downloadAttachments = computed(() =>
  filterDownloadAttachments(detail.selectedPost?.attachments ?? [])
);
const existingInlineKeys = computed(() =>
  (detail.selectedPost?.attachments ?? [])
    .filter((attachment) =>
      attachment.attachmentKind === "INLINE_IMAGE" && typeof attachment.inlineKey === "string")
    .map((attachment) => attachment.inlineKey as string)
);
const existingSources = computed(() =>
  buildInlineImageSources(detail.selectedPost?.attachments ?? [])
);
const inlineDraft = useInlineImageDraft({
  confirmUpload: () => detail.ensureEditAttachmentUploadConfirmed(),
  reservedImageKeys: () => existingInlineKeys.value
});
const inlineSources = computed(() => ({
  ...existingSources.value,
  ...inlineDraft.inlineSources.value
}));
const keptDownloadCount = computed(() =>
  downloadAttachments.value.filter(
    (attachment) => !detail.removeAttachmentIds.has(attachment.id)
  ).length
);
const activeInlineImageCount = computed(() =>
  collectInlineImageKeys(detail.postEditForm.bodyDocument).length
);
const inlineImageSlotCapacity = computed(() =>
  Math.max(MAX_ATTACHMENTS - keptDownloadCount.value - detail.postEditAttachmentFiles.length, 0)
);
const combinedAttachmentCount = computed(() =>
  keptDownloadCount.value + detail.postEditAttachmentFiles.length + activeInlineImageCount.value
);

function onTitle(event: Event) {
  detail.postEditForm.title = (event.target as HTMLInputElement).value;
}

function onInlineImageError(message: string) {
  detail.postActionError = message;
}

function registerInlineImages(files: readonly File[]): InlineImageDraftRegistrationResult {
  const result = inlineDraft.register(files);
  if (!result.error && result.entries.length > 0) {
    detail.postActionError = "";
  }
  return result;
}

async function onSubmit() {
  const prepared = inlineDraft.buildUploads(
    detail.postEditForm.bodyDocument,
    existingInlineKeys.value
  );
  if (prepared.error) {
    detail.postActionError = prepared.error;
    return;
  }
  const saved = await detail.handleUpdatePost(prepared.inlineImages);
  if (saved) {
    inlineDraft.clear();
  }
}

function onCancel() {
  inlineDraft.clear();
  detail.closePostActionPanel();
}

onBeforeUnmount(() => {
  inlineDraft.clear();
});
</script>

<template>
  <form class="form-grid compact-form action-panel" @submit.prevent="onSubmit">
    <label class="field">
      <span>제목</span>
      <input :value="detail.postEditForm.title" maxlength="200" required @input="onTitle" />
    </label>
    <div class="field">
      <span>{{ getPostBodyLabel() }}</span>
      <PostDocumentEditor
        v-model="detail.postEditForm.bodyDocument"
        :inline-sources="inlineSources"
        :register-inline-images="registerInlineImages"
        :max-active-inline-images="inlineImageSlotCapacity"
        @inline-image-error="onInlineImageError"
      />
    </div>
    <p class="section-meta">{{ getPostBodyHelp() }}</p>

    <div v-if="downloadAttachments.length > 0" class="attachment-panel">
      <span class="attachment-label">현재 첨부파일 ({{ downloadAttachments.length }})</span>
      <div v-for="attachment in downloadAttachments" :key="attachment.id" class="attachment-card">
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

    <AttachmentDropzone
      label="첨부파일 추가"
      :input-key="detail.postEditAttachmentInputKey"
      @files-selected="detail.selectEditAttachmentFiles($event)"
    />
    <p class="section-meta">
      최대 {{ MAX_ATTACHMENTS }}개까지 등록할 수 있습니다.
      (현재: 첨부 {{ keptDownloadCount + detail.postEditAttachmentFiles.length }}개 +
      본문 이미지 {{ activeInlineImageCount }}개 = {{ combinedAttachmentCount }}/{{ MAX_ATTACHMENTS }})
    </p>
    <ul v-if="detail.postEditAttachmentFiles.length > 0" class="attachment-select-list">
      <li
        v-for="file in detail.postEditAttachmentFiles"
        :key="attachmentFileKey(file)"
        class="attachment-select-item"
      >
        <span>새 파일: {{ file.name }} ({{ formatFileSize(file.size) }})</span>
        <button
          type="button"
          class="ghost-button"
          :aria-label="`${file.name} 첨부파일 제거`"
          @click="detail.removeEditAttachment(attachmentFileKey(file))"
        >
          제거
        </button>
      </li>
    </ul>

    <p v-if="detail.postActionError" class="panel-error" role="alert">{{ detail.postActionError }}</p>
    <div class="action-form-actions">
      <button type="submit" class="ghost-button" :disabled="detail.submitting">게시글 수정</button>
      <button type="button" class="ghost-button" @click="onCancel">취소</button>
    </div>
  </form>
</template>
