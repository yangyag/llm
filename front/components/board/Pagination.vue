<script setup lang="ts">
import { usePostsStore } from "~/stores/posts";

const posts = usePostsStore();
</script>

<template>
  <nav v-if="posts.pagination.totalPages > 1" class="pager" aria-label="페이지 이동">
    <button
      type="button"
      :disabled="!posts.pagination.hasPrevious"
      @click="posts.navigateToList(1)"
    >
      맨처음
    </button>
    <button
      type="button"
      :disabled="!posts.pagination.hasPrevious"
      @click="posts.navigateToList(posts.currentPage - 1)"
    >
      ← 이전
    </button>
    <span class="pager-now">{{ posts.currentPage }} / {{ posts.pagination.totalPages }}</span>
    <button
      type="button"
      :disabled="!posts.pagination.hasNext"
      @click="posts.navigateToList(posts.currentPage + 1)"
    >
      다음 →
    </button>
    <button
      type="button"
      :disabled="!posts.pagination.hasNext"
      @click="posts.navigateToList(posts.pagination.totalPages)"
    >
      맨끝
    </button>
  </nav>
</template>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.pager button {
  font: inherit;
  font-size: 0.88rem;
  padding: 8px 14px;
  border: 0;
  border-radius: 10px;
  background: none;
  color: #9bb0ca;
  cursor: pointer;
}

.pager button:hover:not(:disabled) {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}

.pager button:disabled {
  cursor: default;
  opacity: 0.3;
}

.pager-now {
  font-size: 0.88rem;
  color: #7f93ad;
  font-variant-numeric: tabular-nums;
}
</style>
