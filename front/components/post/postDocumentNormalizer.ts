import { Extension } from "@tiptap/core";
import { NodeSelection, Plugin, PluginKey, TextSelection } from "@tiptap/pm/state";
import type { EditorState, Transaction } from "@tiptap/pm/state";
import { Mapping } from "@tiptap/pm/transform";
import {
  LANGUAGE_PATTERN,
  MAX_ALT_LENGTH,
  MAX_ORDERED_LIST_START,
  MIN_ORDERED_LIST_START,
  UUID_PATTERN
} from "~/utils/postDocument";

// 편집기 상태가 저장 규칙(canonical 문서)을 벗어나지 않도록 입력·붙여넣기 직후 바로잡는다.
// canonical 변환이 실패하면 v-model이 갱신되지 않아 이후 입력이 저장되지 않으므로,
// Tiptap이 만들 수 있는 규칙 밖 값(번호 목록 start/type, 코드 언어, 이미지 키·alt)을 여기서 고친다.

export type PostDocumentNormalizeIssue = "duplicate-image" | "invalid-image";

export const postDocumentNormalizerKey = new PluginKey("postDocumentNormalizer");

const IMAGE_NODE = "inlineAttachmentImage";

function normalizeListStart(value: unknown): number {
  if (typeof value !== "number" || !Number.isInteger(value)) {
    return MIN_ORDERED_LIST_START;
  }
  return Math.min(Math.max(value, MIN_ORDERED_LIST_START), MAX_ORDERED_LIST_START);
}

function normalizeAlt(value: unknown): string {
  if (typeof value !== "string") {
    return "";
  }
  if (value.length <= MAX_ALT_LENGTH) {
    return value;
  }
  // 서로게이트 쌍 중간에서 자르면 서버가 단독 서로게이트로 거부한다.
  const lastCode = value.charCodeAt(MAX_ALT_LENGTH - 1);
  const end = lastCode >= 0xd800 && lastCode <= 0xdbff ? MAX_ALT_LENGTH - 1 : MAX_ALT_LENGTH;
  return value.slice(0, end);
}

/**
 * 문서를 저장 규칙에 맞게 고치는 트랜잭션을 만든다. 고칠 것이 없으면 null.
 *
 * @param state 검사할 편집기 상태
 * @param keepImagePositions 같은 키의 이미지가 여러 개일 때 우선 남길 위치(기존 이미지)
 */
export function normalizePostDocumentTransaction(
  state: EditorState,
  keepImagePositions: ReadonlySet<number> = new Set()
): { transaction: Transaction; issues: PostDocumentNormalizeIssue[] } | null {
  const markupFixes: Array<{ pos: number; attrs: Record<string, unknown> }> = [];
  const removals: number[] = [];
  const issues = new Set<PostDocumentNormalizeIssue>();
  const imagePositionsByKey = new Map<string, number[]>();

  state.doc.descendants((node, pos) => {
    const name = node.type.name;
    if (name === "orderedList") {
      const start = normalizeListStart(node.attrs.start);
      if (start !== node.attrs.start || node.attrs.type != null) {
        markupFixes.push({ pos, attrs: { ...node.attrs, start, type: null } });
      }
    } else if (name === "codeBlock") {
      const language = node.attrs.language;
      if (language != null && (typeof language !== "string" || !LANGUAGE_PATTERN.test(language))) {
        markupFixes.push({ pos, attrs: { ...node.attrs, language: null } });
      }
    } else if (name === IMAGE_NODE) {
      const imageKey = node.attrs.imageKey;
      if (typeof imageKey !== "string" || !UUID_PATTERN.test(imageKey)) {
        removals.push(pos);
        issues.add("invalid-image");
        return false;
      }
      const key = imageKey.toLowerCase();
      imagePositionsByKey.set(key, [...(imagePositionsByKey.get(key) ?? []), pos]);
      const alt = normalizeAlt(node.attrs.alt);
      if (alt !== node.attrs.alt) {
        markupFixes.push({ pos, attrs: { ...node.attrs, alt } });
      }
    }
    return true;
  });

  // 같은 이미지는 한 번만 둘 수 있다. 기존 이미지를 남기고 새로 들어온 사본을 뺀다.
  for (const positions of imagePositionsByKey.values()) {
    if (positions.length < 2) {
      continue;
    }
    const kept = positions.find((pos) => keepImagePositions.has(pos)) ?? positions[0];
    for (const pos of positions) {
      if (pos !== kept) {
        removals.push(pos);
        issues.add("duplicate-image");
      }
    }
  }

  if (markupFixes.length === 0 && removals.length === 0) {
    return null;
  }

  const transaction = state.tr;
  const removed = new Set(removals);
  // setNodeMarkup은 노드 크기를 바꾸지 않으므로 원래 위치를 그대로 쓸 수 있다.
  for (const fix of markupFixes) {
    if (!removed.has(fix.pos)) {
      transaction.setNodeMarkup(fix.pos, undefined, fix.attrs);
    }
  }
  // 붙여넣은 사본이 선택된 채 제외되면 선택이 옆의 원본 이미지로 옮겨 가 다음 입력이 원본을 덮어쓴다.
  const selectedRemoval = state.selection instanceof NodeSelection && removed.has(state.selection.from)
    ? state.selection.from
    : null;
  // 제외한 이미지는 빈 문단으로 바꾼다. blockquote 등의 필수 내용을 유지하고 그 자리에 커서를 둘 수 있다.
  // 뒤에서부터 바꿔야 앞쪽 위치가 유지된다.
  for (const pos of [...removals].sort((a, b) => b - a)) {
    const node = transaction.doc.nodeAt(pos);
    if (node) {
      transaction.replaceWith(pos, pos + node.nodeSize, state.schema.nodes.paragraph.create());
    }
  }
  if (selectedRemoval !== null) {
    const paragraphStart = transaction.mapping.map(selectedRemoval, -1);
    transaction.setSelection(TextSelection.create(transaction.doc, paragraphStart + 1));
  }
  return { transaction, issues: [...issues] };
}

function mapExistingImagePositions(oldState: EditorState, transactions: readonly Transaction[]): Set<number> {
  const mapping = new Mapping();
  for (const transaction of transactions) {
    mapping.appendMapping(transaction.mapping);
  }
  const positions = new Set<number>();
  oldState.doc.descendants((node, pos) => {
    if (node.type.name !== IMAGE_NODE) {
      return true;
    }
    // 바로 앞에 붙여넣은 내용이 있어도 기존 노드 쪽으로 매핑되도록 assoc=1을 쓴다.
    // 기존 노드가 지워졌다면 매핑 위치에 같은 키 이미지가 없으므로 선택되지 않는다.
    positions.add(mapping.map(pos, 1));
    return false;
  });
  return positions;
}

export function createPostDocumentNormalizerPlugin(): Plugin {
  return new Plugin({
    key: postDocumentNormalizerKey,
    appendTransaction(transactions, oldState, newState) {
      if (!transactions.some((transaction) => transaction.docChanged)) {
        return null;
      }
      const result = normalizePostDocumentTransaction(
        newState,
        mapExistingImagePositions(oldState, transactions)
      );
      if (!result) {
        return null;
      }
      return result.transaction.setMeta(postDocumentNormalizerKey, result.issues);
    }
  });
}

/** 편집기 트랜잭션에서 정규화로 처리한 문제를 모은다. */
export function readNormalizeIssues(transactions: readonly Transaction[]): PostDocumentNormalizeIssue[] {
  const issues = new Set<PostDocumentNormalizeIssue>();
  for (const transaction of transactions) {
    const meta = transaction.getMeta(postDocumentNormalizerKey);
    if (Array.isArray(meta)) {
      for (const issue of meta) {
        issues.add(issue as PostDocumentNormalizeIssue);
      }
    }
  }
  return [...issues];
}

export const PostDocumentNormalizer = Extension.create({
  name: "postDocumentNormalizer",

  addProseMirrorPlugins() {
    return [createPostDocumentNormalizerPlugin()];
  }
});
