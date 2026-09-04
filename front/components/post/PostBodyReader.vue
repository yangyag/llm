<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { isFileConversionMode } from "~/utils/post";

const props = defineProps<{
  body: string;
  mode: string;
}>();

const FONT_STEPS = [15, 16, 17, 19, 21] as const;
const DEFAULT_STEP = 2;
const LONG_BODY_CHARS = 1200;

const step = ref(DEFAULT_STEP);
const expanded = ref(false);
const copied = ref(false);
const copyFailed = ref(false);

let copyTimer: ReturnType<typeof setTimeout> | undefined;

const fontSize = computed(() => FONT_STEPS[step.value]);
const canShrink = computed(() => step.value > 0);
const canGrow = computed(() => step.value < FONT_STEPS.length - 1);

/** 문단 나누기: 빈 줄 기준. 줄바꿈만 있는 경우도 그대로 살린다. */
const paragraphs = computed(() =>
  props.body
    .split(/\n{2,}/)
    .map((text) => text.trim())
    .filter((text) => text.length > 0)
);
const hasBody = computed(() => paragraphs.value.length > 0);

const isLong = computed(
  () => !isFileConversionMode(props.mode) && props.body.length > LONG_BODY_CHARS
);
const collapsed = computed(() => isLong.value && !expanded.value);

/** 공백 제외 어림 분량. */
const readingLabel = computed(() => {
  const chars = props.body.replace(/\s/g, "").length;
  if (chars === 0) return "";
  const minutes = Math.max(1, Math.ceil(chars / 500));
  return `${chars.toLocaleString()}자 · 약 ${minutes}분`;
});

function shrink() {
  if (canShrink.value) step.value -= 1;
}

function grow() {
  if (canGrow.value) step.value += 1;
}

function resetCopyFeedback() {
  window.clearTimeout(copyTimer);
  copyTimer = undefined;
}

async function copyBody() {
  if (!hasBody.value || isFileConversionMode(props.mode)) return;
  copyFailed.value = false;
  try {
    await navigator.clipboard.writeText(props.body);
    copied.value = true;
    resetCopyFeedback();
    copyTimer = window.setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch {
    copied.value = false;
    copyFailed.value = true;
  }
}

watch(
  () => props.body,
  () => {
    copied.value = false;
    copyFailed.value = false;
    expanded.value = false;
    resetCopyFeedback();
  }
);

onBeforeUnmount(() => {
  resetCopyFeedback();
});
</script>

<template>
  <div class="prose" :style="{ '--prose-font-size': `${fontSize}px` }">
    <div class="prose-tools" role="toolbar" aria-label="읽기 설정">
      <span v-if="readingLabel" class="prose-length">{{ readingLabel }}</span>
      <div class="prose-controls">
        <button type="button" :disabled="!canShrink" title="글자 작게" @click="shrink">가−</button>
        <button type="button" :disabled="!canGrow" title="글자 크게" @click="grow">가＋</button>
        <button
          v-if="!isFileConversionMode(props.mode)"
          type="button"
          :disabled="!hasBody"
          @click="copyBody"
        >
          {{ copied ? "복사됨!" : "복사" }}
        </button>
      </div>
    </div>
    <p v-if="copyFailed" class="prose-copy-error" role="alert">
      클립보드 복사에 실패했습니다. 직접 드래그해서 복사해 주세요.
    </p>

    <div class="prose-body" :class="{ faded: collapsed }">
      <p v-if="!hasBody" class="prose-empty">작성된 본문이 없습니다.</p>
      <p v-for="(paragraph, index) in paragraphs" :key="`${index}-${paragraph.length}`">
        {{ paragraph }}
      </p>
    </div>
    <button v-if="isLong" type="button" class="prose-more" @click="expanded = !expanded">
      {{ expanded ? "접기 ▲" : "계속 읽기 ▼" }}
    </button>
  </div>
</template>

<style scoped>
.prose {
  --prose-font-size: 17px;
  margin-top: 18px;
}

.prose-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.prose-length {
  color: #7f93ad;
  font-size: 0.82rem;
}

.prose-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}

.prose-controls button {
  font: inherit;
  font-size: 0.82rem;
  padding: 6px 9px;
  border: 0;
  border-radius: 8px;
  background: none;
  color: #7f93ad;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}

.prose-controls button:hover:not(:disabled) {
  color: #eef5ff;
  background: rgba(255, 255, 255, 0.06);
}

.prose-controls button:disabled {
  cursor: not-allowed;
  opacity: 0.35;
}

.prose-copy-error {
  margin: 0 0 8px;
  color: #ff9a9a;
  font-size: 0.86rem;
}

.prose-body {
  max-width: 68ch;
  color: #dde7f5;
  font-size: var(--prose-font-size);
  line-height: 1.9;
  overflow-wrap: anywhere;
}

.prose-body p {
  margin: 0;
  white-space: pre-wrap;
}

.prose-body p + p {
  margin-top: 1.1em;
}

.prose-body.faded {
  max-height: 420px;
  overflow: hidden;
  mask-image: linear-gradient(180deg, #000 62%, transparent 100%);
}

.prose-more {
  display: block;
  width: 100%;
  margin-top: 10px;
  padding: 12px;
  font: inherit;
  font-size: 0.9rem;
  font-weight: 700;
  color: #d6e6ff;
  background: rgba(143, 183, 255, 0.08);
  border: 1px solid rgba(143, 183, 255, 0.18);
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.prose-more:hover {
  background: rgba(143, 183, 255, 0.14);
}

.prose-empty {
  color: #9bb0ca;
}

@media (max-width: 640px) {
  .prose-body {
    line-height: 1.85;
  }
}

@media print {
  .prose-tools,
  .prose-more {
    display: none;
  }

  .prose-body {
    max-width: none;
    max-height: none;
    color: #000;
  }

  .prose-body.faded {
    mask-image: none;
  }
}
</style>
