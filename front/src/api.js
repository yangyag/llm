import { fromUint8Array } from "js-base64";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

function withApiBase(path) {
  if (!API_BASE_URL) {
    return path;
  }

  if (/^https?:\/\//i.test(path)) {
    return path;
  }

  return `${API_BASE_URL}${path}`;
}

function encodeBodyBase64(value) {
  return fromUint8Array(new TextEncoder().encode(value));
}

function buildPostFormData({ title, body, attachments = [], removeAttachmentIds = [] }) {
  const formData = new FormData();
  formData.append("title", title);
  formData.append("bodyBase64", encodeBodyBase64(body));

  for (const attachment of attachments) {
    if (attachment) {
      formData.append("attachments", attachment);
    }
  }

  for (const attachmentId of removeAttachmentIds) {
    formData.append("removeAttachmentIds", String(attachmentId));
  }

  return formData;
}

async function requestJson(path, options = {}) {
  const isFormData = options.body instanceof FormData;
  const { headers: optionHeaders, ...restOptions } = options;
  const response = await fetch(withApiBase(path), {
    headers: {
      Accept: "application/json",
      ...(isFormData ? {} : { "Content-Type": "application/json" }),
      ...(optionHeaders ?? {})
    },
    ...restOptions
  });

  if (!response.ok) {
    // 인증 요청(Authorization 헤더 포함)이 401이면 세션 만료로 보고 전역 로그아웃 신호를 보낸다.
    // 로그인 요청은 Authorization 헤더가 없으므로(잘못된 자격증명 401) 여기서 제외된다.
    const sentAuthHeader = Boolean(optionHeaders && (optionHeaders.Authorization ?? optionHeaders.authorization));
    if (response.status === 401 && sentAuthHeader && typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent("auth:unauthorized"));
    }
    const payload = await response.json().catch(() => null);
    const error = new Error(payload?.message ?? `Request failed: ${response.status}`);
    error.code = payload?.code ?? null;
    error.status = response.status;
    throw error;
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export function getPosts(page = 1, query = "") {
  const params = new URLSearchParams({ page: String(page) });
  const normalizedQuery = query.trim();
  if (normalizedQuery) {
    params.set("query", normalizedQuery);
  }
  return requestJson(`/api/v1/posts?${params.toString()}`);
}

export function getPost(postId) {
  return requestJson(`/api/v1/posts/${postId}`);
}

export function createPost({ title, body, attachments = [] }, token) {
  return requestJson("/api/v1/posts", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData({ title, body, attachments })
  });
}

export function updatePost(postId, { title, body, attachments = [], removeAttachmentIds = [] }, token) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData({ title, body, attachments, removeAttachmentIds })
  });
}

export function deletePost(postId, token) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function createReply(postId, { body }, token) {
  return requestJson(`/api/v1/posts/${postId}/replies`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      bodyBase64: encodeBodyBase64(body)
    })
  });
}

export function createAiReply(postId, provider, token) {
  return requestJson(`/api/v1/posts/${postId}/ai-replies`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ provider })
  });
}

export function updateReply(replyId, { body }, token) {
  return requestJson(`/api/v1/posts/replies/${replyId}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      bodyBase64: encodeBodyBase64(body)
    })
  });
}

export function deleteReply(replyId, token) {
  return requestJson(`/api/v1/posts/replies/${replyId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function getApiUrl(path) {
  return withApiBase(path);
}

export function login(username, password) {
  return requestJson("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export function getMe(token) {
  return requestJson("/api/v1/auth/me", {
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function batchDeletePosts(ids, token) {
  return requestJson("/api/v1/posts/batch-delete", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: JSON.stringify({ ids })
  });
}
