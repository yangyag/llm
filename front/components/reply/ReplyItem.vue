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
  canManagePost(props.reply.authorUserId, auth.userId, auth.role)
);

const authorLabel = computed(() => props.reply.authorUsername || "익명");
const authorInitial = computed(() => authorLabel.value.slice(0, 1).toUpperCase());

const createdLabel = computed(() => {
  const date = new Date(props.reply.createdAt);
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  const time = date.toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
  if (sameDay) return `오늘 ${time}`;
  return `${date.toLocaleDateString("ko-KR", { month: "long", day: "numeric" })} ${time}`;
});

async function onDelete() {
  if (!window.confirm("이 답변을 삭제하시겠습니까?")) return;
  await detail.handleDeleteReply(props.reply.id);
}
</script>

<template>
  <article class="reply">
    <div class="reply-head">
      <span class="reply-avatar" aria-hidden="true">{{ authorInitial }}</span>
      <strong class="reply-author">{{ authorLabel }}</strong>
      <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 행 표시용으로 유지. -->
      <span v-if="props.reply.ai" class="ai-badge">AI · {{ props.reply.aiProvider }}</span>
      <time>{{ createdLabel }}</time>
      <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. !reply.ai 가드는 레거시 행 보호용으로 유지. -->
      <span v-if="!props.reply.ai && manageable" class="reply-ops">
        <button
          type="button"
          @click="detail.openReplyEditPanel(props.reply.id, props.reply.body)"
        >
          수정
        </button>
        <button type="button" class="danger" :disabled="detail.submitting" @click="onDelete">
          삭제
        </button>
      </span>
    </div>
    <p class="reply-body">{{ props.reply.body }}</p>

    <ReplyEditPanel
      v-if="!props.reply.ai && manageable && detail.replyEditState.replyId === props.reply.id"
    />
    <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 위 !reply.ai 가드는 레거시 행 보호용으로 유지. -->
  </article>
</template>

<style scoped>
.reply {
  padding: 18px 4px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.reply:last-child {
  border-bottom: 0;
}

.reply-head {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  color: #9bb0ca;
  font-size: 0.86rem;
}

.reply-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  font-weight: 700;
  color: #dce9ff;
  background: linear-gradient(145deg, rgba(143, 183, 255, 0.28), rgba(77, 166, 255, 0.14));
  border: 1px solid rgba(143, 183, 255, 0.28);
  flex-shrink: 0;
}

.reply-author {
  color: #eef5ff;
  font-size: 0.92rem;
}

.reply-ops {
  display: inline-flex;
  gap: 2px;
  margin-left: auto;
}

.reply-ops button {
  font: inherit;
  font-size: 0.82rem;
  padding: 6px 9px;
  border: 0;
  border-radius: 8px;
  background: none;
  color: #7f93ad;
  cursor: pointer;
}

.reply-ops button:hover:not(:disabled) {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}

.reply-ops button.danger {
  color: #c57a7a;
}

.reply-ops button.danger:hover:not(:disabled) {
  color: #ffb3b3;
  background: rgba(255, 91, 91, 0.1);
}

.reply-body {
  margin: 10px 0 0 35px;
  color: #dde7f5;
  font-size: 0.96rem;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
