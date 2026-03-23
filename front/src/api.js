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

function buildPostFormData({ title, body, password, attachment, removeAttachment = false, mode = "NORMAL" }) {
  const formData = new FormData();
  formData.append("title", title);
  formData.append("mode", mode);
  formData.append("bodyBase64", mode === "FILE_CONVERSION_REQUEST" ? body : encodeBodyBase64(body));
  formData.append("password", password);

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
  const response = await fetch(withApiBase(path), {
    headers: {
      Accept: "application/json",
      ...(isFormData ? {} : { "Content-Type": "application/json" }),
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

export function createPost({ title, body, password, attachment, mode = "NORMAL" }) {
  return requestJson("/api/v1/posts", {
    method: "POST",
    body: buildPostFormData({ title, body, password, attachment, mode })
  });
}

export function updatePost(postId, { title, body, password, attachment, removeAttachment = false, mode = "NORMAL" }) {
  return requestJson(`/api/v1/posts/${postId}`, {
    method: "PUT",
    body: buildPostFormData({ title, body, password, attachment, removeAttachment, mode })
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

export function getApiUrl(path) {
  return withApiBase(path);
}

export function convertPostToAttachment(postId) {
  return requestJson(`/api/v1/posts/${postId}/conversion`, {
    method: "POST"
  });
}
