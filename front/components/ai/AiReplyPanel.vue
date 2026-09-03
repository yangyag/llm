<script setup lang="ts">
// 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 어디서도 import하지 않는 잔재 파일로 유지.
import { usePostDetailStore } from "~/stores/postDetail";
import type { AiProvider } from "~/types/api";

const detail = usePostDetailStore();

// AI 모델명은 하드코딩 유지 (별도 개선 과제).
const providers: { id: AiProvider; label: string }[] = [
  { id: "GPT", label: "GPT (gpt-5.5)" },
  { id: "CLAUDE", label: "Claude (claude-opus-4-7)" },
  { id: "GROK", label: "Grok (grok-4.3)" }
];
</script>

<template>
  <!-- 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. 잔재 파일로 유지. -->
  <div class="card inset-card form-grid ai-reply-card">
    <div class="section-heading">
      <h3>AI가 답변달기</h3>
    </div>
    <p class="section-meta">현재 게시글 본문을 기준으로 AI 답변을 생성합니다.</p>
    <div class="provider-options" role="radiogroup" aria-label="AI provider">
      <label v-for="p in providers" :key="p.id" class="provider-option">
        <input
          type="radio"
          name="ai-provider"
          :value="p.id"
          :checked="detail.selectedAiProvider === p.id"
          @change="detail.setAiProvider(p.id)"
        />
        <span>{{ p.label }}</span>
      </label>
    </div>
    <p v-if="detail.aiReplyError" class="panel-error">{{ detail.aiReplyError }}</p>
    <button
      type="button"
      class="ghost-button wide-button"
      :disabled="detail.aiSubmitting || detail.submitting"
      @click="detail.handleCreateAiReply()"
    >
      {{ detail.aiSubmitting ? "AI 답변 생성 중..." : "AI가 답변달기" }}
    </button>
  </div>
</template>
