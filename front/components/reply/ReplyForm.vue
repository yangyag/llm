<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";

const detail = usePostDetailStore();

function onBody(event: Event) {
  detail.replyForm.body = (event.target as HTMLInputElement).value;
}
</script>

<template>
  <form class="reply-compose" @submit.prevent="detail.handleCreateReply()">
    <label class="sr-only" for="reply-body">답변 본문</label>
    <textarea
      id="reply-body"
      :value="detail.replyForm.body"
      rows="3"
      placeholder="답변을 입력하세요"
      required
      @input="onBody"
    />
    <button type="submit" class="reply-submit" :disabled="detail.submitting">
      {{ detail.submitting ? "등록 중..." : "답변 등록" }}
    </button>
  </form>
</template>

<style scoped>
.reply-compose {
  display: grid;
  gap: 10px;
  margin-bottom: 6px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.reply-compose:focus-within {
  border-color: rgba(143, 183, 255, 0.35);
}

.reply-compose textarea {
  width: 100%;
  border: 0;
  background: none;
  color: #f8fbff;
  font: inherit;
  font-size: 0.95rem;
  line-height: 1.7;
  resize: vertical;
  min-height: 72px;
}

.reply-compose textarea::placeholder {
  color: #5f728c;
}

.reply-compose textarea:focus {
  outline: none;
}

.reply-submit {
  justify-self: end;
  font: inherit;
  font-size: 0.9rem;
  font-weight: 700;
  padding: 10px 22px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, #ff8a00, #ff5e5b);
  color: #fff7ef;
  cursor: pointer;
  transition: transform 0.15s ease, opacity 0.15s ease;
}

.reply-submit:hover:not(:disabled) {
  transform: translateY(-1px);
}

.reply-submit:disabled {
  cursor: wait;
  opacity: 0.7;
}
</style>
