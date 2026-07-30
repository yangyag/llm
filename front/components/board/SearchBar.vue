<script setup lang="ts">
import { usePostsStore } from "~/stores/posts";

const posts = usePostsStore();

function onInput(event: Event) {
  posts.setSearchInput((event.target as HTMLInputElement).value);
}
</script>

<template>
  <form class="search-bar" @submit.prevent="posts.handleSearchSubmit()">
    <label class="field search-field">
      <span>검색어</span>
      <input
        :value="posts.searchInput"
        placeholder="제목 검색"
        @input="onInput"
      />
    </label>
    <div class="search-actions">
      <button type="submit" class="submit-button" :disabled="posts.loading">검색</button>
      <button
        type="button"
        class="ghost-button"
        :disabled="posts.loading || (!posts.searchQuery && !posts.searchInput)"
        @click="posts.handleSearchReset()"
      >
        초기화
      </button>
    </div>
  </form>
</template>
