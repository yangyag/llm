<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { usePostDetailStore } from "~/stores/postDetail";
import { getPostBodyHelp, getPostBodyLabel, MAX_ATTACHMENTS } from "~/utils/post";
import { collectInlineImageKeys } from "~/utils/postDocument";
import { useInlineImageDraft } from "~/composables/useInlineImageDraft";
import type { InlineImageDraftRegistrationResult } from "~/composables/useInlineImageDraft";
import AttachmentSelect from "./AttachmentSelect.vue";
import PostDocumentEditor from "./PostDocumentEditor.client.vue";

const detail = usePostDetailStore();
const createPostFormId = "create-post-form";

const inlineDraft = useInlineImageDraft({
  confirmUpload: () => detail.ensureCreateAttachmentUploadConfirmed()
});
const inlineSources = inlineDraft.inlineSources;
// 편집기 내용이 저장 형식으로 바뀌지 않는 동안에는 이전 본문이 저장되지 않도록 제출을 막는다.
const documentError = ref("");

const activeInlineImageCount = computed(() =>
  collectInlineImageKeys(detail.postForm.bodyDocument).length
);
const inlineImageSlotCapacity = computed(() =>
  Math.max(MAX_ATTACHMENTS - detail.postAttachmentFiles.length, 0)
);

function onTitle(event: Event) {
  detail.postForm.title = (event.target as HTMLInputElement).value;
}

function onInlineImageError(message: string) {
  detail.error = message;
}

function onDocumentError(message: string) {
  const previous = documentError.value;
  documentError.value = message;
  if (message) {
    detail.error = message;
  } else if (detail.error === previous) {
    detail.error = "";
  }
}

function registerInlineImages(files: readonly File[]): InlineImageDraftRegistrationResult {
  const result = inlineDraft.register(files);
  if (!result.error && result.entries.length > 0) {
    detail.error = "";
  }
  return result;
}

async function onSubmit() {
  if (documentError.value) {
    detail.error = documentError.value;
    return;
  }
  const prepared = inlineDraft.buildUploads(detail.postForm.bodyDocument);
  if (prepared.error) {
    detail.error = prepared.error;
    return;
  }
  const saved = await detail.handleCreatePost(prepared.inlineImages);
  if (saved) {
    inlineDraft.clear();
  }
}

onBeforeUnmount(() => {
  inlineDraft.clear();
});
</script>

<template>
  <form :id="createPostFormId" class="form-grid" @submit.prevent="onSubmit">
    <label class="field">
      <span>제목</span>
      <input :value="detail.postForm.title" maxlength="200" required @input="onTitle" />
    </label>
    <div class="field">
      <span>{{ getPostBodyLabel() }}</span>
      <PostDocumentEditor
        v-model="detail.postForm.bodyDocument"
        :inline-sources="inlineSources"
        :register-inline-images="registerInlineImages"
        :max-active-inline-images="inlineImageSlotCapacity"
        @inline-image-error="onInlineImageError"
        @document-error="onDocumentError"
      />
    </div>
    <p class="section-meta">{{ getPostBodyHelp() }}</p>
    <AttachmentSelect :inline-image-count="activeInlineImageCount" />
  </form>
</template>
