import { encode } from "js-base64";
import type { Attachment, PostDocument, PostDocumentMark, PostDocumentNode } from "~/types/api";

const TOP_LEVEL_BLOCKS = new Set([
  "paragraph",
  "heading",
  "blockquote",
  "bulletList",
  "orderedList",
  "codeBlock",
  "horizontalRule",
  "inlineAttachmentImage"
]);
const INLINE_NODES = new Set(["text", "hardBreak"]);
const LIST_ITEM_BLOCKS = new Set([
  "paragraph",
  "blockquote",
  "bulletList",
  "orderedList",
  "codeBlock",
  "horizontalRule",
  "inlineAttachmentImage"
]);
const ALLOWED_MARKS = new Set(["bold", "code", "italic", "strike", "underline"]);
const MAX_ALT_LENGTH = 200;
const LANGUAGE_PATTERN = /^[A-Za-z0-9_+.#-]{1,50}$/;
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readType(node: unknown): string | null {
  if (!isRecord(node) || typeof node.type !== "string") {
    return null;
  }
  return node.type;
}

function canonicalChildren(
  node: Record<string, unknown>,
  allowedTypes: Set<string>,
  required: boolean,
  seenImageKeys: Set<string>
): PostDocumentNode[] | null {
  const content = node.content;
  if (content === undefined || content === null) {
    return required ? null : [];
  }
  if (!Array.isArray(content)) {
    return null;
  }
  const children: PostDocumentNode[] = [];
  for (const child of content) {
    const childType = readType(child);
    if (childType === null || !allowedTypes.has(childType)) {
      return null;
    }
    const canonical = canonicalizeNode(child as Record<string, unknown>, childType, seenImageKeys);
    if (canonical === null) {
      return null;
    }
    children.push(canonical);
  }
  if (required && children.length === 0) {
    return null;
  }
  return children;
}

function canonicalInlineContainer(
  node: Record<string, unknown>,
  type: string,
  seenImageKeys: Set<string>
): PostDocumentNode | null {
  const content = canonicalChildren(node, INLINE_NODES, false, seenImageKeys);
  if (content === null) {
    return null;
  }
  const canonical: PostDocumentNode = { type };
  if (content.length > 0) {
    canonical.content = content;
  }
  return canonical;
}

function canonicalMarks(node: Record<string, unknown>): PostDocumentMark[] | null {
  const marks = node.marks;
  if (marks === undefined || marks === null) {
    return [];
  }
  if (!Array.isArray(marks)) {
    return null;
  }
  const types = new Set<string>();
  for (const mark of marks) {
    if (!isRecord(mark) || typeof mark.type !== "string" || !ALLOWED_MARKS.has(mark.type)) {
      return null;
    }
    if (types.has(mark.type)) {
      return null;
    }
    types.add(mark.type);
  }
  return [...types].sort().map((type) => ({ type: type as PostDocumentMark["type"] }));
}

function canonicalImage(node: Record<string, unknown>, seenImageKeys: Set<string>): PostDocumentNode | null {
  const attrs = node.attrs;
  if (!isRecord(attrs)) {
    return null;
  }
  const imageKey = attrs.imageKey;
  if (typeof imageKey !== "string" || !UUID_PATTERN.test(imageKey)) {
    return null;
  }
  const canonicalKey = imageKey.toLowerCase();
  if (seenImageKeys.has(canonicalKey)) {
    return null;
  }
  seenImageKeys.add(canonicalKey);
  let alt = "";
  const altValue = attrs.alt;
  if (altValue !== undefined && altValue !== null) {
    if (typeof altValue !== "string" || altValue.length > MAX_ALT_LENGTH) {
      return null;
    }
    alt = altValue;
  }
  return {
    type: "inlineAttachmentImage",
    attrs: { imageKey: canonicalKey, alt }
  };
}

function canonicalizeNode(
  node: Record<string, unknown>,
  type: string,
  seenImageKeys: Set<string>
): PostDocumentNode | null {
  switch (type) {
    case "paragraph":
      return canonicalInlineContainer(node, "paragraph", seenImageKeys);
    case "heading": {
      const attrs = node.attrs;
      if (!isRecord(attrs)) {
        return null;
      }
      const level = attrs.level;
      if (typeof level !== "number" || !Number.isInteger(level) || level < 1 || level > 6) {
        return null;
      }
      const content = canonicalChildren(node, INLINE_NODES, false, seenImageKeys);
      if (content === null) {
        return null;
      }
      const canonical: PostDocumentNode = { type: "heading" };
      if (content.length > 0) {
        canonical.content = content;
      }
      canonical.attrs = { level };
      return canonical;
    }
    case "blockquote": {
      const content = canonicalChildren(node, TOP_LEVEL_BLOCKS, true, seenImageKeys);
      if (content === null) {
        return null;
      }
      return { type: "blockquote", content };
    }
    case "bulletList": {
      const content = canonicalChildren(node, new Set(["listItem"]), true, seenImageKeys);
      if (content === null) {
        return null;
      }
      return { type: "bulletList", content };
    }
    case "orderedList": {
      let start = 1;
      const attrs = node.attrs;
      if (attrs !== undefined && attrs !== null) {
        if (!isRecord(attrs)) {
          return null;
        }
        if (attrs.start !== undefined && attrs.start !== null) {
          const startValue = attrs.start;
          if (
            typeof startValue !== "number" ||
            !Number.isInteger(startValue) ||
            startValue < 1 ||
            startValue > 1_000_000
          ) {
            return null;
          }
          start = startValue;
        }
        if (attrs.type !== undefined && attrs.type !== null) {
          return null;
        }
      }
      const content = canonicalChildren(node, new Set(["listItem"]), true, seenImageKeys);
      if (content === null) {
        return null;
      }
      return { type: "orderedList", content, attrs: { start, type: null } };
    }
    case "listItem": {
      const content = node.content;
      if (!Array.isArray(content) || content.length === 0) {
        return null;
      }
      const children: PostDocumentNode[] = [];
      for (let index = 0; index < content.length; index++) {
        const child = content[index];
        const childType = readType(child);
        const allowed = index === 0 ? childType === "paragraph" : childType !== null && LIST_ITEM_BLOCKS.has(childType);
        if (childType === null || !allowed) {
          return null;
        }
        const canonical = canonicalizeNode(child as Record<string, unknown>, childType, seenImageKeys);
        if (canonical === null) {
          return null;
        }
        children.push(canonical);
      }
      return { type: "listItem", content: children };
    }
    case "codeBlock": {
      let language: string | null = null;
      const attrs = node.attrs;
      if (attrs !== undefined && attrs !== null) {
        if (!isRecord(attrs)) {
          return null;
        }
        if (attrs.language !== undefined && attrs.language !== null) {
          if (typeof attrs.language !== "string" || !LANGUAGE_PATTERN.test(attrs.language)) {
            return null;
          }
          language = attrs.language;
        }
      }
      const content = canonicalChildren(node, new Set(["text"]), false, seenImageKeys);
      if (content === null) {
        return null;
      }
      const canonical: PostDocumentNode = { type: "codeBlock" };
      if (content.length > 0) {
        canonical.content = content;
      }
      canonical.attrs = { language };
      return canonical;
    }
    case "horizontalRule":
      return { type: "horizontalRule" };
    case "hardBreak":
      return { type: "hardBreak" };
    case "inlineAttachmentImage":
      return canonicalImage(node, seenImageKeys);
    case "text": {
      const text = node.text;
      if (typeof text !== "string" || text.length === 0) {
        return null;
      }
      const marks = canonicalMarks(node);
      if (marks === null) {
        return null;
      }
      const canonical: PostDocumentNode = { type: "text", text };
      if (marks.length > 0) {
        canonical.marks = marks;
      }
      return canonical;
    }
    default:
      return null;
  }
}

export function emptyPostDocument(): PostDocument {
  return { type: "doc", content: [{ type: "paragraph" }] };
}

export function plainTextToPostDocument(body: string): PostDocument {
  const content = body
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line): PostDocumentNode => {
      if (line.length === 0) {
        return { type: "paragraph" };
      }
      return { type: "paragraph", content: [{ type: "text", text: line }] };
    });
  return { type: "doc", content };
}

export function toCanonicalPostDocument(value: unknown): PostDocument | null {
  if (!isRecord(value) || value.type !== "doc") {
    return null;
  }
  const content = canonicalChildren(value, TOP_LEVEL_BLOCKS, false, new Set());
  if (content === null) {
    return null;
  }
  return { type: "doc", content };
}

export function isPostDocument(value: unknown): value is PostDocument {
  return toCanonicalPostDocument(value) !== null;
}

export function resolvePostDocument(
  bodyFormat: unknown,
  bodyDocument: unknown,
  plainBody: string
): PostDocument {
  if (bodyFormat === "TIPTAP_JSON") {
    const canonical = toCanonicalPostDocument(bodyDocument);
    if (canonical !== null) {
      return canonical;
    }
  }
  return plainTextToPostDocument(plainBody);
}

export function collectInlineImageKeys(document: unknown): string[] {
  const canonical = toCanonicalPostDocument(document);
  if (canonical === null) {
    return [];
  }
  const keys: string[] = [];
  const walk = (nodes: PostDocumentNode[]): void => {
    for (const node of nodes) {
      if (node.type === "inlineAttachmentImage" && typeof node.attrs?.imageKey === "string") {
        keys.push(node.attrs.imageKey);
      }
      if (node.content) {
        walk(node.content);
      }
    }
  };
  walk(canonical.content);
  return keys;
}

export function buildInlineImageSources(attachments: readonly Attachment[]): Record<string, string> {
  const sources: Record<string, string> = {};
  for (const attachment of attachments) {
    if (
      attachment.attachmentKind === "INLINE_IMAGE" &&
      typeof attachment.inlineKey === "string" &&
      attachment.inlineKey.length > 0 &&
      typeof attachment.contentUrl === "string" &&
      attachment.contentUrl.length > 0
    ) {
      sources[attachment.inlineKey] = attachment.contentUrl;
    }
  }
  return sources;
}

export function filterDownloadAttachments(attachments: readonly Attachment[]): Attachment[] {
  return attachments.filter((attachment) => attachment.attachmentKind === "DOWNLOAD");
}

export function buildRichPostBodyPayload(document: unknown): {
  bodyFormat: "TIPTAP_JSON";
  bodyDocumentBase64: string;
} {
  const canonical = toCanonicalPostDocument(document);
  if (canonical === null) {
    throw new Error("post document is not canonicalizable");
  }
  return {
    bodyFormat: "TIPTAP_JSON",
    bodyDocumentBase64: encode(JSON.stringify(canonical))
  };
}
