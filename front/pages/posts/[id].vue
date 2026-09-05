<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AttachmentPanel from "~/components/post/AttachmentPanel.vue";
import PostBodyReader from "~/components/post/PostBodyReader.vue";
import { useAuthStore } from "~/stores/auth";
import { getPost } from "~/services/api";
import { getPostModeLabel, isFileConversionMode } from "~/utils/post";
import type { PostDetail } from "~/types/api";

const auth = useAuthStore();

const route = useRoute();
const postId = computed(() => Number.parseInt(route.params.id as string, 10));

const post = ref<PostDetail | null>(null);
const loading = ref(true);
const error = ref("");

let cancelled = false;
let requestVersion = 0;

async function loadPost() {
  const version = ++requestVersion;
  const requestedId = postId.value;
  post.value = null;
  loading.value = true;
  error.value = "";
  try {
    const payload = await getPost(requestedId);
    if (cancelled || version !== requestVersion) return;
    post.value = payload;
  } catch (loadError) {
    if (cancelled || version !== requestVersion) return;
    post.value = null;
    error.value = (loadError as Error).message;
  } finally {
    if (!cancelled && version === requestVersion) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  loadPost();
});

onBeforeUnmount(() => {
  cancelled = true;
  requestVersion += 1;
});

watch(postId, () => {
  if (!cancelled) loadPost();
});

function goToBoard() {
  window.location.href = "/";
}
</script>

<template>
  <main class="board-page">
    <section class="board-shell">
      <header class="board-header">
        <div>
          <p class="eyebrow">Secret Board</p>
          <h1>게시글 보기</h1>
        </div>
        <div class="board-actions">
          <NuxtLink v-if="auth.isAdmin" to="/users" class="ghost-button">사용자 관리</NuxtLink>
          <button type="button" class="ghost-button" @click="goToBoard">← 게시판으로</button>
        </div>
      </header>

      <section v-if="loading" class="card">
        <p class="empty-state">불러오는 중...</p>
      </section>

      <section v-else-if="error" class="card">
        <p class="message-banner error">{{ error }}</p>
        <div class="inline-actions">
          <button type="button" class="ghost-button" @click="loadPost">다시 시도</button>
          <button type="button" class="ghost-button" @click="goToBoard">홈으로</button>
        </div>
      </section>

      <template v-else-if="post">
        <section class="card detail-card">
          <article class="post-hero">
            <div class="post-hero-badges">
              <span class="post-mode-badge" :class="{ file: isFileConversionMode(post.mode) }">
                {{ getPostModeLabel(post.mode) }}
              </span>
              <span v-if="post.conversionReady" class="post-mode-badge success">암호화 업로드 완료</span>
            </div>

            <h3 class="post-hero-title">{{ post.title }}</h3>

            <div class="post-hero-byline">
              <span class="post-avatar" aria-hidden="true">{{ (post.authorUsername || "익").slice(0, 1).toUpperCase() }}</span>
              <strong class="post-hero-author">{{ post.authorUsername || "익명" }}</strong>
              <span class="post-hero-dot" aria-hidden="true">·</span>
              <time>{{ new Date(post.createdAt).toLocaleString("ko-KR") }}</time>
              <span class="post-hero-dot" aria-hidden="true">·</span>
              <span class="post-hero-replies">댓글 {{ post.replies.length }}</span>
            </div>

            <p v-if="isFileConversionMode(post.mode)" class="post-body-box">
              암호화 업로드 글입니다. 본문은 공개 상세 화면에서 표시되지 않습니다.
            </p>
            <PostBodyReader v-else :body="post.body" :mode="post.mode" />

            <AttachmentPanel
              v-if="post.attachments.length > 0"
              :attachments="post.attachments"
              :mode="post.mode"
            />
          </article>

          <section class="reply-thread">
            <h3 class="reply-thread-title">
              댓글
              <span class="reply-count">{{ post.replies.length }}</span>
            </h3>

            <p v-if="post.replies.length === 0" class="empty-state">아직 댓글이 없습니다.</p>
            <div v-else class="reply-list">
              <article v-for="reply in post.replies" :key="reply.id" class="reply">
                <div class="reply-head">
                  <span class="reply-avatar" aria-hidden="true">{{ ((reply.authorUsername || "익") as string).slice(0, 1).toUpperCase() }}</span>
                  <strong class="reply-author">{{ reply.authorUsername || "익명" }}</strong>
                  <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 레거시 행 표시용으로 유지. -->
                  <span v-if="reply.ai" class="ai-badge">
                    AI · {{ reply.aiProvider }} ({{ reply.aiModel }})
                  </span>
                  <time>{{ new Date(reply.createdAt).toLocaleString("ko-KR") }}</time>
                </div>
                <p class="reply-body">{{ reply.body }}</p>
              </article>
            </div>
          </section>
        </section>

        <section class="card">
          <div class="section-heading">
            <h2>로그인 안내</h2>
          </div>
          <p class="section-meta">글 수정·삭제는 작성자 본인 또는 관리자만 가능합니다. 로그인 후 게시판에서 이용하세요.</p>
          <a class="ghost-button" href="/" @click.prevent="goToBoard">로그인하러 가기</a>
        </section>
      </template>
    </section>
  </main>
</template>
