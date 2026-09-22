import { computed, ref } from "vue";
import type { ComputedRef, Ref } from "vue";
import { collectInlineImageKeys } from "~/utils/postDocument";
import type { PostInlineImageUpload } from "~/types/api";

export const MAX_PENDING_INLINE_IMAGES = 20;
export const MAX_PENDING_INLINE_IMAGE_BYTES = 100 * 1024 * 1024;
export const MAX_INLINE_IMAGE_FILE_SIZE = 10 * 1024 * 1024;
export const UNSUPPORTED_INLINE_IMAGE_MESSAGE = "본문 이미지는 PNG 또는 JPEG 파일만 추가할 수 있습니다.";
export const INLINE_IMAGE_FILE_SIZE_MESSAGE = "본문 이미지는 파일당 최대 10MB까지 추가할 수 있습니다.";
export const INLINE_IMAGE_REGISTRY_COUNT_MESSAGE = "본문 이미지는 편집 세션당 최대 20개까지 보관할 수 있습니다. 글을 저장하거나 편집을 취소한 뒤 다시 시도해 주세요.";
export const INLINE_IMAGE_REGISTRY_BYTES_MESSAGE = "본문 이미지 임시 보관 용량은 최대 100MB입니다. 글을 저장하거나 편집을 취소한 뒤 다시 시도해 주세요.";
export const INLINE_IMAGE_ATTACHMENT_COUNT_MESSAGE = "일반 첨부파일과 본문 이미지는 합쳐서 최대 5개까지 등록할 수 있습니다.";
export const MISSING_INLINE_IMAGE_FILE_MESSAGE = "본문 이미지 임시 파일을 찾을 수 없습니다. 이미지를 다시 추가해 주세요.";

const SUPPORTED_INLINE_IMAGE_TYPES = new Set(["image/png", "image/jpeg"]);

export interface InlineImageCrypto {
  randomUUID?: () => string;
  getRandomValues?: (array: Uint8Array) => Uint8Array;
}

export interface InlineImageDraftEntry extends PostInlineImageUpload {
  objectUrl: string;
}

export interface ClipboardImageFiles {
  hasImage: boolean;
  files: File[];
  unsupportedTypes: string[];
}

export interface InlineImageDraftRegistrationResult {
  entries: InlineImageDraftEntry[];
  error: string | null;
}

export interface InlineImageUploadBuildResult {
  inlineImages: PostInlineImageUpload[];
  error: string | null;
}

export interface InlineImageDraftOptions {
  confirmUpload: () => boolean;
  reservedImageKeys?: () => readonly string[];
  crypto?: InlineImageCrypto;
  now?: () => number;
  createObjectURL?: (file: File) => string;
  revokeObjectURL?: (url: string) => void;
  renameFile?: (file: File, name: string) => File;
}

export function createUuidV4(source?: InlineImageCrypto): string {
  const cryptoSource: InlineImageCrypto | undefined = source ?? globalThis.crypto;
  const randomUUID = cryptoSource?.randomUUID;
  if (typeof randomUUID === "function") {
    return randomUUID.call(cryptoSource);
  }
  if (!cryptoSource || typeof cryptoSource.getRandomValues !== "function") {
    throw new Error("crypto.getRandomValues is unavailable");
  }
  const bytes = cryptoSource.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function resolveInlineImageType(
  file: Pick<File, "type" | "name">
): "image/png" | "image/jpeg" | null {
  const type = file.type.toLowerCase();
  if (type === "image/png" || type === "image/jpeg") {
    return type;
  }
  if (type === "image/jpg" || type === "image/pjpeg") {
    return "image/jpeg";
  }
  if (type === "image/x-png") {
    return "image/png";
  }
  if (type !== "" && type !== "application/octet-stream") {
    return null;
  }
  const dotIndex = file.name.lastIndexOf(".");
  if (dotIndex < 0) {
    return null;
  }
  const extension = file.name.slice(dotIndex + 1).toLowerCase();
  if (extension === "png") {
    return "image/png";
  }
  if (extension === "jpg" || extension === "jpeg") {
    return "image/jpeg";
  }
  return null;
}

export function readClipboardImageFiles(
  clipboardData: Pick<DataTransfer, "items"> | null
): ClipboardImageFiles {
  const files: File[] = [];
  const unsupportedTypes: string[] = [];
  for (const item of Array.from(clipboardData?.items ?? [])) {
    if (item.kind !== "file") {
      continue;
    }
    const file = item.getAsFile();
    const type = item.type || file?.type || "";
    if (!type.startsWith("image/")) {
      continue;
    }
    if (file && SUPPORTED_INLINE_IMAGE_TYPES.has(type)) {
      files.push(file);
    } else {
      unsupportedTypes.push(type);
    }
  }
  return { hasImage: files.length > 0 || unsupportedTypes.length > 0, files, unsupportedTypes };
}

export function buildInlineImageManifest(
  inlineImages: readonly PostInlineImageUpload[]
): Array<{ imageKey: string; fileIndex: number }> {
  const seen = new Set<string>();
  return inlineImages.map((inlineImage, fileIndex) => {
    if (seen.has(inlineImage.imageKey)) {
      throw new Error("duplicate inline image key");
    }
    seen.add(inlineImage.imageKey);
    return { imageKey: inlineImage.imageKey, fileIndex };
  });
}

export function useInlineImageDraft(options: InlineImageDraftOptions): {
  entries: Readonly<Ref<InlineImageDraftEntry[]>>;
  inlineSources: ComputedRef<Record<string, string>>;
  totalBytes: ComputedRef<number>;
  register(files: readonly File[]): InlineImageDraftRegistrationResult;
  buildUploads(document: unknown, existingImageKeys?: readonly string[]): InlineImageUploadBuildResult;
  clear(): void;
} {
  const confirmUpload = options.confirmUpload;
  const cryptoSource = options.crypto;
  const now = options.now ?? (() => Date.now());
  const createObjectURL = options.createObjectURL ?? ((file: File) => URL.createObjectURL(file));
  const revokeObjectURL = options.revokeObjectURL ?? ((url: string) => URL.revokeObjectURL(url));
  const renameFile = options.renameFile ?? ((file: File, name: string) =>
    new File([file], name, { type: file.type, lastModified: file.lastModified })
  );

  const entries = ref<InlineImageDraftEntry[]>([]);
  const inlineSources = computed<Record<string, string>>(() => {
    const sources: Record<string, string> = {};
    for (const entry of entries.value) {
      sources[entry.imageKey] = entry.objectUrl;
    }
    return sources;
  });
  const totalBytes = computed(() =>
    entries.value.reduce((total, entry) => total + entry.file.size, 0)
  );

  function inlineImageFileName(imageKey: string, file: File): string {
    const extension = resolveInlineImageType(file) === "image/jpeg" ? "jpg" : "png";
    return `inline-image-${now()}-${imageKey.slice(0, 8)}.${extension}`;
  }

  function nextImageKeys(count: number): string[] {
    const taken = new Set(entries.value.map((entry) => entry.imageKey.toLowerCase()));
    for (const reserved of options.reservedImageKeys?.() ?? []) {
      taken.add(String(reserved).toLowerCase());
    }
    const keys: string[] = [];
    while (keys.length < count) {
      const candidate = createUuidV4(cryptoSource);
      if (!taken.has(candidate.toLowerCase())) {
        taken.add(candidate.toLowerCase());
        keys.push(candidate);
      }
    }
    return keys;
  }

  function register(files: readonly File[]): InlineImageDraftRegistrationResult {
    if (files.length === 0) {
      return { entries: [], error: null };
    }
    for (const file of files) {
      if (!resolveInlineImageType(file)) {
        return { entries: [], error: UNSUPPORTED_INLINE_IMAGE_MESSAGE };
      }
      if (file.size > MAX_INLINE_IMAGE_FILE_SIZE) {
        return { entries: [], error: INLINE_IMAGE_FILE_SIZE_MESSAGE };
      }
    }
    if (entries.value.length + files.length > MAX_PENDING_INLINE_IMAGES) {
      return { entries: [], error: INLINE_IMAGE_REGISTRY_COUNT_MESSAGE };
    }
    const addedBytes = files.reduce((total, file) => total + file.size, 0);
    if (totalBytes.value + addedBytes > MAX_PENDING_INLINE_IMAGE_BYTES) {
      return { entries: [], error: INLINE_IMAGE_REGISTRY_BYTES_MESSAGE };
    }
    if (!confirmUpload()) {
      return { entries: [], error: null };
    }
    const keys = nextImageKeys(files.length);
    const created: InlineImageDraftEntry[] = [];
    try {
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index];
        const imageKey = keys[index];
        const renamed = renameFile(file, inlineImageFileName(imageKey, file));
        const objectUrl = createObjectURL(renamed);
        created.push({ imageKey, file: renamed, objectUrl });
      }
    } catch (creationError) {
      for (const entry of created) {
        revokeObjectURL(entry.objectUrl);
      }
      throw creationError;
    }
    entries.value = [...entries.value, ...created];
    return { entries: created, error: null };
  }

  function buildUploads(
    document: unknown,
    existingImageKeys: readonly string[] = []
  ): InlineImageUploadBuildResult {
    const keys = collectInlineImageKeys(document);
    const byKey = new Map(entries.value.map((entry) => [entry.imageKey, entry]));
    const existing = new Set(existingImageKeys);
    const inlineImages: PostInlineImageUpload[] = [];
    for (const imageKey of keys) {
      const entry = byKey.get(imageKey);
      if (entry) {
        inlineImages.push({ imageKey: entry.imageKey, file: entry.file });
        continue;
      }
      if (existing.has(imageKey)) {
        continue;
      }
      return { inlineImages: [], error: MISSING_INLINE_IMAGE_FILE_MESSAGE };
    }
    return { inlineImages, error: null };
  }

  function clear(): void {
    const current = entries.value;
    entries.value = [];
    for (const entry of current) {
      revokeObjectURL(entry.objectUrl);
    }
  }

  return { entries, inlineSources, totalBytes, register, buildUploads, clear };
}
