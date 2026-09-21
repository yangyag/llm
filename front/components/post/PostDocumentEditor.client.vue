<script setup lang="ts">
import { watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import { TextSelection } from "@tiptap/pm/state";
import StarterKit from "@tiptap/starter-kit";
import { getApiUrl } from "~/services/api";
import { collectInlineImageKeys, emptyPostDocument, toCanonicalPostDocument } from "~/utils/postDocument";
import {
  INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE,
  UNSUPPORTED_INLINE_IMAGE_MESSAGE,
  readClipboardImageFiles
} from "~/composables/useInlineImageDraft";
import type { InlineImageDraftRegistrationResult } from "~/composables/useInlineImageDraft";
import { InlineAttachmentImage } from "./inlineAttachmentImage";
import type { PostDocument } from "~/types/api";

const props = withDefaults(defineProps<{
  modelValue: PostDocument;
  inlineSources?: Record<string, string>;
  ariaLabel?: string;
  registerPastedImages?: (files: readonly File[]) => InlineImageDraftRegistrationResult;
  maxActiveInlineImages?: number;
}>(), {
  inlineSources: () => ({}),
  ariaLabel: "게시글 본문",
  maxActiveInlineImages: 5
});

const emit = defineEmits<{
  "update:modelValue": [value: PostDocument];
  "inline-image-error": [message: string];
}>();

function resolveSource(imageKey: string): string | null {
  const raw = props.inlineSources[imageKey];
  if (!raw) {
    return null;
  }
  return raw.startsWith("blob:") ? raw : getApiUrl(raw);
}

const editor = useEditor({
  content: toCanonicalPostDocument(props.modelValue) ?? emptyPostDocument(),
  editable: true,
  extensions: [
    StarterKit.configure({ link: false }),
    InlineAttachmentImage.configure({ resolveSource })
  ],
  editorProps: {
    attributes: {
      class: "post-document-editor",
      role: "textbox",
      "aria-label": props.ariaLabel,
      "aria-multiline": "true"
    },
    handlePaste: (view, event) => {
      const register = props.registerPastedImages;
      const clipboard = readClipboardImageFiles(event.clipboardData);
      if (!clipboard.hasImage || !register) {
        return false;
      }
      event.preventDefault();
      if (clipboard.unsupportedTypes.length > 0) {
        emit("inline-image-error", UNSUPPORTED_INLINE_IMAGE_MESSAGE);
        return true;
      }
      const activeCount = collectInlineImageKeys(view.state.doc.toJSON()).length;
      if (activeCount + clipboard.files.length > props.maxActiveInlineImages) {
        emit("inline-image-error", INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE);
        return true;
      }
      const result = register(clipboard.files);
      if (result.error) {
        emit("inline-image-error", result.error);
        return true;
      }
      if (result.entries.length === 0) {
        return true;
      }
      const lastKey = result.entries[result.entries.length - 1].imageKey;
      editor.value?.chain().focus().insertContent(
        result.entries.map((entry) => ({
          type: "inlineAttachmentImage",
          attrs: { imageKey: entry.imageKey, alt: "붙여넣은 이미지" }
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
      return true;
    }
  },
  onUpdate: ({ editor: instance }) => {
    const canonical = toCanonicalPostDocument(instance.getJSON());
    if (canonical) {
      emit("update:modelValue", canonical);
    }
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
  <EditorContent :editor="editor" />
</template>

<style scoped>
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
