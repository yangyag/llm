<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "~/stores/auth";
import { usePostDetailStore } from "~/stores/postDetail";
import { canManagePost, getPostModeLabel, isFileConversionMode } from "~/utils/post";
import AttachmentPanel from "./AttachmentPanel.vue";
import ConversionSummary from "./ConversionSummary.vue";
import PostBodyReader from "./PostBodyReader.vue";
import PostEditPanel from "./PostEditPanel.vue";

const auth = useAuthStore();
const detail = usePostDetailStore();

const manageable = computed(() =>
  canManagePost(detail.selectedPost?.authorUsername, auth.username, auth.role)
);

const editable = computed(
  () =>
    manageable.value &&
    !detail.selectedPost?.conversionReady &&
    !isFileConversionMode(detail.selectedPost?.mode ?? "") &&
    detail.postActionMode !== "edit"
);

const authorLabel = computed(() => detail.selectedPost?.authorUsername || "익명");
const authorInitial = computed(() => authorLabel.value.slice(0, 1).toUpperCase());

const createdLabel = computed(() => {
  const raw = detail.selectedPost?.createdAt;
  if (!raw) return "";
  const date = new Date(raw);
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  const time = date.toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
  if (sameDay) return `오늘 ${time}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const isYesterday =
    date.getFullYear() === yesterday.getFullYear() &&
    date.getMonth() === yesterday.getMonth() &&
    date.getDate() === yesterday.getDate();
  if (isYesterday) return `어제 ${time}`;
  return `${date.toLocaleDateString("ko-KR", { month: "long", day: "numeric" })} ${time}`;
});

async function onDelete() {
  if (!window.confirm("이 게시글을 삭제하시겠습니까?")) return;
  await detail.handleDeletePost();
}
</script>

<template>
  <article class="post-hero">
    <div class="post-hero-badges">
      <span
        class="post-mode-badge"
        :class="{ file: isFileConversionMode(detail.selectedPost?.mode ?? '') }"
      >
        {{ getPostModeLabel(detail.selectedPost?.mode ?? '') }}
      </span>
      <span v-if="detail.selectedPost?.conversionReady" class="post-mode-badge success">
        암호화 업로드 완료
      </span>
    </div>

    <h3 class="post-hero-title">{{ detail.selectedPost?.title }}</h3>

    <div class="post-hero-byline">
      <span class="post-avatar" aria-hidden="true">{{ authorInitial }}</span>
      <strong class="post-hero-author">{{ authorLabel }}</strong>
      <span class="post-hero-dot" aria-hidden="true">·</span>
      <time>{{ createdLabel }}</time>
      <span class="post-hero-dot" aria-hidden="true">·</span>
      <span class="post-hero-replies">답변 {{ detail.selectedPost?.replies.length ?? 0 }}</span>
    </div>

    <div class="post-hero-actions">
      <button type="button" class="hero-action" @click="detail.handleCopyPostLink()">
        {{ detail.postLinkCopied ? "복사됨!" : "링크 복사" }}
      </button>
      <button v-if="editable" type="button" class="hero-action" @click="detail.openPostEditPanel()">
        수정
      </button>
      <button v-if="manageable" type="button" class="hero-action danger" @click="onDelete">
        삭제
      </button>
    </div>

    <ConversionSummary
      v-if="detail.selectedPost && isFileConversionMode(detail.selectedPost.mode)"
      :post="detail.selectedPost"
    />
    <PostBodyReader
      v-else-if="detail.selectedPost"
      :body="detail.selectedPost.body"
      :mode="detail.selectedPost.mode"
    />

    <AttachmentPanel
      v-if="detail.selectedPost && detail.selectedPost.attachments.length > 0"
      :attachments="detail.selectedPost.attachments"
      :mode="detail.selectedPost.mode"
    />

    <PostEditPanel v-if="detail.postActionMode === 'edit' && manageable" />
  </article>
</template>

<style scoped>
.post-hero {
  display: grid;
  gap: 0;
}

.post-hero-badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.post-hero-title {
  margin: 12px 0 0;
  font-size: 1.65rem;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: #f8fbff;
  overflow-wrap: anywhere;
}

.post-hero-byline {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  color: #9bb0ca;
  font-size: 0.9rem;
}

.post-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  font-weight: 700;
  color: #ffe0bf;
  background: linear-gradient(145deg, rgba(255, 138, 0, 0.35), rgba(255, 94, 91, 0.22));
  border: 1px solid rgba(255, 138, 0, 0.3);
  flex-shrink: 0;
}

.post-hero-author {
  color: #eef5ff;
  font-size: 0.95rem;
}

.post-hero-dot {
  opacity: 0.5;
}

.post-hero-replies {
  color: #8fb7ff;
}

.post-hero-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  padding: 6px 0 2px;
}

.hero-action {
  font: inherit;
  font-size: 0.86rem;
  padding: 8px 10px;
  border: 0;
  border-radius: 10px;
  background: none;
  color: #9bb0ca;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}

.hero-action:hover {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}

.hero-action.danger {
  color: #c57a7a;
}

.hero-action.danger:hover {
  color: #ffb3b3;
  background: rgba(255, 91, 91, 0.1);
}
</style>
