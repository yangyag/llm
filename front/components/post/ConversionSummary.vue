<script setup lang="ts">
import type { PostDetail } from "~/types/api";

const props = defineProps<{
  post: PostDetail;
}>();
</script>

<template>
  <div class="conversion-summary-card">
    <div class="conversion-summary-header">
      <strong>Base64 본문 숨김</strong>
      <span class="post-mode-badge" :class="props.post.conversionReady ? 'success' : 'file'">
        {{ props.post.conversionReady ? "암호화 업로드 완료" : "업로드 대기" }}
      </span>
    </div>
    <p class="section-meta">
      암호화 업로드 글의 raw Base64 본문은 상세 화면에서 숨겨집니다.
    </p>
    <div class="conversion-summary-stats">
      <span>본문 길이 {{ props.post.body.length.toLocaleString() }}자</span>
      <span>
        {{
          props.post.conversionReady
            ? "암호화 업로드가 완료되어 복원 파일을 다운로드할 수 있습니다."
            : "암호화 업로드가 완료되면 복원 파일을 다운로드할 수 있습니다."
        }}
      </span>
    </div>
  </div>
</template>
