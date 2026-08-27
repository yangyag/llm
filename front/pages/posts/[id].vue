<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import AttachmentPanel from "~/components/post/AttachmentPanel.vue";
import { useAuthStore } from "~/stores/auth";
import { getApiUrl, getPost } from "~/services/api";
import { clipboardUserMessage, writeClipboardText } from "~/utils/clipboard";
import { canCopyPostBody, formatFileSize, getPostModeLabel, isFileConversionMode } from "~/utils/post";
import type { PostDetail } from "~/types/api";

const auth = useAuthStore();

const route = useRoute();
const postId = computed(() => Number.parseInt(route.params.id as string, 10));

const post = ref<PostDetail | null>(null);
const loading = ref(true);
const error = ref("");
const bodyCopied = ref(false);
const copyError = ref("");

let cancelled = false;
let bodyCopyTimer: ReturnType<typeof setTimeout> | undefined;

function resetCopyFeedback() {
  bodyCopied.value = false;
  copyError.value = "";
  window.clearTimeout(bodyCopyTimer);
  bodyCopyTimer = undefined;
}

async function handleCopyPostBody() {
  copyError.value = "";
  const current = post.value;
  if (!current || !canCopyPostBody(current)) return;
  try {
    await writeClipboardText(current.body);
    copyError.value = "";
    bodyCopied.value = true;
    window.clearTimeout(bodyCopyTimer);
    bodyCopyTimer = window.setTimeout(() => {
      bodyCopied.value = false;
    }, 2000);
  } catch (err) {
    copyError.value = clipboardUserMessage(err, "게시글 본문을 클립보드에 복사하지 못했습니다.");
  }
}

async function loadPost() {
  loading.value = true;
  error.value = "";
  try {
    const payload = await getPost(postId.value);
    if (cancelled) return;
    post.value = payload;
  } catch (loadError) {
    if (cancelled) return;
    post.value = null;
    error.value = (loadError as Error).message;
  } finally {
    if (!cancelled) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  loadPost();
});

onBeforeUnmount(() => {
  cancelled = true;
  resetCopyFeedback();
});

watch(postId, () => {
  resetCopyFeedback();
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
          <p class="eyebrow">Anonymous Board</p>
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
        <section class="card">
          <div class="section-heading">
            <h2>게시글 상세</h2>
            <span>댓글 {{ post.replies.length }}개</span>
          </div>

          <article class="detail-panel">
            <div class="detail-top">
              <div>
                <h3>{{ post.title }}</h3>
                <div class="detail-meta-row">
                  <span class="post-mode-badge" :class="{ file: isFileConversionMode(post.mode) }">
                    {{ getPostModeLabel(post.mode) }}
                  </span>
                  <span v-if="post.conversionReady" class="post-mode-badge success">암호화 업로드 완료</span>
                </div>
                <div class="post-author-row">
                  <span class="post-author-label">작성자</span>
                  <strong class="post-author-name" :class="{ muted: !post.authorUsername }">
                    {{ post.authorUsername || "작성자 없음" }}
                  </strong>
                  <time>{{ new Date(post.createdAt).toLocaleString() }}</time>
                </div>
              </div>
              <div v-if="canCopyPostBody(post)" class="inline-actions">
                <button type="button" class="ghost-button" @click="handleCopyPostBody">
                  {{ bodyCopied ? "복사됨!" : "본문 복사" }}
                </button>
              </div>
            </div>
            <p v-if="copyError" class="message-banner error" role="alert">{{ copyError }}</p>

            <p v-if="isFileConversionMode(post.mode)" class="post-body-box">
              암호화 업로드 글입니다. 본문은 공개 상세 화면에서 표시되지 않습니다.
            </p>
            <p v-else class="post-body-box">{{ post.body }}</p>

            <AttachmentPanel
              v-if="post.attachments.length > 0"
              :attachments="post.attachments"
              :mode="post.mode"
            />
          </article>
        </section>

        <section class="reply-section">
          <div class="section-heading">
            <h3>댓글</h3>
          </div>

          <p v-if="post.replies.length === 0" class="empty-state">아직 댓글이 없습니다.</p>
          <div v-else class="reply-list">
            <article
              v-for="reply in post.replies"
              :key="reply.id"
              class="card inset-card reply-card"
            >
              <div class="reply-top">
                <div class="reply-heading">
                  <strong>댓글 #{{ reply.id }}</strong>
                  <span v-if="reply.ai" class="ai-badge">
                    AI · {{ reply.aiProvider }} ({{ reply.aiModel }})
                  </span>
                </div>
                <time>{{ new Date(reply.createdAt).toLocaleString() }}</time>
              </div>
              <p class="detail-body">{{ reply.body }}</p>
            </article>
          </div>
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
