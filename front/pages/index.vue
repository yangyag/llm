<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import BatchActionBar from "~/components/board/BatchActionBar.vue";
import Pagination from "~/components/board/Pagination.vue";
import PostList from "~/components/board/PostList.vue";
import SearchBar from "~/components/board/SearchBar.vue";
import { useAuthStore } from "~/stores/auth";
import { usePostsStore } from "~/stores/posts";
import { usePostDetailStore } from "~/stores/postDetail";
import { getApiUrl } from "~/services/api";
import { getListStateFromLocation } from "~/composables/useListUrlState";
import { useIdleTimeout } from "~/composables/useIdleTimeout";

const auth = useAuthStore();
const posts = usePostsStore();
const detail = usePostDetailStore();

useIdleTimeout();

// 부팅 검증(fetchMe) 완료 전에는 보호 화면을 렌더링하지 않는다.
const message = computed(
  () => detail.message || posts.listMessage
);
const error = computed(
  () => detail.error || detail.postActionError || detail.replyActionError || posts.listError
);

// 뒤로가기(popstate): URL → state 동기화 후 목록 뷰로.
function handlePopState() {
  const next = getListStateFromLocation();
  posts.currentPage = next.page;
  posts.searchQuery = next.query;
  posts.searchInput = next.query;
  detail.resetListViewState();
}

onMounted(() => {
  window.addEventListener("popstate", handlePopState);
  if (detail.view === "list") {
    posts.loadPosts(posts.currentPage, posts.searchQuery);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("popstate", handlePopState);
});

// currentPage/searchQuery 변경 시 목록을 다시 불러온다.
watch(
  [() => posts.currentPage, () => posts.searchQuery],
  () => {
    if (detail.view === "list") {
      posts.loadPosts(posts.currentPage, posts.searchQuery);
    }
  }
);

function handleLogout() {
  auth.logout();
  navigateTo("/login", { replace: true });
}
</script>

<template>
  <main v-if="auth.checked" class="board-page">
    <section class="board-shell">
      <header class="board-header">
        <div>
          <p class="eyebrow">Secret Board</p>
          <h1>비밀 게시판</h1>
        </div>
        <div class="board-actions">
          <span v-if="auth.username" class="auth-username">{{ auth.username }}</span>
          <NuxtLink v-if="auth.isAdmin" to="/users" class="ghost-button">사용자 관리</NuxtLink>
          <button type="button" class="ghost-button" @click="handleLogout">로그아웃</button>
          <button type="button" class="ghost-button" @click="detail.refreshListView()">목록</button>
          <button
            v-if="detail.view !== 'detail'"
            :type="detail.view === 'write' ? 'submit' : 'button'"
            :form="detail.view === 'write' ? 'create-post-form' : undefined"
            :class="detail.view === 'write' ? 'submit-button' : 'primary-button'"
            @click="detail.view === 'write' ? undefined : detail.openWrite()"
            :disabled="detail.view === 'write' && detail.submitting"
          >
            {{ detail.view === "write" ? (detail.submitting ? "등록 중..." : "등록") : "글쓰기" }}
          </button>
        </div>
      </header>

      <p v-if="message" class="message-banner success" role="status">{{ message }}</p>
      <p v-if="error" class="message-banner error" role="alert">{{ error }}</p>

      <template v-if="detail.view === 'list'">
        <section class="card">
          <div class="list-head">
            <p class="list-count">
              {{ posts.searchQuery ? `“${posts.searchQuery}” 검색 결과 ${posts.pagination.totalItems}개` : `총 ${posts.pagination.totalItems}개` }}
            </p>
            <button type="button" class="list-refresh" @click="detail.refreshListView()">새로고침</button>
          </div>

          <SearchBar />
          <BatchActionBar v-if="auth.username && posts.items.length > 0 && !posts.loading" />

          <p v-if="posts.loading" class="empty-state">불러오는 중...</p>
          <p v-else-if="posts.items.length === 0" class="empty-state">
            {{ posts.searchQuery ? "검색 결과가 없습니다. 다른 검색어로 다시 시도해 보세요." : "아직 게시글이 없습니다. 첫 글을 작성해 보세요." }}
          </p>
          <PostList v-else />

          <Pagination v-if="posts.items.length > 0" />
        </section>

        <p class="tool-note">
          <a :href="getApiUrl('/upload_zip_post.zip')" download="upload_zip_post.zip">
            파일 업로드 프로그램 다운로드
          </a>
        </p>
      </template>

      <section v-if="detail.view === 'write'" class="card">
        <div class="section-heading">
          <h2>새 글 작성</h2>
        </div>
        <PostForm />
      </section>

      <section v-if="detail.view === 'detail'" class="card detail-card">
        <p v-if="detail.detailLoading" class="empty-state">불러오는 중...</p>
        <template v-else-if="detail.selectedPost">
          <PostDetail />
          <section class="reply-thread">
            <h3 class="reply-thread-title">
              답변
              <span class="reply-count">{{ detail.selectedPost.replies.length }}</span>
            </h3>
            <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. AI 패널 제거, 일반 답변폼 단독 1열. -->
            <ReplyForm />
            <ReplyList />
          </section>
        </template>
        <p v-else class="empty-state">게시글을 불러오지 못했습니다. 목록에서 다시 선택해 주세요.</p>
      </section>
    </section>
  </main>
</template>
