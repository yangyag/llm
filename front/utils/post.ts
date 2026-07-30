// 게시글/첨부 공용 유틸 — WelcomePage.jsx의 helper 함수 이관.
import type { PostMode } from "~/types/api";

export const MAX_ATTACHMENTS = 5;
export const ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE = "첨부파일을 올려도 되는 환경입니까?";

export function attachmentFileKey(file: File): string {
  return `${file.name}::${file.size}::${file.lastModified}`;
}

// 기존 선택 목록에 새로 고른 파일을 합치되, 중복(이름/크기/수정시각 동일)은 제거하고 최대 개수로 제한한다.
export function mergeAttachmentFiles(
  existingFiles: File[],
  incomingFiles: File[],
  max = MAX_ATTACHMENTS
): { files: File[]; truncated: boolean } {
  const merged = [...existingFiles];
  const seen = new Set(existingFiles.map(attachmentFileKey));
  for (const file of incomingFiles) {
    const key = attachmentFileKey(file);
    if (!seen.has(key)) {
      seen.add(key);
      merged.push(file);
    }
  }
  return {
    files: merged.slice(0, max),
    truncated: merged.length > max
  };
}

export function isFileConversionMode(mode: PostMode | string): boolean {
  return mode === "FILE_CONVERSION_REQUEST";
}

export function getPostModeLabel(mode: PostMode | string): string {
  return isFileConversionMode(mode) ? "암호화 업로드" : "일반";
}

export function getPostBodyLabel(): string {
  return "본문";
}

export function getPostBodyHelp(): string {
  return `본문은 비워둘 수 있습니다. 첨부파일은 최대 ${MAX_ATTACHMENTS}개, 파일당 100MB까지 업로드할 수 있습니다.`;
}

export function formatFileSize(size: number): string {
  if (!Number.isFinite(size) || size < 1024) {
    return `${size ?? 0}B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)}KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)}MB`;
}
