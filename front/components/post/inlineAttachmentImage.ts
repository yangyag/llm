import { Node } from "@tiptap/core";
import { VueNodeViewRenderer } from "@tiptap/vue-3";
import InlineAttachmentImageNodeView from "./InlineAttachmentImageNodeView.vue";

export interface InlineAttachmentImageOptions {
  resolveSource: (imageKey: string) => string | null;
}

export const InlineAttachmentImage = Node.create<InlineAttachmentImageOptions>({
  name: "inlineAttachmentImage",
  group: "block",
  atom: true,
  selectable: true,
  isolating: true,
  draggable: false,

  addOptions() {
    return {
      resolveSource: () => null
    };
  },

  addAttributes() {
    return {
      imageKey: { default: null, rendered: false },
      alt: { default: "", rendered: false }
    };
  },

  parseHTML() {
    return [
      {
        tag: 'figure[data-type="inline-attachment-image"]',
        getAttrs: (element) => ({
          imageKey: element.getAttribute("data-image-key"),
          alt: element.getAttribute("data-alt") ?? ""
        })
      }
    ];
  },

  renderHTML({ node }) {
    return [
      "figure",
      {
        "data-type": "inline-attachment-image",
        "data-image-key": node.attrs.imageKey,
        "data-alt": node.attrs.alt
      }
    ];
  },

  addNodeView() {
    return VueNodeViewRenderer(InlineAttachmentImageNodeView);
  }
});
