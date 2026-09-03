<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "~/stores/auth";
import { usePostDetailStore } from "~/stores/postDetail";
import { canManagePost } from "~/utils/post";
import type { Reply } from "~/types/api";
import ReplyEditPanel from "./ReplyEditPanel.vue";

const props = defineProps<{
  reply: Reply;
}>();

const auth = useAuthStore();
const detail = usePostDetailStore();

const manageable = computed(() =>
  canManagePost(props.reply.authorUsername, auth.username, auth.role)
);

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
        <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 행 표시용으로 유지. -->
        <span v-if="props.reply.ai" class="ai-badge">AI · {{ props.reply.aiProvider }}</span>
        <span v-else-if="props.reply.authorUsername" class="author-badge">
          {{ props.reply.authorUsername }}
        </span>
        <span v-else class="author-badge muted">작성자 없음</span>
      </div>
      <time>{{ new Date(props.reply.createdAt).toLocaleString() }}</time>
    </div>
    <p class="detail-body">{{ props.reply.body }}</p>

    <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. !reply.ai 가드는 레거시 행 보호용으로 유지. -->
    <div v-if="!props.reply.ai && manageable" class="inline-actions">
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

    <ReplyEditPanel
      v-if="!props.reply.ai && manageable && detail.replyEditState.replyId === props.reply.id"
    />
    <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 위 !reply.ai 가드는 레거시 행 보호용으로 유지. -->
  </article>
</template>
