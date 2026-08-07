<script setup lang="ts">
import { computed, ref } from "vue";
import { useAuthStore } from "~/stores/auth";
import { usePostsStore } from "~/stores/posts";
import { canManagePost } from "~/utils/post";

const auth = useAuthStore();
const posts = usePostsStore();
const deleting = ref(false);

const manageableItems = computed(() =>
  posts.items.filter((post) => canManagePost(post.authorUsername, auth.username, auth.role))
);

const allManageableSelected = computed(
  () =>
    manageableItems.value.length > 0 &&
    manageableItems.value.every((post) => posts.selectedPostIds.has(post.id))
);

function toggleSelectAllManageable() {
  if (allManageableSelected.value) {
    posts.selectedPostIds = new Set();
    return;
  }
  posts.selectedPostIds = new Set(manageableItems.value.map((post) => post.id));
}

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
  <div v-if="manageableItems.length > 0" class="batch-action-bar">
    <label class="checkbox-field batch-select-all">
      <input
        type="checkbox"
        :checked="allManageableSelected"
        @change="toggleSelectAllManageable()"
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
