<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { useAuthStore } from "~/stores/auth";
import { usePostsStore } from "~/stores/posts";
import { usePostDetailStore } from "~/stores/postDetail";
import { getApiUrl } from "~/services/api";
import { getListStateFromLocation } from "~/composables/useListUrlState";
import { useIdleTimeout } from "~/composables/useIdleTimeout";
import { isFileConversionMode } from "~/utils/post";

const auth = useAuthStore();
const posts = usePostsStore();
const detail = usePostDetailStore();

useIdleTimeout();

// 부팅 검증(fetchMe) 완료 전엔 빈 화면 (원본 App.jsx 동등).
const message = computed(
  () => detail.message || posts.listMessage
);
const error = computed(
  () => detail.error || detail.postActionError || detail.replyActionError || detail.aiReplyError || posts.listError
);

// 뒤로가기(popstate): URL → state 동기화 후 목록 뷰로.
function handlePopState() {
  const next = getListStateFromLocation();
  posts.currentPage = next.page;
  posts.searchQuery = next.query;
  posts.searchInput = next.query;
  detail.view = "list";
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

// currentPage/searchQuery 변경 시 목록 재로드 (원본 useEffect).
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
  navigateTo("/login");
}
</script>

<template>
  <main v-if="auth.checked" class="board-page">
    <section class="board-shell">
      <header class="board-header">
        <div>
          <p class="eyebrow">Anonymous Board</p>
          <h1>답변 가능한 게시판</h1>
        </div>
        <div class="board-actions">
          <span v-if="auth.username" class="auth-username">{{ auth.username }}</span>
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

      <p v-if="message" class="message-banner success">{{ message }}</p>
      <p v-if="error" class="message-banner error">{{ error }}</p>

      <template v-if="detail.view === 'list'">
        <div class="download-link-row">
          <a class="download-link" :href="getApiUrl('/upload_zip_post.zip')" download="upload_zip_post.zip">
            * 파일 업로드 프로그램
          </a>
        </div>

        <section class="card">
          <div class="section-heading">
            <div>
              <h2>게시글 목록</h2>
              <p class="section-meta">
                {{ posts.searchQuery ? `검색 결과 ${posts.pagination.totalItems}개` : `총 ${posts.pagination.totalItems}개` }}, 페이지 {{ posts.pagination.page }}{{ posts.pagination.totalPages > 0 ? ` / ${posts.pagination.totalPages}` : "" }}
              </p>
            </div>
            <button type="button" class="ghost-button" @click="detail.refreshListView()">새로고침</button>
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
      </template>

      <section v-if="detail.view === 'write'" class="card">
        <div class="section-heading">
          <h2>새 글 작성</h2>
        </div>
        <PostForm />
      </section>

      <section v-if="detail.view === 'detail'" class="card">
        <div class="section-heading">
          <h2>게시글 상세</h2>
          <span v-if="detail.selectedPost">답변 {{ detail.selectedPost.replies.length }}개</span>
        </div>

        <p v-if="detail.detailLoading || !detail.selectedPost" class="empty-state">불러오는 중...</p>
        <template v-else>
          <PostDetail />
          <section class="reply-section">
            <div class="section-heading">
              <h3>답변</h3>
            </div>
            <div class="split-layout">
              <ReplyForm />
              <AiReplyPanel v-if="!isFileConversionMode(detail.selectedPost.mode)" />
            </div>
            <ReplyList />
          </section>
        </template>
      </section>
    </section>
  </main>
</template>
