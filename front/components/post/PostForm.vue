<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import { getPostBodyHelp, getPostBodyLabel } from "~/utils/post";
import AttachmentSelect from "./AttachmentSelect.vue";
import PostDocumentEditor from "./PostDocumentEditor.client.vue";

const detail = usePostDetailStore();
const createPostFormId = "create-post-form";

function onTitle(event: Event) {
  detail.postForm.title = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form :id="createPostFormId" class="form-grid" @submit.prevent="detail.handleCreatePost()">
    <label class="field">
      <span>제목</span>
      <input :value="detail.postForm.title" maxlength="200" required @input="onTitle" />
    </label>
    <div class="field">
      <span>{{ getPostBodyLabel() }}</span>
      <PostDocumentEditor v-model="detail.postForm.bodyDocument" />
    </div>
    <p class="section-meta">{{ getPostBodyHelp() }}</p>
    <AttachmentSelect />
  </form>
</template>
