<script setup lang="ts">
import { watch } from "vue";
import { EditorContent, useEditor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import { getApiUrl } from "~/services/api";
import { emptyPostDocument, toCanonicalPostDocument } from "~/utils/postDocument";
import { InlineAttachmentImage } from "./inlineAttachmentImage";
import type { PostDocument } from "~/types/api";

const props = withDefaults(defineProps<{
  document: PostDocument;
  inlineSources?: Record<string, string>;
}>(), {
  inlineSources: () => ({})
});

function resolveSource(imageKey: string): string | null {
  const raw = props.inlineSources[imageKey];
  return raw ? getApiUrl(raw) : null;
}

const editor = useEditor({
  content: toCanonicalPostDocument(props.document) ?? emptyPostDocument(),
  editable: false,
  extensions: [
    StarterKit.configure({ link: false }),
    InlineAttachmentImage.configure({ resolveSource })
  ],
  editorProps: {
    attributes: {
      class: "post-document-reader",
      role: "document",
      "aria-label": "게시글 본문"
    }
  }
});

watch(
  () => props.document,
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
:deep(.post-document-reader) {
  outline: none;
}

:deep(.post-document-reader p) {
  margin: 0;
}

:deep(.post-document-reader p + p) {
  margin-top: 0.6em;
}

:deep(.post-document-reader h1),
:deep(.post-document-reader h2),
:deep(.post-document-reader h3),
:deep(.post-document-reader h4),
:deep(.post-document-reader h5),
:deep(.post-document-reader h6) {
  margin: 0.9em 0 0.45em;
  line-height: 1.35;
}

:deep(.post-document-reader ul),
:deep(.post-document-reader ol) {
  margin: 0.4em 0;
  padding-left: 1.4em;
}

:deep(.post-document-reader blockquote) {
  margin: 0.6em 0;
  padding-left: 1em;
  border-left: 3px solid rgba(143, 183, 255, 0.35);
  color: #b9c9e2;
}

:deep(.post-document-reader pre) {
  margin: 0.6em 0;
  padding: 12px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.35);
  overflow-x: auto;
}

:deep(.post-document-reader code) {
  font-family: ui-monospace, monospace;
  font-size: 0.92em;
}

:deep(.post-document-reader hr) {
  margin: 1em 0;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.16);
}
</style>
