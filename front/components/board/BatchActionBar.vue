<script setup lang="ts">
import { ref } from "vue";
import { usePostsStore } from "~/stores/posts";

const posts = usePostsStore();
const deleting = ref(false);

async function onBatchDelete() {
  if (deleting.value || posts.selectedPostIds.size === 0) return;
  if (!window.confirm(`선택한 ${posts.selectedPostIds.size}개의 게시글을 삭제하시겠습니까?`)) return;

  deleting.value = true;
  try {
    await posts.batchDelete();
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <div class="batch-action-bar">
    <label class="checkbox-field batch-select-all">
      <input
        type="checkbox"
        :checked="posts.items.length > 0 && posts.selectedPostIds.size === posts.items.length"
        @change="posts.toggleSelectAll()"
      />
      <span>전체 선택</span>
    </label>
    <button
      v-if="posts.selectedPostIds.size > 0"
      type="button"
      class="danger-button"
      :disabled="deleting"
      @click="onBatchDelete"
    >
      {{ deleting ? "삭제 중..." : `선택 삭제 (${posts.selectedPostIds.size})` }}
    </button>
  </div>
</template>
