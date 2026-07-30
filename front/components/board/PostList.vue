<script setup lang="ts">
import { usePostsStore } from "~/stores/posts";
import { usePostDetailStore } from "~/stores/postDetail";
import { getPostModeLabel, isFileConversionMode } from "~/utils/post";

const posts = usePostsStore();
const detail = usePostDetailStore();

function open(postId: number) {
  detail.openDetail(postId);
}
</script>

<template>
  <div class="post-list">
    <div v-for="post in posts.items" :key="post.id" class="post-list-item-row">
      <input
        type="checkbox"
        class="post-checkbox"
        :checked="posts.selectedPostIds.has(post.id)"
        @change="posts.toggleSelection(post.id)"
      />
      <button type="button" class="post-list-item" @click="open(post.id)">
        <div class="post-title-row">
          <strong>{{ post.title }}</strong>
          <span v-if="!isFileConversionMode(post.mode)" class="post-mode-badge">
            {{ getPostModeLabel(post.mode) }}
          </span>
          <span v-if="post.conversionReady" class="post-mode-badge success">암호화 업로드 완료</span>
          <span v-if="post.hasAttachment" class="attachment-badge">첨부</span>
        </div>
        <span>답변 {{ post.replyCount }}개</span>
        <time>{{ new Date(post.createdAt).toLocaleString() }}</time>
      </button>
    </div>
  </div>
</template>
