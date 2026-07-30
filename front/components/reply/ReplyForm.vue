<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";

const detail = usePostDetailStore();

function onBody(event: Event) {
  detail.replyForm.body = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form class="card inset-card form-grid" @submit.prevent="detail.handleCreateReply()">
    <label class="field">
      <span>답변 본문</span>
      <textarea :value="detail.replyForm.body" rows="6" required @input="onBody" />
    </label>
    <button
      type="submit"
      class="primary-button wide-button"
      :disabled="detail.submitting || detail.aiSubmitting"
    >
      답변 등록
    </button>
  </form>
</template>
