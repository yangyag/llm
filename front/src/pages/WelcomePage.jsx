import { useEffect, useState } from "react";
import {
  batchDeletePosts,
  createAiReply,
  createPost,
  createReply,
  deletePost,
  deleteReply,
  getApiUrl,
  getPost,
  getPosts,
  updatePost,
  updateReply
} from "../api";

const EMPTY_POST_FORM = {
  title: "",
  body: ""
};

const EMPTY_REPLY_FORM = {
  body: ""
};

const DEFAULT_PAGINATION = {
  page: 1,
  pageSize: 10,
  totalItems: 0,
  totalPages: 0,
  hasPrevious: false,
  hasNext: false
};

const ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE = "첨부파일을 올려도 되는 환경입니까?";

const MAX_ATTACHMENTS = 5;

function attachmentFileKey(file) {
  return `${file.name}::${file.size}::${file.lastModified}`;
}

// 기존 선택 목록에 새로 고른 파일을 합치되, 중복(이름/크기/수정시각 동일)은 제거하고 최대 개수로 제한한다.
function mergeAttachmentFiles(existingFiles, incomingFiles, max = MAX_ATTACHMENTS) {
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

function normalizeSearchQuery(value) {
  return value.trim();
}

function getListStateFromLocation() {
  const params = new URLSearchParams(window.location.search);
  const pageValue = Number.parseInt(params.get("page") ?? "1", 10);
  return {
    page: Number.isFinite(pageValue) && pageValue > 0 ? pageValue : 1,
    query: normalizeSearchQuery(params.get("query") ?? "")
  };
}

function updateListStateInUrl(page, query, { replace = false } = {}) {
  const nextPage = Math.max(page, 1);
  const normalizedQuery = normalizeSearchQuery(query);
  const url = new URL(window.location.href);
  url.searchParams.set("page", String(nextPage));
  if (normalizedQuery) {
    url.searchParams.set("query", normalizedQuery);
  } else {
    url.searchParams.delete("query");
  }
  const method = replace ? "replaceState" : "pushState";
  window.history[method]({}, "", `${url.pathname}${url.search}${url.hash}`);
}

function isFileConversionMode(mode) {
  return mode === "FILE_CONVERSION_REQUEST";
}

function getPostModeLabel(mode) {
  return isFileConversionMode(mode) ? "암호화 업로드" : "일반";
}

function getPostBodyLabel() {
  return "본문";
}

function getPostBodyHelp() {
  return `본문은 비워둘 수 있습니다. 첨부파일은 최대 ${MAX_ATTACHMENTS}개, 파일당 100MB까지 업로드할 수 있습니다.`;
}

function formatFileSize(size) {
  if (!Number.isFinite(size) || size < 1024) {
    return `${size ?? 0}B`;
  }

  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)}KB`;
  }

  return `${(size / (1024 * 1024)).toFixed(1)}MB`;
}

function WelcomePage({ authToken, authUsername, onLogout }) {
  const createPostFormId = "create-post-form";
  const initialListState = getListStateFromLocation();
  const [view, setView] = useState("list");
  const [posts, setPosts] = useState([]);
  const [currentPage, setCurrentPage] = useState(initialListState.page);
  const [searchQuery, setSearchQuery] = useState(initialListState.query);
  const [searchInput, setSearchInput] = useState(initialListState.query);
  const [pagination, setPagination] = useState(DEFAULT_PAGINATION);
  const [selectedPostId, setSelectedPostId] = useState(null);
  const [selectedPost, setSelectedPost] = useState(null);
  const [postActionMode, setPostActionMode] = useState("none");
  const [postForm, setPostForm] = useState(EMPTY_POST_FORM);
  const [postAttachmentFiles, setPostAttachmentFiles] = useState([]);
  const [postAttachmentConfirmed, setPostAttachmentConfirmed] = useState(false);
  const [postAttachmentInputKey, setPostAttachmentInputKey] = useState(0);
  const [replyForm, setReplyForm] = useState(EMPTY_REPLY_FORM);
  const [selectedAiProvider, setSelectedAiProvider] = useState("GPT");
  const [postEditForm, setPostEditForm] = useState(EMPTY_POST_FORM);
  const [postEditAttachmentFiles, setPostEditAttachmentFiles] = useState([]);
  const [postEditAttachmentConfirmed, setPostEditAttachmentConfirmed] = useState(false);
  const [postEditAttachmentInputKey, setPostEditAttachmentInputKey] = useState(0);
  const [removeAttachmentIds, setRemoveAttachmentIds] = useState(new Set());
  const [replyEditState, setReplyEditState] = useState({ replyId: null, body: "" });
  const [postActionError, setPostActionError] = useState("");
  const [replyActionError, setReplyActionError] = useState("");
  const [aiReplyError, setAiReplyError] = useState("");
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [aiSubmitting, setAiSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [selectedPostIds, setSelectedPostIds] = useState(new Set());
  const [postLinkCopied, setPostLinkCopied] = useState(false);

  useEffect(() => {
    if (!postLinkCopied) {
      return undefined;
    }
    const timeoutId = window.setTimeout(() => {
      setPostLinkCopied(false);
    }, 2000);
    return () => window.clearTimeout(timeoutId);
  }, [postLinkCopied]);

  useEffect(() => {
    function handlePopState() {
      const nextListState = getListStateFromLocation();
      setCurrentPage(nextListState.page);
      setSearchQuery(nextListState.query);
      setSearchInput(nextListState.query);
      setView("list");
    }

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (view === "list") {
      loadPosts(currentPage, searchQuery);
    }
  }, [currentPage, searchQuery]);

  useEffect(() => {
    if (view === "detail" && selectedPostId != null) {
      loadPostDetail(selectedPostId);
    }
  }, [view, selectedPostId]);

  async function loadPosts(page = currentPage, query = searchQuery) {
    setLoading(true);
    setSelectedPostIds(new Set());
    setError("");

    try {
      const normalizedQuery = normalizeSearchQuery(query);
      const payload = await getPosts(page, normalizedQuery);
      if ((payload.totalPages ?? 0) > 0 && page > payload.totalPages) {
        navigateToList(payload.totalPages, normalizedQuery, { replace: true });
        return loadPosts(payload.totalPages, normalizedQuery);
      }
      if ((payload.totalPages ?? 0) === 0 && page > 1) {
        navigateToList(1, normalizedQuery, { replace: true });
        return loadPosts(1, normalizedQuery);
      }

      setPosts(payload.items ?? []);
      setPagination({
        page: payload.page ?? page,
        pageSize: payload.pageSize ?? DEFAULT_PAGINATION.pageSize,
        totalItems: payload.totalItems ?? 0,
        totalPages: payload.totalPages ?? 0,
        hasPrevious: payload.hasPrevious ?? false,
        hasNext: payload.hasNext ?? false
      });
      setCurrentPage(payload.page ?? page);
      setSearchQuery(normalizedQuery);
      return payload;
    } catch (loadError) {
      setError(loadError.message);
      return null;
    } finally {
      setLoading(false);
    }
  }

  function navigateToList(page, query = searchQuery, options = {}) {
    const nextPage = Math.max(page, 1);
    const normalizedQuery = normalizeSearchQuery(query);
    updateListStateInUrl(nextPage, normalizedQuery, options);
    setCurrentPage(nextPage);
    setSearchQuery(normalizedQuery);
  }

  function handleSearchSubmit(event) {
    event.preventDefault();
    const nextQuery = normalizeSearchQuery(searchInput);
    setSearchInput(nextQuery);
    navigateToList(1, nextQuery);
  }

  function handleSearchReset() {
    setSearchInput("");
    navigateToList(1, "");
  }

  async function loadPostDetail(postId) {
    setDetailLoading(true);
    setError("");

    try {
      const payload = await getPost(postId);
      setSelectedPost(payload);
      setPostEditForm({
        title: payload.title,
        body: payload.body
      });
      setPostEditAttachmentFiles([]);
      setPostEditAttachmentConfirmed(false);
      setPostEditAttachmentInputKey((prev) => prev + 1);
      setRemoveAttachmentIds(new Set());
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setDetailLoading(false);
    }
  }

  function resetListViewState() {
    setView("list");
    setSelectedPostId(null);
    setSelectedPost(null);
    setPostActionMode("none");
    setPostActionError("");
    setReplyActionError("");
    setAiReplyError("");
    setMessage("");
    setError("");
  }

  async function refreshListView() {
    resetListViewState();
    await loadPosts(currentPage, searchQuery);
  }

  function openWrite() {
    setView("write");
    setPostForm(EMPTY_POST_FORM);
    setPostAttachmentFiles([]);
    setPostAttachmentConfirmed(false);
    setPostAttachmentInputKey((prev) => prev + 1);
    setPostActionMode("none");
    setPostActionError("");
    setReplyActionError("");
    setAiReplyError("");
    setMessage("");
    setError("");
  }

  function openDetail(postId) {
    setSelectedPostId(postId);
    setView("detail");
    setPostActionMode("none");
    setReplyForm(EMPTY_REPLY_FORM);
    setReplyEditState({ replyId: null, body: "" });
    setPostEditAttachmentFiles([]);
    setPostEditAttachmentConfirmed(false);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemoveAttachmentIds(new Set());
    setPostActionError("");
    setReplyActionError("");
    setAiReplyError("");
    setSelectedAiProvider("GPT");
    setMessage("");
    setError("");
  }

  function openPostEditPanel() {
    if (!selectedPost || selectedPost.conversionReady || isFileConversionMode(selectedPost.mode)) {
      return;
    }

    setPostEditForm({
      title: selectedPost.title,
      body: selectedPost.body
    });
    setPostEditAttachmentFiles([]);
    setPostEditAttachmentConfirmed(false);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemoveAttachmentIds(new Set());
    setPostActionError("");
    setPostActionMode("edit");
  }

  function closePostActionPanel() {
    setPostActionMode("none");
    setPostActionError("");
    setPostEditAttachmentFiles([]);
    setPostEditAttachmentConfirmed(false);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemoveAttachmentIds(new Set());
  }

  function confirmAttachmentUploadEnvironment() {
    return window.confirm(ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE);
  }

  function handleCreateAttachmentChange(event) {
    const selectedFiles = Array.from(event.target.files ?? []);
    // 입력을 비워 같은 파일을 다시 골라도 onChange가 발생하고, 누적은 우리 상태가 관리하게 한다.
    setPostAttachmentInputKey((prev) => prev + 1);
    if (selectedFiles.length === 0) {
      return;
    }

    if (!confirmAttachmentUploadEnvironment()) {
      return;
    }

    setError("");
    const { files, truncated } = mergeAttachmentFiles(postAttachmentFiles, selectedFiles);
    setPostAttachmentFiles(files);
    setPostAttachmentConfirmed(true);
    if (truncated) {
      setError(`첨부파일은 최대 ${MAX_ATTACHMENTS}개까지만 첨부할 수 있습니다.`);
    }
  }

  function removeCreateAttachment(targetKey) {
    setPostAttachmentFiles((prev) => prev.filter((file) => attachmentFileKey(file) !== targetKey));
  }

  function handleEditAttachmentChange(event) {
    const selectedFiles = Array.from(event.target.files ?? []);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setPostActionError("");
    if (selectedFiles.length === 0) {
      return;
    }

    if (!confirmAttachmentUploadEnvironment()) {
      return;
    }

    const keptExistingCount = (selectedPost?.attachments ?? []).filter(
      (attachment) => !removeAttachmentIds.has(attachment.id)
    ).length;
    const availableSlots = Math.max(MAX_ATTACHMENTS - keptExistingCount, 0);
    const { files, truncated } = mergeAttachmentFiles(postEditAttachmentFiles, selectedFiles, availableSlots);
    setPostEditAttachmentFiles(files);
    setPostEditAttachmentConfirmed(true);
    if (truncated) {
      setPostActionError(`첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지만 등록할 수 있습니다.`);
    }
  }

  function removeEditAttachment(targetKey) {
    setPostEditAttachmentFiles((prev) => prev.filter((file) => attachmentFileKey(file) !== targetKey));
  }

  function toggleRemoveExistingAttachment(attachmentId) {
    setPostActionError("");
    setRemoveAttachmentIds((prev) => {
      const next = new Set(prev);
      if (next.has(attachmentId)) {
        next.delete(attachmentId);
      } else {
        next.add(attachmentId);
      }
      return next;
    });
  }

  function openReplyEditPanel(reply) {
    setReplyActionError("");
    setReplyEditState({
      replyId: reply.id,
      body: reply.body
    });
  }

  function closeReplyEditPanel() {
    setReplyActionError("");
    setReplyEditState({ replyId: null, body: "" });
  }

  async function handleCreatePost(event) {
    event.preventDefault();

    if (postAttachmentFiles.length > 0 && !postAttachmentConfirmed && !confirmAttachmentUploadEnvironment()) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      const created = await createPost({
        ...postForm,
        attachments: postAttachmentFiles
      }, authToken);
      setPostForm(EMPTY_POST_FORM);
      setPostAttachmentFiles([]);
      setPostAttachmentConfirmed(false);
      setPostAttachmentInputKey((prev) => prev + 1);
      navigateToList(1, searchQuery, { replace: true });
      await loadPosts(1);
      openDetail(created.id);
      setMessage("게시글을 등록했습니다.");
    } catch (submitError) {
      if (submitError?.code === "INVALID_ATTACHMENT_REQUEST") {
        setError(`첨부파일 요청이 올바르지 않습니다. 첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지 등록할 수 있습니다.`);
      } else {
        setError(submitError.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleUpdatePost(event) {
    event.preventDefault();
    if (!selectedPostId) {
      return;
    }

    if (postEditAttachmentFiles.length > 0 && !postEditAttachmentConfirmed && !confirmAttachmentUploadEnvironment()) {
      return;
    }

    // 삭제 표시를 해제하는 등으로 (유지 기존 + 신규) 합계가 한도를 넘을 수 있으므로 제출 직전 재검증한다.
    const keptExistingCount = (selectedPost?.attachments ?? []).filter(
      (attachment) => !removeAttachmentIds.has(attachment.id)
    ).length;
    if (keptExistingCount + postEditAttachmentFiles.length > MAX_ATTACHMENTS) {
      setPostActionError(`첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지만 등록할 수 있습니다.`);
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");
    setPostActionError("");

    try {
      const updated = await updatePost(selectedPostId, {
        ...postEditForm,
        attachments: postEditAttachmentFiles,
        removeAttachmentIds: [...removeAttachmentIds]
      }, authToken);
      setSelectedPost(updated);
      setPostActionMode("none");
      setPostActionError("");
      setPostEditAttachmentFiles([]);
      setPostEditAttachmentConfirmed(false);
      setPostEditAttachmentInputKey((prev) => prev + 1);
      setRemoveAttachmentIds(new Set());
      await loadPosts(currentPage);
      setMessage("게시글을 수정했습니다.");
    } catch (submitError) {
      if (submitError?.code === "FILE_CONVERSION_LOCKED") {
        setError("암호화 업로드 완료된 글은 수정할 수 없습니다.");
      } else if (submitError?.code === "INVALID_ATTACHMENT_REQUEST") {
        setError(`첨부파일 요청이 올바르지 않습니다. 첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지 등록할 수 있습니다.`);
      } else {
        setError(submitError.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeletePost() {
    if (!selectedPostId) return;

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      await deletePost(selectedPostId, authToken);
      await loadPosts(currentPage);
      resetListViewState();
      setMessage("게시글을 삭제했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCreateReply(event) {
    event.preventDefault();
    if (!selectedPostId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      const detail = await createReply(selectedPostId, replyForm, authToken);
      setSelectedPost(detail);
      setReplyForm(EMPTY_REPLY_FORM);
      await loadPosts(currentPage);
      setMessage("답변을 등록했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleUpdateReply(event) {
    event.preventDefault();
    if (!replyEditState.replyId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");
    setReplyActionError("");

    try {
      const detail = await updateReply(replyEditState.replyId, {
        body: replyEditState.body
      }, authToken);
      setSelectedPost(detail);
      setReplyActionError("");
      setReplyEditState({ replyId: null, body: "" });
      await loadPosts(currentPage);
      setMessage("답변을 수정했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeleteReply(replyId) {
    if (!replyId) return;
    if (!window.confirm("이 답변을 삭제하시겠습니까?")) return;

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      await deleteReply(replyId, authToken);
      await loadPostDetail(selectedPostId);
      await loadPosts(currentPage);
      setMessage("답변을 삭제했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCreateAiReply() {
    if (!selectedPostId) {
      return;
    }

    setAiSubmitting(true);
    setAiReplyError("");
    setError("");
    setMessage("");

    try {
      const detail = await createAiReply(selectedPostId, selectedAiProvider, authToken);
      setSelectedPost(detail);
      await loadPosts(currentPage);
      setMessage("AI 답변을 등록했습니다.");
    } catch (submitError) {
      setAiReplyError(submitError.message);
    } finally {
      setAiSubmitting(false);
    }
  }

  function togglePostSelection(postId, event) {
    event.stopPropagation();
    setSelectedPostIds((prev) => {
      const next = new Set(prev);
      if (next.has(postId)) {
        next.delete(postId);
      } else {
        next.add(postId);
      }
      return next;
    });
  }

  function toggleSelectAll() {
    if (selectedPostIds.size === posts.length) {
      setSelectedPostIds(new Set());
    } else {
      setSelectedPostIds(new Set(posts.map((p) => p.id)));
    }
  }

  async function handleBatchDelete() {
    if (selectedPostIds.size === 0) return;
    if (!window.confirm(`선택한 ${selectedPostIds.size}개의 게시글을 삭제하시겠습니까?`)) return;

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      await batchDeletePosts([...selectedPostIds], authToken);
      setSelectedPostIds(new Set());
      await loadPosts(currentPage, searchQuery);
      setMessage(`${selectedPostIds.size}개의 게시글을 삭제했습니다.`);
    } catch (batchError) {
      setError(batchError.message);
    } finally {
      setSubmitting(false);
    }
  }

  const pageNumbers = Array.from({ length: pagination.totalPages }, (_, index) => index + 1);

  async function handleCopyPostLink() {
    if (!selectedPost?.id) {
      return;
    }
    const postUrl = `${window.location.origin}/posts/${selectedPost.id}`;
    try {
      await window.navigator.clipboard.writeText(postUrl);
      setPostLinkCopied(true);
    } catch {
      setError("게시글 링크를 클립보드에 복사하지 못했습니다.");
    }
  }


  return (
    <main className="board-page">
      <section className="board-shell">
        <header className="board-header">
          <div>
            <p className="eyebrow">Anonymous Board</p>
            <h1>답변 가능한 게시판</h1>
          </div>
          <div className="board-actions">
            {authUsername ? (
              <span className="auth-username">{authUsername}</span>
            ) : null}
            {onLogout ? (
              <button type="button" className="ghost-button" onClick={onLogout}>
                로그아웃
              </button>
            ) : null}
            <button type="button" className="ghost-button" onClick={refreshListView}>
              목록
            </button>
            {view !== "detail" ? (
              <button
                type={view === "write" ? "submit" : "button"}
                form={view === "write" ? createPostFormId : undefined}
                className={view === "write" ? "submit-button" : "primary-button"}
                onClick={view === "write" ? undefined : openWrite}
                disabled={view === "write" && submitting}
              >
                {view === "write" ? (submitting ? "등록 중..." : "등록") : "글쓰기"}
              </button>
            ) : null}
          </div>
        </header>

        {message ? <p className="message-banner success">{message}</p> : null}
        {error ? <p className="message-banner error">{error}</p> : null}

        {view === "list" ? (
          <>
            <div className="download-link-row">
              <a
                className="download-link"
                href={getApiUrl("/upload_zip_post.zip")}
                download="upload_zip_post.zip"
              >
                * 파일 업로드 프로그램
              </a>
            </div>

            <section className="card">
              <div className="section-heading">
                <div>
                  <h2>게시글 목록</h2>
                  <p className="section-meta">
                    {searchQuery ? `검색 결과 ${pagination.totalItems}개` : `총 ${pagination.totalItems}개`}, 페이지 {pagination.page}
                    {pagination.totalPages > 0 ? ` / ${pagination.totalPages}` : ""}
                  </p>
                </div>
                <button type="button" className="ghost-button" onClick={refreshListView}>
                  새로고침
                </button>
              </div>
              <form className="search-bar" onSubmit={handleSearchSubmit}>
                <label className="field search-field">
                  <span>검색어</span>
                  <input
                    value={searchInput}
                    onChange={(event) => setSearchInput(event.target.value)}
                    placeholder="제목 검색"
                  />
                </label>
                <div className="search-actions">
                  <button type="submit" className="submit-button" disabled={loading}>
                    검색
                  </button>
                  <button
                    type="button"
                    className="ghost-button"
                    onClick={handleSearchReset}
                    disabled={loading || (!searchQuery && !searchInput)}
                  >
                    초기화
                  </button>
                </div>
              </form>
              {authUsername && posts.length > 0 && !loading ? (
                <div className="batch-action-bar">
                  <label className="checkbox-field batch-select-all">
                    <input
                      type="checkbox"
                      checked={posts.length > 0 && selectedPostIds.size === posts.length}
                      onChange={toggleSelectAll}
                    />
                    <span>전체 선택</span>
                  </label>
                  {selectedPostIds.size > 0 ? (
                    <button
                      type="button"
                      className="danger-button"
                      onClick={handleBatchDelete}
                      disabled={submitting}
                    >
                      {submitting ? "삭제 중..." : `선택 삭제 (${selectedPostIds.size})`}
                    </button>
                  ) : null}
                </div>
              ) : null}
              {loading ? (
                <p className="empty-state">불러오는 중...</p>
              ) : posts.length === 0 ? (
                <p className="empty-state">
                  {searchQuery ? "검색 결과가 없습니다. 다른 검색어로 다시 시도해 보세요." : "아직 게시글이 없습니다. 첫 글을 작성해 보세요."}
                </p>
              ) : (
                <div className="post-list">
                  {posts.map((post) => (
                    <div key={post.id} className="post-list-item-row">
                      {authUsername ? (
                        <input
                          type="checkbox"
                          className="post-checkbox"
                          checked={selectedPostIds.has(post.id)}
                          onChange={(event) => togglePostSelection(post.id, event)}
                        />
                      ) : null}
                      <button
                        type="button"
                        className="post-list-item"
                        onClick={() => openDetail(post.id)}
                      >
                        <div className="post-title-row">
                          <strong>{post.title}</strong>
                          {!isFileConversionMode(post.mode) ? (
                            <span className="post-mode-badge">{getPostModeLabel(post.mode)}</span>
                          ) : null}
                          {post.conversionReady ? <span className="post-mode-badge success">암호화 업로드 완료</span> : null}
                          {post.hasAttachment ? <span className="attachment-badge">첨부</span> : null}
                        </div>
                        <span>답변 {post.replyCount}개</span>
                        <time>{new Date(post.createdAt).toLocaleString()}</time>
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {posts.length > 0 ? (
                <div className="pagination">
                  <button
                    type="button"
                    className="ghost-button pagination-button"
                    onClick={() => navigateToList(1)}
                    disabled={!pagination.hasPrevious}
                  >
                    처음
                  </button>

                  <button
                    type="button"
                    className="ghost-button pagination-button"
                    onClick={() => navigateToList(currentPage - 1)}
                    disabled={!pagination.hasPrevious}
                  >
                    이전
                  </button>

                  <div className="pagination-numbers">
                    {pageNumbers.map((pageNumber) => (
                      <button
                        key={pageNumber}
                        type="button"
                        className={`ghost-button pagination-button${pageNumber === currentPage ? " active" : ""}`}
                        onClick={() => navigateToList(pageNumber)}
                      >
                        {pageNumber}
                      </button>
                    ))}
                  </div>

                  <button
                    type="button"
                    className="ghost-button pagination-button"
                    onClick={() => navigateToList(currentPage + 1)}
                    disabled={!pagination.hasNext}
                  >
                    다음
                  </button>

                  <button
                    type="button"
                    className="ghost-button pagination-button"
                    onClick={() => navigateToList(pagination.totalPages)}
                    disabled={!pagination.hasNext}
                  >
                    끝
                  </button>
                </div>
              ) : null}
            </section>
          </>
        ) : null}

        {view === "write" ? (
          <section className="card">
            <div className="section-heading">
              <h2>새 글 작성</h2>
            </div>
            <form id={createPostFormId} className="form-grid" onSubmit={handleCreatePost}>
              <label className="field">
                <span>제목</span>
                <input
                  value={postForm.title}
                  onChange={(event) => setPostForm((prev) => ({ ...prev, title: event.target.value }))}
                  maxLength={200}
                  required
                />
              </label>
              <label className="field">
                <span>{getPostBodyLabel()}</span>
                <textarea
                  value={postForm.body}
                  onChange={(event) => setPostForm((prev) => ({ ...prev, body: event.target.value }))}
                  rows={12}
                />
              </label>
              <p className="section-meta">{getPostBodyHelp()}</p>
              <>
                <label className="field">
                  <span>첨부파일</span>
                  <input
                    key={postAttachmentInputKey}
                    type="file"
                    multiple
                    onChange={handleCreateAttachmentChange}
                  />
                </label>
                <p className="section-meta">
                  첨부파일은 최대 {MAX_ATTACHMENTS}개, 파일당 100MB까지 업로드할 수 있습니다.
                  {` (선택: ${postAttachmentFiles.length}/${MAX_ATTACHMENTS})`}
                </p>
                {postAttachmentFiles.length > 0 ? (
                  <ul className="attachment-select-list">
                    {postAttachmentFiles.map((file) => (
                      <li key={attachmentFileKey(file)} className="attachment-select-item">
                        <span>{file.name} ({formatFileSize(file.size)})</span>
                        <button
                          type="button"
                          className="ghost-button"
                          onClick={() => removeCreateAttachment(attachmentFileKey(file))}
                        >
                          제거
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </>
            </form>
          </section>
        ) : null}

        {view === "detail" ? (
          <section className="card">
            <div className="section-heading">
              <h2>게시글 상세</h2>
              {selectedPost ? <span>답변 {selectedPost.replies.length}개</span> : null}
            </div>

            {detailLoading || !selectedPost ? (
              <p className="empty-state">불러오는 중...</p>
            ) : (
              <>
                <article className="detail-panel">
                  <div className="detail-top">
                    <div>
                      <h3>{selectedPost.title}</h3>
                      <div className="detail-meta-row">
                        <span className={`post-mode-badge${isFileConversionMode(selectedPost.mode) ? " file" : ""}`}>
                          {getPostModeLabel(selectedPost.mode)}
                        </span>
                        {selectedPost.conversionReady ? <span className="post-mode-badge success">암호화 업로드 완료</span> : null}
                      </div>
                      <time>{new Date(selectedPost.createdAt).toLocaleString()}</time>
                    </div>
                    <div className="inline-actions">
                      <button type="button" className="ghost-button" onClick={handleCopyPostLink}>
                        {postLinkCopied ? "복사됨!" : "링크 복사"}
                      </button>
                      {!selectedPost.conversionReady ? (
                        <button type="button" className="ghost-button" onClick={openPostEditPanel}>
                          수정
                        </button>
                      ) : null}
                      <button
                        type="button"
                        className="danger-button"
                        onClick={async () => {
                          if (!window.confirm('이 게시글을 삭제하시겠습니까?')) return;
                          await handleDeletePost();
                        }}
                      >
                        삭제
                      </button>
                    </div>
                  </div>
                  {isFileConversionMode(selectedPost.mode) ? (
                    <div className="conversion-summary-card">
                      <div className="conversion-summary-header">
                        <strong>Base64 본문 숨김</strong>
                        <span className={`post-mode-badge${selectedPost.conversionReady ? " success" : " file"}`}>
                          {selectedPost.conversionReady ? "암호화 업로드 완료" : "업로드 대기"}
                        </span>
                      </div>
                      <p className="section-meta">
                        암호화 업로드 글의 raw Base64 본문은 상세 화면에서 숨겨집니다.
                      </p>
                      <div className="conversion-summary-stats">
                        <span>본문 길이 {selectedPost.body.length.toLocaleString()}자</span>
                        <span>
                          {selectedPost.conversionReady
                            ? "암호화 업로드가 완료되어 복원 파일을 다운로드할 수 있습니다."
                            : "암호화 업로드가 완료되면 복원 파일을 다운로드할 수 있습니다."}
                        </span>
                      </div>
                    </div>
                  ) : (
                    <p className="detail-body">{selectedPost.body}</p>
                  )}
                  {selectedPost.attachments?.length > 0 ? (
                    <div className="attachment-panel">
                      <span className="attachment-label">
                        {isFileConversionMode(selectedPost.mode)
                          ? "복원 파일"
                          : `첨부파일 (${selectedPost.attachments.length})`}
                      </span>
                      {selectedPost.attachments.map((attachment) => (
                        <div key={attachment.id} className="attachment-card">
                          <div>
                            <strong>{attachment.originalFilename}</strong>
                            <p className="section-meta">
                              {formatFileSize(attachment.size)}
                              {attachment.contentType ? ` · ${attachment.contentType}` : ""}
                            </p>
                          </div>
                          <a
                            className="ghost-button attachment-link"
                            href={getApiUrl(attachment.downloadUrl)}
                            download={attachment.originalFilename}
                          >
                            다운로드
                          </a>
                        </div>
                      ))}
                    </div>
                  ) : null}
                  {postActionMode === "edit" ? (
                    <form className="form-grid compact-form action-panel" onSubmit={handleUpdatePost}>
                      <label className="field">
                        <span>제목</span>
                        <input
                          value={postEditForm.title}
                          onChange={(event) => setPostEditForm((prev) => ({ ...prev, title: event.target.value }))}
                          maxLength={200}
                          required
                        />
                      </label>
                      <label className="field">
                        <span>{getPostBodyLabel()}</span>
                        <textarea
                          value={postEditForm.body}
                          onChange={(event) => setPostEditForm((prev) => ({ ...prev, body: event.target.value }))}
                          rows={8}
                        />
                      </label>
                      <p className="section-meta">{getPostBodyHelp()}</p>
                      {selectedPost.attachments?.length > 0 ? (
                        <div className="attachment-panel">
                          <span className="attachment-label">현재 첨부파일 ({selectedPost.attachments.length})</span>
                          {selectedPost.attachments.map((attachment) => (
                            <div key={attachment.id} className="attachment-card">
                              <div>
                                <strong className={removeAttachmentIds.has(attachment.id) ? "attachment-marked-remove" : undefined}>
                                  {attachment.originalFilename}
                                </strong>
                                <p className="section-meta">{formatFileSize(attachment.size)}</p>
                              </div>
                              <div className="inline-actions">
                                <a
                                  className="ghost-button attachment-link"
                                  href={getApiUrl(attachment.downloadUrl)}
                                  download={attachment.originalFilename}
                                >
                                  다운로드
                                </a>
                                <label className="checkbox-field">
                                  <input
                                    type="checkbox"
                                    checked={removeAttachmentIds.has(attachment.id)}
                                    onChange={() => toggleRemoveExistingAttachment(attachment.id)}
                                  />
                                  <span>삭제</span>
                                </label>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : null}
                      <>
                        <label className="field">
                          <span>첨부파일 추가</span>
                          <input
                            key={postEditAttachmentInputKey}
                            type="file"
                            multiple
                            onChange={handleEditAttachmentChange}
                          />
                        </label>
                        <p className="section-meta">
                          {`최대 ${MAX_ATTACHMENTS}개까지 등록할 수 있습니다. (현재 ${
                            (selectedPost.attachments ?? []).filter((a) => !removeAttachmentIds.has(a.id)).length
                            + postEditAttachmentFiles.length
                          }/${MAX_ATTACHMENTS})`}
                        </p>
                        {postEditAttachmentFiles.length > 0 ? (
                          <ul className="attachment-select-list">
                            {postEditAttachmentFiles.map((file) => (
                              <li key={attachmentFileKey(file)} className="attachment-select-item">
                                <span>새 파일: {file.name} ({formatFileSize(file.size)})</span>
                                <button
                                  type="button"
                                  className="ghost-button"
                                  onClick={() => removeEditAttachment(attachmentFileKey(file))}
                                >
                                  제거
                                </button>
                              </li>
                            ))}
                          </ul>
                        ) : null}
                      </>
                      {postActionError ? <p className="panel-error">{postActionError}</p> : null}
                      <div className="action-form-actions">
                        <button
                          type="submit"
                          className="ghost-button"
                          disabled={submitting}
                        >
                          게시글 수정
                        </button>
                        <button type="button" className="ghost-button" onClick={closePostActionPanel}>
                          취소
                        </button>
                      </div>
                    </form>
                  ) : null}
                </article>

                <section className="reply-section">
                  <div className="section-heading">
                    <h3>답변</h3>
                  </div>

                  <div className="split-layout">
                    <form className="card inset-card form-grid" onSubmit={handleCreateReply}>
                      <label className="field">
                        <span>답변 본문</span>
                        <textarea
                          value={replyForm.body}
                          onChange={(event) => setReplyForm((prev) => ({ ...prev, body: event.target.value }))}
                          rows={6}
                          required
                        />
                      </label>
                      <button type="submit" className="primary-button wide-button" disabled={submitting || aiSubmitting}>
                        답변 등록
                      </button>
                    </form>

                    {!isFileConversionMode(selectedPost.mode) ? (
                      <div className="card inset-card form-grid ai-reply-card">
                        <div className="section-heading">
                          <h3>AI가 답변달기</h3>
                        </div>
                        <p className="section-meta">현재 게시글 본문을 기준으로 AI 답변을 생성합니다.</p>
                        <div className="provider-options" role="radiogroup" aria-label="AI provider">
                          {[
                            { id: "GPT", label: "GPT (gpt-5.5)" },
                            { id: "Claude", label: "Claude (claude-opus-4-7)" },
                            { id: "Grok", label: "Grok (grok-4.3)" }
                          ].map(({ id, label }) => (
                            <label key={id} className="provider-option">
                              <input
                                type="radio"
                                name="ai-provider"
                                value={id}
                                checked={selectedAiProvider === id}
                                onChange={(event) => {
                                  setAiReplyError("");
                                  setSelectedAiProvider(event.target.value);
                                }}
                              />
                              <span>{label}</span>
                            </label>
                          ))}
                        </div>
                        {aiReplyError ? <p className="panel-error">{aiReplyError}</p> : null}
                        <button
                          type="button"
                          className="ghost-button wide-button"
                          onClick={handleCreateAiReply}
                          disabled={aiSubmitting || submitting}
                        >
                          {aiSubmitting ? "AI 답변 생성 중..." : "AI가 답변달기"}
                        </button>
                      </div>
                    ) : null}
                  </div>

                  {selectedPost.replies.length === 0 ? (
                    <p className="empty-state">아직 답변이 없습니다.</p>
                  ) : (
                    <div className="reply-list">
                      {selectedPost.replies.map((reply) => (
                        <article key={reply.id} className="card inset-card reply-card">
                          <div className="reply-top">
                            <div className="reply-heading">
                              <strong>답변 #{reply.id}</strong>
                              {reply.ai ? <span className="ai-badge">AI · {reply.aiProvider}</span> : null}
                            </div>
                            <time>{new Date(reply.createdAt).toLocaleString()}</time>
                          </div>
                          <p className="detail-body">{reply.body}</p>

                          {!reply.ai ? (
                            <div className="inline-actions">
                              <button
                                type="button"
                                className="ghost-button"
                                onClick={() => openReplyEditPanel(reply)}
                              >
                                수정
                              </button>
                              <button
                                type="button"
                                className="danger-button"
                                onClick={() => handleDeleteReply(reply.id)}
                                disabled={submitting}
                              >
                                삭제
                              </button>
                            </div>
                          ) : null}

                          {!reply.ai && replyEditState.replyId === reply.id ? (
                            <form className="form-grid compact-form action-panel" onSubmit={handleUpdateReply}>
                              <label className="field">
                                <span>수정 본문</span>
                                <textarea
                                  value={replyEditState.body}
                                  onChange={(event) =>
                                    setReplyEditState((prev) => ({ ...prev, body: event.target.value }))
                                  }
                                  rows={4}
                                  required
                                />
                              </label>
                              {replyActionError ? <p className="panel-error">{replyActionError}</p> : null}
                              <div className="action-form-actions">
                                <button type="submit" className="ghost-button" disabled={submitting}>
                                  답변 수정
                                </button>
                                <button type="button" className="ghost-button" onClick={closeReplyEditPanel}>
                                  취소
                                </button>
                              </div>
                            </form>
                          ) : null}
                        </article>
                      ))}
                    </div>
                  )}
                </section>
              </>
            )}
          </section>
        ) : null}
      </section>
    </main>
  );
}

export default WelcomePage;
