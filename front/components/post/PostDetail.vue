<script setup lang="ts">
import { computed } from "vue";
import { useAuthStore } from "~/stores/auth";
import { usePostDetailStore } from "~/stores/postDetail";
import { canCopyPostBody, canManagePost, getPostModeLabel, isFileConversionMode } from "~/utils/post";
import AttachmentPanel from "./AttachmentPanel.vue";
import ConversionSummary from "./ConversionSummary.vue";
import PostEditPanel from "./PostEditPanel.vue";

const auth = useAuthStore();
const detail = usePostDetailStore();

const manageable = computed(() =>
  canManagePost(detail.selectedPost?.authorUsername, auth.username, auth.role)
);

const authorLabel = computed(() => detail.selectedPost?.authorUsername || "작성자 없음");

async function onDelete() {
  if (!window.confirm("이 게시글을 삭제하시겠습니까?")) return;
  await detail.handleDeletePost();
}
</script>

<template>
  <article class="detail-panel">
    <div class="detail-top">
      <div>
        <h3>{{ detail.selectedPost?.title }}</h3>
        <div class="detail-meta-row">
          <span
            class="post-mode-badge"
            :class="{ file: isFileConversionMode(detail.selectedPost?.mode ?? '') }"
          >
            {{ getPostModeLabel(detail.selectedPost?.mode ?? '') }}
          </span>
          <span v-if="detail.selectedPost?.conversionReady" class="post-mode-badge success">
            암호화 업로드 완료
          </span>
        </div>
        <div class="post-author-row">
          <span class="post-author-label">작성자</span>
          <strong class="post-author-name" :class="{ muted: !detail.selectedPost?.authorUsername }">
            {{ authorLabel }}
          </strong>
          <time>{{ new Date(detail.selectedPost?.createdAt ?? '').toLocaleString() }}</time>
        </div>
      </div>
      <div class="inline-actions">
        <button type="button" class="ghost-button" @click="detail.handleCopyPostLink()">
          {{ detail.postLinkCopied ? "복사됨!" : "링크 복사" }}
        </button>
        <button
          v-if="!isFileConversionMode(detail.selectedPost?.mode ?? '')"
          type="button"
          class="ghost-button"
          :disabled="!canCopyPostBody(detail.selectedPost)"
          :title="canCopyPostBody(detail.selectedPost) ? undefined : '본문이 없습니다'"
          @click="detail.handleCopyPostBody()"
        >
          {{ detail.postBodyCopied ? "복사됨!" : "본문 복사" }}
        </button>
        <button
          v-if="manageable && !detail.selectedPost?.conversionReady && detail.postActionMode !== 'edit'"
          type="button"
          class="ghost-button"
          @click="detail.openPostEditPanel()"
        >
          수정
        </button>
        <button
          v-if="manageable"
          type="button"
          class="danger-button"
          @click="onDelete"
        >
          삭제
        </button>
      </div>
    </div>

    <ConversionSummary
      v-if="detail.selectedPost && isFileConversionMode(detail.selectedPost.mode)"
      :post="detail.selectedPost"
    />
    <p v-else class="post-body-box">{{ detail.selectedPost?.body }}</p>

    <AttachmentPanel
      v-if="detail.selectedPost && detail.selectedPost.attachments.length > 0"
      :attachments="detail.selectedPost.attachments"
      :mode="detail.selectedPost.mode"
    />

    <PostEditPanel v-if="detail.postActionMode === 'edit' && manageable" />
  </article>
</template>
