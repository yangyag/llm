<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";

const detail = usePostDetailStore();

function onBody(event: Event) {
  detail.replyEditState.body = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form class="form-grid compact-form action-panel" @submit.prevent="detail.handleUpdateReply()">
    <label class="field">
      <span>수정 본문</span>
      <textarea :value="detail.replyEditState.body" rows="4" required @input="onBody" />
    </label>
    <p v-if="detail.replyActionError" class="panel-error">{{ detail.replyActionError }}</p>
    <div class="action-form-actions">
      <button type="submit" class="ghost-button" :disabled="detail.submitting">답변 수정</button>
      <button type="button" class="ghost-button" @click="detail.closeReplyEditPanel()">취소</button>
    </div>
  </form>
</template>
