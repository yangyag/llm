<script setup lang="ts">
import { useAuthStore } from "~/stores/auth";
import { usePostsStore } from "~/stores/posts";
import { usePostDetailStore } from "~/stores/postDetail";
import { canManagePost, isFileConversionMode } from "~/utils/post";

const auth = useAuthStore();
const posts = usePostsStore();
const detail = usePostDetailStore();

function open(postId: number) {
  detail.openDetail(postId);
}

function canSelect(post: { authorUserId: number | null }): boolean {
  return canManagePost(post.authorUserId, auth.userId, auth.role);
}

function formatListDate(raw: string): string {
  const date = new Date(raw);
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  if (sameDay) {
    return date.toLocaleTimeString("ko-KR", { hour: "numeric", minute: "2-digit" });
  }
  const thisYear = date.getFullYear() === now.getFullYear();
  return date.toLocaleDateString("ko-KR", {
    ...(thisYear ? {} : { year: "numeric" as const }),
    month: "numeric",
    day: "numeric"
  });
}
</script>

<template>
  <ul class="post-rows">
    <li
      v-for="post in posts.items"
      :key="post.id"
      class="post-row"
      :class="{ selected: posts.selectedPostIds.has(post.id) }"
    >
      <input
        v-if="canSelect(post)"
        type="checkbox"
        class="post-row-check"
        :checked="posts.selectedPostIds.has(post.id)"
        :aria-label="'선택: ' + post.title"
        @change="posts.toggleSelection(post.id)"
        @click.stop
      />
      <button type="button" class="post-row-main" @click="open(post.id)">
        <span class="post-row-title">
          <span v-if="isFileConversionMode(post.mode)" class="post-row-file-dot" aria-label="암호화 업로드 글" />
          {{ post.title }}
          <span v-if="post.conversionReady" class="post-mode-badge success">업로드 완료</span>
        </span>
        <span class="post-row-meta">
          <span class="post-row-author">{{ post.authorUsername || "익명" }}</span>
          <span class="post-row-dot" aria-hidden="true">·</span>
          <time>{{ formatListDate(post.createdAt) }}</time>
          <span class="post-row-dot" aria-hidden="true">·</span>
          <span>답변 {{ post.replyCount }}</span>
          <span v-if="post.hasAttachment" class="post-row-attach" title="첨부파일 있음">⤓</span>
        </span>
      </button>
    </li>
  </ul>
</template>

<style scoped>
.post-rows {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
}

.post-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 6px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  transition: background 0.15s ease;
}

.post-row:last-child {
  border-bottom: 0;
}

.post-row:hover {
  background: rgba(255, 255, 255, 0.025);
}

.post-row.selected {
  background: rgba(143, 183, 255, 0.06);
}

.post-row-check {
  width: 1.1rem;
  height: 1.1rem;
  margin: 0;
  flex-shrink: 0;
  cursor: pointer;
  accent-color: #ff8a00;
}

.post-row-main {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.post-row-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 1.02rem;
  font-weight: 500;
  color: #f3f7ff;
  overflow-wrap: anywhere;
}

.post-row:hover .post-row-title {
  color: #8fb7ff;
}

.post-row-file-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ff8a00;
  flex-shrink: 0;
}

.post-row-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  font-size: 0.85rem;
  color: #7f93ad;
}

.post-row-author {
  color: #9bb0ca;
}

.post-row-dot {
  opacity: 0.5;
}

.post-row-attach {
  color: #7fd6a4;
  font-weight: 700;
}
</style>
