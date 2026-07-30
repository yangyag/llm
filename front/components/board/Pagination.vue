<script setup lang="ts">
import { usePostsStore } from "~/stores/posts";

const posts = usePostsStore();
</script>

<template>
  <div class="pagination">
    <button
      type="button"
      class="ghost-button pagination-button"
      :disabled="!posts.pagination.hasPrevious"
      @click="posts.navigateToList(1)"
    >
      처음
    </button>
    <button
      type="button"
      class="ghost-button pagination-button"
      :disabled="!posts.pagination.hasPrevious"
      @click="posts.navigateToList(posts.currentPage - 1)"
    >
      이전
    </button>
    <div class="pagination-numbers">
      <button
        v-for="pageNumber in posts.pageNumbers"
        :key="pageNumber"
        type="button"
        class="ghost-button pagination-button"
        :class="{ active: pageNumber === posts.currentPage }"
        @click="posts.navigateToList(pageNumber)"
      >
        {{ pageNumber }}
      </button>
    </div>
    <button
      type="button"
      class="ghost-button pagination-button"
      :disabled="!posts.pagination.hasNext"
      @click="posts.navigateToList(posts.currentPage + 1)"
    >
      다음
    </button>
    <button
      type="button"
      class="ghost-button pagination-button"
      :disabled="!posts.pagination.hasNext"
      @click="posts.navigateToList(posts.pagination.totalPages)"
    >
      끝
    </button>
  </div>
</template>
