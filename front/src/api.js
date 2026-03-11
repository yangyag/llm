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

async function requestJson(path, options = {}) {
  const response = await fetch(withApiBase(path), {
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(options.headers ?? {})
    },
    ...options
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

export function getPosts(page = 1) {
  const params = new URLSearchParams({ page: String(page) });
  return requestJson(`/api/v1/posts?${params.toString()}`);
}

export function getPost(postId) {
  return requestJson(`/api/v1/posts/${postId}`);
}

export function createPost({ title, body, password }) {
  return requestJson("/api/v1/posts", {
    method: "POST",
    body: JSON.stringify({
      title,
      bodyBase64: encodeBodyBase64(body),
      password
    })
  });
}

export function updatePost(postId, { title, body, password }) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "PUT",
    body: JSON.stringify({
      title,
      bodyBase64: encodeBodyBase64(body),
      password
    })
  });
}

export function deletePost(postId, password) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "DELETE",
    body: JSON.stringify({ password })
  });
}

export function createReply(postId, { body, password }) {
  return requestJson(`/api/v1/posts/${postId}/replies`, {
    method: "POST",
    body: JSON.stringify({
      bodyBase64: encodeBodyBase64(body),
      password
    })
  });
}

export function createAiReply(postId, provider) {
  return requestJson(`/api/v1/posts/${postId}/ai-replies`, {
    method: "POST",
    body: JSON.stringify({ provider })
  });
}

export function updateReply(replyId, { body, password }) {
  return requestJson(`/api/v1/posts/replies/${replyId}`, {
    method: "PUT",
    body: JSON.stringify({
      bodyBase64: encodeBodyBase64(body),
      password
    })
  });
}

export function deleteReply(replyId, password) {
  return requestJson(`/api/v1/posts/replies/${replyId}`, {
    method: "DELETE",
    body: JSON.stringify({ password })
  });
}
