<script setup lang="ts">
import { usePostDetailStore } from "~/stores/postDetail";
import ReplyItem from "./ReplyItem.vue";

const detail = usePostDetailStore();
</script>

<template>
  <p
    v-if="detail.selectedPost && detail.selectedPost.replies.length === 0"
    class="empty-state"
  >
    아직 답변이 없습니다.
  </p>
  <div v-else class="reply-list">
    <ReplyItem
      v-for="reply in detail.selectedPost?.replies ?? []"
      :key="reply.id"
      :reply="reply"
    />
  </div>
</template>
