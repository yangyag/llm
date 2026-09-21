<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { NodeViewWrapper, nodeViewProps } from "@tiptap/vue-3";
import type { InlineAttachmentImageOptions } from "./inlineAttachmentImage";

const props = defineProps(nodeViewProps);

const loadFailed = ref(false);

const imageKey = computed(() =>
  typeof props.node.attrs.imageKey === "string" ? props.node.attrs.imageKey : ""
);
const alt = computed(() =>
  typeof props.node.attrs.alt === "string" ? props.node.attrs.alt : ""
);
const source = computed(() => {
  if (!imageKey.value) return null;
  const options = props.extension.options as InlineAttachmentImageOptions;
  return options.resolveSource(imageKey.value);
});

watch(source, () => {
  loadFailed.value = false;
});
</script>

<template>
  <NodeViewWrapper
    as="figure"
    class="inline-attachment-image"
    :class="{ selected: selected }"
    data-type="inline-attachment-image"
    :data-image-key="imageKey"
  >
    <img
      v-if="source && !loadFailed"
      :src="source"
      :alt="alt"
      loading="lazy"
      @error="loadFailed = true"
    />
    <div
      v-else
      class="inline-attachment-image-placeholder"
      role="img"
      :aria-label="alt || '본문 이미지'"
    >
      이미지를 불러올 수 없습니다.
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.inline-attachment-image {
  margin: 1em 0;
}

.inline-attachment-image img {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 0 auto;
  border-radius: 12px;
}

.inline-attachment-image.selected {
  outline: 2px solid rgba(143, 183, 255, 0.55);
  border-radius: 12px;
}

.inline-attachment-image-placeholder {
  padding: 24px;
  border: 1px dashed rgba(143, 183, 255, 0.35);
  border-radius: 12px;
  color: #9bb0ca;
  font-size: 0.9rem;
  text-align: center;
}
</style>
