<script setup lang="ts">
import { usePostsStore } from "~/stores/posts";

const posts = usePostsStore();

function onInput(event: Event) {
  posts.setSearchInput((event.target as HTMLInputElement).value);
}
</script>

<template>
  <form class="search-row" @submit.prevent="posts.handleSearchSubmit()">
    <label class="sr-only" for="board-search">제목 검색</label>
    <input
      id="board-search"
      :value="posts.searchInput"
      class="search-input"
      placeholder="제목 검색"
      @input="onInput"
    />
    <button
      v-if="posts.searchQuery || posts.searchInput"
      type="button"
      class="search-clear"
      :disabled="posts.loading"
      @click="posts.handleSearchReset()"
    >
      지우기
    </button>
  </form>
</template>

<style scoped>
.search-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 16px;
  padding: 6px 6px 6px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.09);
  transition: border-color 0.15s ease;
}

.search-row:focus-within {
  border-color: rgba(143, 183, 255, 0.4);
}

.search-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: none;
  color: #f8fbff;
  font: inherit;
  font-size: 0.95rem;
  padding: 8px 0;
}

.search-input::placeholder {
  color: #5f728c;
}

.search-input:focus {
  outline: none;
}

.search-clear {
  font: inherit;
  font-size: 0.84rem;
  padding: 7px 11px;
  border: 0;
  border-radius: 9px;
  background: none;
  color: #7f93ad;
  cursor: pointer;
  white-space: nowrap;
}

.search-clear:hover:not(:disabled) {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}
</style>
