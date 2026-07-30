// API 클라이언트 — Nuxt $fetch 기반의 게시판 API 계약 구현.
// Nuxt $fetch 사용. 에러 정규화(ApiError) + 인증 요청 401 시 auth:unauthorized 디스패치 보존.
import { fromUint8Array } from "js-base64";
import type {
  AiProvider,
  ApiError,
  LoginResponse,
  MeResponse,
  PostDetail,
  PostListResponse,
  PostMutationInput
} from "~/types/api";

type RequestOptions = {
  method?: "GET" | "HEAD" | "PATCH" | "POST" | "PUT" | "DELETE" | "CONNECT" | "OPTIONS" | "TRACE";
  headers?: Record<string, string>;
  body?: BodyInit | Record<string, unknown> | null;
};

function getApiBase(): string {
  // SPA 모드: runtimeConfig.public.apiBase. 빈값(운영) → 상대경로 /api.
  const config = useRuntimeConfig();
  return (config.public.apiBase ?? "").replace(/\/+$/, "");
}

function withApiBase(path: string): string {
  const base = getApiBase();
  if (!base) {
    return path;
  }
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  return `${base}${path}`;
}

function encodeBodyBase64(value: string): string {
  return fromUint8Array(new TextEncoder().encode(value));
}

function buildPostFormData({ title, body, attachments = [], removeAttachmentIds = [] }: PostMutationInput): FormData {
  const formData = new FormData();
  formData.append("title", title);
  formData.append("bodyBase64", encodeBodyBase64(body));

  for (const attachment of attachments) {
    if (attachment) {
      formData.append("attachments", attachment);
    }
  }

  for (const attachmentId of removeAttachmentIds ?? []) {
    formData.append("removeAttachmentIds", String(attachmentId));
  }

  return formData;
}

async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const isFormData = typeof FormData !== "undefined" && options.body instanceof FormData;
  const { headers: optionHeaders, ...restOptions } = options;

  try {
    return await $fetch<T>(withApiBase(path), {
      headers: {
        Accept: "application/json",
        ...(isFormData ? {} : { "Content-Type": "application/json" }),
        ...(optionHeaders ?? {})
      },
      ...restOptions
    });
  } catch (err) {
    const errorResponse = err as { response?: { status?: number; _data?: { code?: string; message?: string } | null } };
    const status = errorResponse.response?.status ?? 0;
    const payload = errorResponse.response?._data ?? null;

    // 인증 요청(Authorization 헤더 포함)이 401이면 세션 만료로 보고 전역 로그아웃 신호.
    // 로그인 요청은 Authorization 헤더가 없으므로(잘못된 자격증명 401) 여기서 제외된다.
    const sentAuthHeader = Boolean(optionHeaders && (optionHeaders.Authorization ?? optionHeaders.authorization));
    if (status === 401 && sentAuthHeader && typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent("auth:unauthorized"));
    }

    const error: ApiError = {
      code: payload?.code ?? null,
      status,
      message: payload?.message ?? `Request failed: ${status}`
    };
    throw error;
  }
}

export function getPosts(page = 1, query = ""): Promise<PostListResponse> {
  const params = new URLSearchParams({ page: String(page) });
  const normalizedQuery = query.trim();
  if (normalizedQuery) {
    params.set("query", normalizedQuery);
  }
  return requestJson<PostListResponse>(`/api/v1/posts?${params.toString()}`);
}

export function getPost(postId: number): Promise<PostDetail> {
  return requestJson<PostDetail>(`/api/v1/posts/${postId}`);
}

export function createPost(input: PostMutationInput, token: string): Promise<PostDetail> {
  return requestJson<PostDetail>("/api/v1/posts", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData(input)
  });
}

export function updatePost(postId: number, input: PostMutationInput, token: string): Promise<PostDetail> {
  return requestJson<PostDetail>(`/api/v1/posts/${postId}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData(input)
  });
}

export function deletePost(postId: number, token: string): Promise<void> {
  return requestJson<void>(`/api/v1/posts/${postId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function createReply(postId: number, body: string, token: string): Promise<PostDetail> {
  return requestJson<PostDetail>(`/api/v1/posts/${postId}/replies`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ bodyBase64: encodeBodyBase64(body) })
  });
}

export function createAiReply(postId: number, provider: AiProvider, token: string): Promise<PostDetail> {
  return requestJson<PostDetail>(`/api/v1/posts/${postId}/ai-replies`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ provider })
  });
}

export function updateReply(replyId: number, body: string, token: string): Promise<PostDetail> {
  return requestJson<PostDetail>(`/api/v1/posts/replies/${replyId}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ bodyBase64: encodeBodyBase64(body) })
  });
}

export function deleteReply(replyId: number, token: string): Promise<void> {
  return requestJson<void>(`/api/v1/posts/replies/${replyId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function getApiUrl(path: string): string {
  return withApiBase(path);
}

export function login(username: string, password: string): Promise<LoginResponse> {
  return requestJson<LoginResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export function getMe(token: string): Promise<MeResponse> {
  return requestJson<MeResponse>("/api/v1/auth/me", {
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function batchDeletePosts(ids: number[], token: string): Promise<void> {
  return requestJson<void>("/api/v1/posts/batch-delete", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ ids })
  });
}
