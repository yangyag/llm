<script setup lang="ts">
import { ref, watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import { TextSelection } from "@tiptap/pm/state";
import StarterKit from "@tiptap/starter-kit";
import { getApiUrl } from "~/services/api";
import { collectInlineImageKeys, emptyPostDocument, toCanonicalPostDocument } from "~/utils/postDocument";
import {
  INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE,
  UNSUPPORTED_INLINE_IMAGE_MESSAGE,
  readClipboardImageFiles,
  resolveInlineImageType
} from "~/composables/useInlineImageDraft";
import type { InlineImageDraftRegistrationResult } from "~/composables/useInlineImageDraft";
import { InlineAttachmentImage } from "./inlineAttachmentImage";
import { PostDocumentNormalizer, readNormalizeIssues } from "./postDocumentNormalizer";
import type { PostDocument } from "~/types/api";

const DUPLICATE_INLINE_IMAGE_MESSAGE = "같은 본문 이미지는 한 번만 넣을 수 있어 중복된 이미지를 제외했습니다.";
const INVALID_INLINE_IMAGE_MESSAGE = "불러올 수 없는 본문 이미지를 제외했습니다.";
const UNSAVABLE_DOCUMENT_MESSAGE =
  "본문에 저장할 수 없는 서식이 있어 마지막 변경이 반영되지 않았습니다. 방금 입력하거나 붙여넣은 내용을 되돌린 뒤 다시 시도해 주세요.";

const props = withDefaults(defineProps<{
  modelValue: PostDocument;
  inlineSources?: Record<string, string>;
  ariaLabel?: string;
  registerInlineImages?: (files: readonly File[]) => InlineImageDraftRegistrationResult;
  maxActiveInlineImages?: number;
}>(), {
  inlineSources: () => ({}),
  ariaLabel: "게시글 본문",
  maxActiveInlineImages: 5
});

const emit = defineEmits<{
  "update:modelValue": [value: PostDocument];
  "inline-image-error": [message: string];
  "document-error": [message: string];
}>();

const inlineImageInput = ref<HTMLInputElement | null>(null);
let documentError = "";

function setDocumentError(message: string): void {
  if (message === documentError) {
    return;
  }
  documentError = message;
  emit("document-error", message);
}

function resolveSource(imageKey: string): string | null {
  const raw = props.inlineSources[imageKey];
  if (!raw) {
    return null;
  }
  return raw.startsWith("blob:") ? raw : getApiUrl(raw);
}

function insertRegisteredImages(files: readonly File[], alt: string): void {
  const register = props.registerInlineImages;
  const instance = editor.value;
  if (!register || !instance || files.length === 0) {
    return;
  }
  if (files.some((file) => !resolveInlineImageType(file))) {
    emit("inline-image-error", UNSUPPORTED_INLINE_IMAGE_MESSAGE);
    return;
  }
  const activeCount = collectInlineImageKeys(instance.state.doc.toJSON()).length;
  if (activeCount + files.length > props.maxActiveInlineImages) {
    emit("inline-image-error", INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE);
    return;
  }
  const result = register(files);
  if (result.error) {
    emit("inline-image-error", result.error);
    return;
  }
  if (result.entries.length === 0) {
    return;
  }
  const lastKey = result.entries[result.entries.length - 1].imageKey;
  instance.chain().focus().insertContent(
    result.entries.map((entry) => ({
      type: "inlineAttachmentImage",
      attrs: { imageKey: entry.imageKey, alt }
    }))
  ).command(({ tr, state }) => {
    let nodeEnd = -1;
    tr.doc.descendants((node, pos) => {
      if (node.type.name === "inlineAttachmentImage" && node.attrs.imageKey === lastKey) {
        nodeEnd = pos + node.nodeSize;
        return false;
      }
      return true;
    });
    if (nodeEnd < 0) {
      return true;
    }
    const next = tr.doc.resolve(nodeEnd).nodeAfter;
    if (next?.isTextblock) {
      tr.setSelection(TextSelection.create(tr.doc, nodeEnd + 1));
    } else {
      tr.insert(nodeEnd, state.schema.nodes.paragraph.create());
      tr.setSelection(TextSelection.create(tr.doc, nodeEnd + 1));
    }
    return true;
  }).run();
}

function openInlineImagePicker() {
  inlineImageInput.value?.click();
}

function onInlineImageInputChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  try {
    if (files.length > 0) {
      insertRegisteredImages(files, "선택한 이미지");
    }
  } finally {
    input.value = "";
    if (files.length > 0 && document.activeElement === input) {
      editor.value?.chain().focus().run();
    }
  }
}

const editor = useEditor({
  content: toCanonicalPostDocument(props.modelValue) ?? emptyPostDocument(),
  editable: true,
  extensions: [
    StarterKit.configure({ link: false }),
    InlineAttachmentImage.configure({ resolveSource }),
    PostDocumentNormalizer
  ],
  editorProps: {
    attributes: {
      class: "post-document-editor",
      role: "textbox",
      "aria-label": props.ariaLabel,
      "aria-multiline": "true"
    },
    handlePaste: (_view, event) => {
      const clipboard = readClipboardImageFiles(event.clipboardData);
      if (!clipboard.hasImage || !props.registerInlineImages) {
        return false;
      }
      event.preventDefault();
      if (clipboard.unsupportedTypes.length > 0) {
        emit("inline-image-error", UNSUPPORTED_INLINE_IMAGE_MESSAGE);
        return true;
      }
      insertRegisteredImages(clipboard.files, "붙여넣은 이미지");
      return true;
    }
  },
  onTransaction: ({ appendedTransactions }) => {
    const issues = readNormalizeIssues(appendedTransactions);
    if (issues.includes("duplicate-image")) {
      emit("inline-image-error", DUPLICATE_INLINE_IMAGE_MESSAGE);
    } else if (issues.includes("invalid-image")) {
      emit("inline-image-error", INVALID_INLINE_IMAGE_MESSAGE);
    }
  },
  onUpdate: ({ editor: instance }) => {
    const canonical = toCanonicalPostDocument(instance.getJSON());
    if (!canonical) {
      // 정규화로도 고치지 못한 상태. 조용히 넘어가면 이후 입력이 저장되지 않으므로 알리고 저장을 막는다.
      setDocumentError(UNSAVABLE_DOCUMENT_MESSAGE);
      return;
    }
    setDocumentError("");
    emit("update:modelValue", canonical);
  }
});

watch(
  () => props.modelValue,
  (value) => {
    const next = toCanonicalPostDocument(value);
    if (!next) return;
    const current = toCanonicalPostDocument(editor.value?.getJSON());
    if (JSON.stringify(current) === JSON.stringify(next)) return;
    editor.value?.commands.setContent(next, { emitUpdate: false });
  },
  { deep: true }
);
</script>

<template>
  <div v-if="registerInlineImages" class="post-document-editor-toolbar">
    <input
      ref="inlineImageInput"
      class="post-document-editor-file-input"
      type="file"
      accept="image/png,image/jpeg"
      multiple
      tabindex="-1"
      aria-label="본문 이미지 파일 선택"
      aria-hidden="true"
      @change="onInlineImageInputChange"
    />
    <button type="button" class="ghost-button" :disabled="maxActiveInlineImages <= 0" @click="openInlineImagePicker">
      본문 이미지 추가
    </button>
  </div>
  <EditorContent :editor="editor" />
</template>

<style scoped>
.post-document-editor-toolbar {
  display: flex;
  justify-content: flex-end;
}

.post-document-editor-toolbar .ghost-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.post-document-editor-file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  border: 0;
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (max-width: 640px) {
  .post-document-editor-toolbar .ghost-button {
    width: 100%;
    min-height: 44px;
  }
}

:deep(.post-document-editor) {
  min-height: 240px;
  padding: 12px 14px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  color: #eef5ff;
  font: inherit;
  line-height: 1.8;
  outline: none;
  transition: border-color 0.15s ease, background 0.15s ease;
}

:deep(.post-document-editor:focus) {
  border-color: rgba(143, 183, 255, 0.55);
  background: rgba(255, 255, 255, 0.06);
}

:deep(.post-document-editor p) {
  margin: 0;
}

:deep(.post-document-editor p + p) {
  margin-top: 0.6em;
}

:deep(.post-document-editor h1),
:deep(.post-document-editor h2),
:deep(.post-document-editor h3),
:deep(.post-document-editor h4),
:deep(.post-document-editor h5),
:deep(.post-document-editor h6) {
  margin: 0.8em 0 0.4em;
  line-height: 1.35;
}

:deep(.post-document-editor ul),
:deep(.post-document-editor ol) {
  margin: 0.4em 0;
  padding-left: 1.4em;
}

:deep(.post-document-editor blockquote) {
  margin: 0.6em 0;
  padding-left: 1em;
  border-left: 3px solid rgba(143, 183, 255, 0.35);
  color: #b9c9e2;
}

:deep(.post-document-editor pre) {
  margin: 0.6em 0;
  padding: 12px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.35);
  overflow-x: auto;
}

:deep(.post-document-editor code) {
  font-family: ui-monospace, monospace;
  font-size: 0.92em;
}

:deep(.post-document-editor hr) {
  margin: 1em 0;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.16);
}
</style>
