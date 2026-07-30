<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import type { Reply } from "~/types/api";
import ReplyEditPanel from "./ReplyEditPanel.vue";

const props = defineProps<{
  reply: Reply;
}>();

const detail = usePostDetailStore();

async function onDelete() {
  if (!window.confirm("이 답변을 삭제하시겠습니까?")) return;
  await detail.handleDeleteReply(props.reply.id);
}
</script>

<template>
  <article class="card inset-card reply-card">
    <div class="reply-top">
      <div class="reply-heading">
        <strong>답변 #{{ props.reply.id }}</strong>
        <span v-if="props.reply.ai" class="ai-badge">AI · {{ props.reply.aiProvider }}</span>
      </div>
      <time>{{ new Date(props.reply.createdAt).toLocaleString() }}</time>
    </div>
    <p class="detail-body">{{ props.reply.body }}</p>

    <div v-if="!props.reply.ai" class="inline-actions">
      <button
        type="button"
        class="ghost-button"
        @click="detail.openReplyEditPanel(props.reply.id, props.reply.body)"
      >
        수정
      </button>
      <button
        type="button"
        class="danger-button"
        :disabled="detail.submitting"
        @click="onDelete"
      >
        삭제
      </button>
    </div>

    <ReplyEditPanel v-if="!props.reply.ai && detail.replyEditState.replyId === props.reply.id" />
  </article>
</template>
