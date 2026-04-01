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

function buildPostFormData({ title, body, attachment, removeAttachment = false, mode = "NORMAL" }) {
  const formData = new FormData();
  formData.append("title", title);
  formData.append("mode", mode);
  formData.append("bodyBase64", mode === "FILE_CONVERSION_REQUEST" ? body : encodeBodyBase64(body));

  if (attachment && mode !== "FILE_CONVERSION_REQUEST") {
    formData.append("attachment", attachment);
  }

  if (removeAttachment) {
    formData.append("removeAttachment", "true");
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

export function createPost({ title, body, attachment, mode = "NORMAL" }, token) {
  return requestJson("/api/v1/posts", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData({ title, body, attachment, mode })
  });
}

export function updatePost(postId, { title, body, attachment, removeAttachment = false, mode = "NORMAL" }, token) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}` },
    body: buildPostFormData({ title, body, attachment, removeAttachment, mode })
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

export function convertPostToAttachment(postId, token) {
  return requestJson(`/api/v1/posts/${postId}/conversion`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` }
  });
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
