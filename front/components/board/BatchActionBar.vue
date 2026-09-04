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
  <div v-if="manageableItems.length > 0" class="select-row">
    <button type="button" class="select-toggle" @click="toggleSelectAllManageable()">
      {{ allManageableSelected ? "선택 해제" : "내 글 전체 선택" }}
    </button>
    <button
      v-if="posts.selectedPostIds.size > 0"
      type="button"
      class="select-delete"
      :disabled="deleting"
      @click="onBatchDelete"
    >
      {{ deleting ? "삭제 중..." : `${posts.selectedPostIds.size}개 삭제` }}
    </button>
  </div>
</template>

<style scoped>
.select-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
}

.select-row button {
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

.select-row button:hover:not(:disabled) {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}

.select-delete {
  color: #c57a7a !important;
}

.select-delete:hover:not(:disabled) {
  color: #ffb3b3 !important;
  background: rgba(255, 91, 91, 0.1) !important;
}
</style>
