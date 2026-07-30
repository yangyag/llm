<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import { getPostBodyHelp, getPostBodyLabel } from "~/utils/post";
import AttachmentSelect from "./AttachmentSelect.vue";

const detail = usePostDetailStore();
const createPostFormId = "create-post-form";

function onTitle(event: Event) {
  detail.postForm.title = (event.target as HTMLInputElement).value;
}
function onBody(event: Event) {
  detail.postForm.body = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form :id="createPostFormId" class="form-grid" @submit.prevent="detail.handleCreatePost()">
    <label class="field">
      <span>제목</span>
      <input :value="detail.postForm.title" maxlength="200" required @input="onTitle" />
    </label>
    <label class="field">
      <span>{{ getPostBodyLabel() }}</span>
      <textarea :value="detail.postForm.body" rows="12" @input="onBody" />
    </label>
    <p class="section-meta">{{ getPostBodyHelp() }}</p>
    <AttachmentSelect />
  </form>
</template>
