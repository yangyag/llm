import { useEffect, useState } from "react";
import {
  convertPostToAttachment,
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

const POST_MODES = {
  NORMAL: "NORMAL",
  FILE_CONVERSION_REQUEST: "FILE_CONVERSION_REQUEST"
};

const POST_MODE_OPTIONS = [
  { value: POST_MODES.NORMAL, label: "일반 게시글 작성" },
  { value: POST_MODES.FILE_CONVERSION_REQUEST, label: "파일 변환 요청" }
];

const EMPTY_POST_FORM = {
  title: "",
  body: "",
  password: "",
  mode: POST_MODES.NORMAL
};

const EMPTY_REPLY_FORM = {
  body: "",
  password: ""
};

const DEFAULT_PAGINATION = {
  page: 1,
  pageSize: 10,
  totalItems: 0,
  totalPages: 0,
  hasPrevious: false,
  hasNext: false
};

function isInvalidPasswordError(error) {
  return error?.code === "INVALID_PASSWORD" || (error?.status === 403 && !error?.code);
}

function getPageFromLocation() {
  const params = new URLSearchParams(window.location.search);
  const value = Number.parseInt(params.get("page") ?? "1", 10);
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function updatePageInUrl(page, { replace = false } = {}) {
  const nextPage = Math.max(page, 1);
  const url = new URL(window.location.href);
  url.searchParams.set("page", String(nextPage));
  const method = replace ? "replaceState" : "pushState";
  window.history[method]({}, "", `${url.pathname}${url.search}${url.hash}`);
}

function isFileConversionMode(mode) {
  return mode === POST_MODES.FILE_CONVERSION_REQUEST;
}

function getPostModeLabel(mode) {
  return isFileConversionMode(mode) ? "파일 변환 요청" : "일반";
}

function getPostBodyLabel(mode) {
  return isFileConversionMode(mode) ? "Base64 본문" : "본문";
}

function getPostBodyHelp(mode) {
  if (isFileConversionMode(mode)) {
    return "ZIP 파일을 Base64로 변환한 문자열을 그대로 붙여넣습니다. 이 모드에서는 첨부파일을 업로드하지 않습니다.";
  }

  return "일반 게시글 본문은 전송 직전에 Base64로 인코딩됩니다.";
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

function WelcomePage() {
  const createPostFormId = "create-post-form";
  const [view, setView] = useState("list");
  const [posts, setPosts] = useState([]);
  const [currentPage, setCurrentPage] = useState(() => getPageFromLocation());
  const [pagination, setPagination] = useState(DEFAULT_PAGINATION);
  const [selectedPostId, setSelectedPostId] = useState(null);
  const [selectedPost, setSelectedPost] = useState(null);
  const [postActionMode, setPostActionMode] = useState("none");
  const [postForm, setPostForm] = useState(EMPTY_POST_FORM);
  const [postAttachmentFile, setPostAttachmentFile] = useState(null);
  const [postAttachmentInputKey, setPostAttachmentInputKey] = useState(0);
  const [replyForm, setReplyForm] = useState(EMPTY_REPLY_FORM);
  const [selectedAiProvider, setSelectedAiProvider] = useState("GPT");
  const [postEditForm, setPostEditForm] = useState(EMPTY_POST_FORM);
  const [postEditAttachmentFile, setPostEditAttachmentFile] = useState(null);
  const [postEditAttachmentInputKey, setPostEditAttachmentInputKey] = useState(0);
  const [removePostAttachment, setRemovePostAttachment] = useState(false);
  const [replyEditState, setReplyEditState] = useState({ replyId: null, body: "", password: "" });
  const [postDeletePassword, setPostDeletePassword] = useState("");
  const [replyDeleteState, setReplyDeleteState] = useState({ replyId: null, password: "" });
  const [postActionError, setPostActionError] = useState("");
  const [replyActionError, setReplyActionError] = useState("");
  const [aiReplyError, setAiReplyError] = useState("");
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [aiSubmitting, setAiSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    function handlePopState() {
      setCurrentPage(getPageFromLocation());
      setView("list");
    }

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (view === "list") {
      loadPosts(currentPage);
    }
  }, [view, currentPage]);

  useEffect(() => {
    if (view === "detail" && selectedPostId != null) {
      loadPostDetail(selectedPostId);
    }
  }, [view, selectedPostId]);

  async function loadPosts(page = currentPage) {
    setLoading(true);
    setError("");

    try {
      const payload = await getPosts(page);
      if ((payload.totalPages ?? 0) > 0 && page > payload.totalPages) {
        navigateToPage(payload.totalPages, { replace: true });
        return loadPosts(payload.totalPages);
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
      return payload;
    } catch (loadError) {
      setError(loadError.message);
      return null;
    } finally {
      setLoading(false);
    }
  }

  function navigateToPage(page, options = {}) {
    const nextPage = Math.max(page, 1);
    updatePageInUrl(nextPage, options);
    setCurrentPage(nextPage);
  }

  async function loadPostDetail(postId) {
    setDetailLoading(true);
    setError("");

    try {
      const payload = await getPost(postId);
      setSelectedPost(payload);
      setPostEditForm({
        title: payload.title,
        body: payload.body,
        password: "",
        mode: payload.mode ?? POST_MODES.NORMAL
      });
      setPostEditAttachmentFile(null);
      setPostEditAttachmentInputKey((prev) => prev + 1);
      setRemovePostAttachment(false);
    } catch (loadError) {
      setError(loadError.message);
    } finally {
      setDetailLoading(false);
    }
  }

  function openList() {
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

  function openWrite() {
    setView("write");
    setPostForm(EMPTY_POST_FORM);
    setPostAttachmentFile(null);
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
    setReplyEditState({ replyId: null, body: "", password: "" });
    setReplyDeleteState({ replyId: null, password: "" });
    setPostDeletePassword("");
    setPostEditAttachmentFile(null);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemovePostAttachment(false);
    setPostActionError("");
    setReplyActionError("");
    setAiReplyError("");
    setSelectedAiProvider("GPT");
    setMessage("");
    setError("");
  }

  function openPostEditPanel() {
    if (!selectedPost || selectedPost.conversionReady) {
      return;
    }

    setPostEditForm({
      title: selectedPost.title,
      body: selectedPost.body,
      password: "",
      mode: selectedPost.mode ?? POST_MODES.NORMAL
    });
    setPostEditAttachmentFile(null);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemovePostAttachment(false);
    setPostDeletePassword("");
    setPostActionError("");
    setPostActionMode("edit");
  }

  function openPostDeletePanel() {
    setPostDeletePassword("");
    setPostActionError("");
    setPostActionMode("delete");
  }

  function closePostActionPanel() {
    setPostActionMode("none");
    setPostDeletePassword("");
    setPostActionError("");
    setPostEditForm((prev) => ({ ...prev, password: "" }));
    setPostEditAttachmentFile(null);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemovePostAttachment(false);
  }

  function openReplyEditPanel(reply) {
    setReplyActionError("");
    setReplyDeleteState({ replyId: null, password: "" });
    setReplyEditState({
      replyId: reply.id,
      body: reply.body,
      password: ""
    });
  }

  function openReplyDeletePanel(replyId) {
    setReplyActionError("");
    setReplyEditState({ replyId: null, body: "", password: "" });
    setReplyDeleteState({ replyId, password: "" });
  }

  function closeReplyEditPanel() {
    setReplyActionError("");
    setReplyEditState({ replyId: null, body: "", password: "" });
  }

  function closeReplyDeletePanel() {
    setReplyActionError("");
    setReplyDeleteState({ replyId: null, password: "" });
  }

  async function handleCreatePost(event) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      const created = await createPost({
        ...postForm,
        attachment: postAttachmentFile
      });
      setPostForm(EMPTY_POST_FORM);
      setPostAttachmentFile(null);
      setPostAttachmentInputKey((prev) => prev + 1);
      navigateToPage(1, { replace: true });
      await loadPosts(1);
      openDetail(created.id);
      setMessage("게시글을 등록했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleUpdatePost(event) {
    event.preventDefault();
    if (!selectedPostId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");
    setPostActionError("");

    try {
      const updated = await updatePost(selectedPostId, {
        ...postEditForm,
        attachment: postEditAttachmentFile,
        removeAttachment: removePostAttachment
      });
      setSelectedPost(updated);
      setPostActionMode("none");
      setPostActionError("");
      setPostEditForm((prev) => ({ ...prev, password: "" }));
      setPostEditAttachmentFile(null);
      setPostEditAttachmentInputKey((prev) => prev + 1);
      setRemovePostAttachment(false);
      await loadPosts(currentPage);
      setMessage("게시글을 수정했습니다.");
    } catch (submitError) {
      if (isInvalidPasswordError(submitError)) {
        setPostActionError("비밀번호가 일치하지 않습니다.");
      } else if (submitError?.code === "FILE_CONVERSION_LOCKED") {
        setError("변환 완료된 파일 변환 요청 글은 수정할 수 없습니다.");
      } else {
        setError(submitError.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeletePost(event) {
    event.preventDefault();
    if (!selectedPostId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");
    setPostActionError("");

    try {
      await deletePost(selectedPostId, postDeletePassword);
      setPostDeletePassword("");
      setPostActionError("");
      await loadPosts(currentPage);
      openList();
      setMessage("게시글을 삭제했습니다.");
    } catch (submitError) {
      if (isInvalidPasswordError(submitError)) {
        setPostActionError("비밀번호가 일치하지 않습니다.");
      } else {
        setError(submitError.message);
      }
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
      const detail = await createReply(selectedPostId, replyForm);
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
        body: replyEditState.body,
        password: replyEditState.password
      });
      setSelectedPost(detail);
      setReplyActionError("");
      setReplyEditState({ replyId: null, body: "", password: "" });
      await loadPosts(currentPage);
      setMessage("답변을 수정했습니다.");
    } catch (submitError) {
      if (isInvalidPasswordError(submitError)) {
        setReplyActionError("비밀번호가 일치하지 않습니다.");
      } else {
        setError(submitError.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDeleteReply(event) {
    event.preventDefault();
    if (!replyDeleteState.replyId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");
    setReplyActionError("");

    try {
      const detail = await deleteReply(replyDeleteState.replyId, replyDeleteState.password);
      setSelectedPost(detail);
      setReplyActionError("");
      setReplyDeleteState({ replyId: null, password: "" });
      await loadPosts(currentPage);
      setMessage("답변을 삭제했습니다.");
    } catch (submitError) {
      if (isInvalidPasswordError(submitError)) {
        setReplyActionError("비밀번호가 일치하지 않습니다.");
      } else {
        setError(submitError.message);
      }
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
      const detail = await createAiReply(selectedPostId, selectedAiProvider);
      setSelectedPost(detail);
      await loadPosts(currentPage);
      setMessage("AI 답변을 등록했습니다.");
    } catch (submitError) {
      setAiReplyError(submitError.message);
    } finally {
      setAiSubmitting(false);
    }
  }

  async function handleConvertPost() {
    if (!selectedPostId) {
      return;
    }

    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      const detail = await convertPostToAttachment(selectedPostId);
      setSelectedPost(detail);
      await loadPosts(currentPage);
      setMessage("파일 변환을 완료했습니다.");
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setSubmitting(false);
    }
  }

  const pageNumbers = Array.from({ length: pagination.totalPages }, (_, index) => index + 1);

  return (
    <main className="board-page">
      <section className="board-shell">
        <header className="board-header">
          <div>
            <p className="eyebrow">Anonymous Board</p>
            <h1>답변 가능한 게시판</h1>
          </div>
          <div className="board-actions">
            <button type="button" className="ghost-button" onClick={openList}>
              목록
            </button>
            <button
              type={view === "write" ? "submit" : "button"}
              form={view === "write" ? createPostFormId : undefined}
              className={view === "write" ? "submit-button" : "primary-button"}
              onClick={view === "write" ? undefined : openWrite}
              disabled={view === "write" && submitting}
            >
              {view === "write" ? (submitting ? "등록 중..." : "등록") : "글쓰기"}
            </button>
          </div>
        </header>

        {message ? <p className="message-banner success">{message}</p> : null}
        {error ? <p className="message-banner error">{error}</p> : null}

        {view === "list" ? (
          <section className="card">
            <div className="section-heading">
              <div>
                <h2>게시글 목록</h2>
                <p className="section-meta">
                  총 {pagination.totalItems}개, 페이지 {pagination.page}
                  {pagination.totalPages > 0 ? ` / ${pagination.totalPages}` : ""}
                </p>
              </div>
              <button type="button" className="ghost-button" onClick={() => loadPosts(currentPage)}>
                새로고침
              </button>
            </div>
            {loading ? (
              <p className="empty-state">불러오는 중...</p>
            ) : posts.length === 0 ? (
              <p className="empty-state">아직 게시글이 없습니다. 첫 글을 작성해 보세요.</p>
            ) : (
              <div className="post-list">
                {posts.map((post) => (
                  <button
                    key={post.id}
                    type="button"
                    className="post-list-item"
                    onClick={() => openDetail(post.id)}
                  >
                    <div className="post-title-row">
                      <strong>{post.title}</strong>
                      <span className={`post-mode-badge${isFileConversionMode(post.mode) ? " file" : ""}`}>
                        {getPostModeLabel(post.mode)}
                      </span>
                      {post.conversionReady ? <span className="post-mode-badge success">변환 완료</span> : null}
                      {post.hasAttachment ? <span className="attachment-badge">첨부</span> : null}
                    </div>
                    <span>답변 {post.replyCount}개</span>
                    <time>{new Date(post.createdAt).toLocaleString()}</time>
                  </button>
                ))}
              </div>
            )}

            {posts.length > 0 ? (
              <div className="pagination">
                <button
                  type="button"
                  className="ghost-button pagination-button"
                  onClick={() => navigateToPage(1)}
                  disabled={!pagination.hasPrevious}
                >
                  처음
                </button>

                <button
                  type="button"
                  className="ghost-button pagination-button"
                  onClick={() => navigateToPage(currentPage - 1)}
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
                      onClick={() => navigateToPage(pageNumber)}
                    >
                      {pageNumber}
                    </button>
                  ))}
                </div>

                <button
                  type="button"
                  className="ghost-button pagination-button"
                  onClick={() => navigateToPage(currentPage + 1)}
                  disabled={!pagination.hasNext}
                >
                  다음
                </button>

                <button
                  type="button"
                  className="ghost-button pagination-button"
                  onClick={() => navigateToPage(pagination.totalPages)}
                  disabled={!pagination.hasNext}
                >
                  끝
                </button>
              </div>
            ) : null}
          </section>
        ) : null}

        {view === "write" ? (
          <section className="card">
            <div className="section-heading">
              <h2>새 글 작성</h2>
            </div>
            <form id={createPostFormId} className="form-grid" onSubmit={handleCreatePost}>
              <div className="field">
                <span>작성 모드</span>
                <div className="provider-options post-mode-options" role="radiogroup" aria-label="게시글 모드">
                  {POST_MODE_OPTIONS.map((option) => (
                    <label key={option.value} className="provider-option">
                      <input
                        type="radio"
                        name="post-mode"
                        value={option.value}
                        checked={postForm.mode === option.value}
                        onChange={(event) => {
                          const nextMode = event.target.value;
                          setPostForm((prev) => ({
                            ...prev,
                            mode: nextMode
                          }));
                          if (nextMode === POST_MODES.FILE_CONVERSION_REQUEST) {
                            setPostAttachmentFile(null);
                            setPostAttachmentInputKey((prev) => prev + 1);
                          }
                        }}
                      />
                      <span>{option.label}</span>
                    </label>
                  ))}
                </div>
              </div>
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
                <span>{getPostBodyLabel(postForm.mode)}</span>
                <textarea
                  value={postForm.body}
                  onChange={(event) => setPostForm((prev) => ({ ...prev, body: event.target.value }))}
                  rows={12}
                  required
                />
              </label>
              <p className="section-meta">{getPostBodyHelp(postForm.mode)}</p>
              <label className="field">
                <span>비밀번호</span>
                <input
                  type="password"
                  value={postForm.password}
                  onChange={(event) => setPostForm((prev) => ({ ...prev, password: event.target.value }))}
                  maxLength={100}
                  required
                />
              </label>
              {isFileConversionMode(postForm.mode) ? (
                <p className="section-meta">파일 변환 요청 모드에서는 결과 ZIP 파일만 생성되며, 일반 첨부파일은 업로드하지 않습니다.</p>
              ) : (
                <>
                  <label className="field">
                    <span>첨부파일</span>
                    <input
                      key={postAttachmentInputKey}
                      type="file"
                      onChange={(event) => setPostAttachmentFile(event.target.files?.[0] ?? null)}
                    />
                  </label>
                  <p className="section-meta">
                    첨부파일은 1개만 업로드할 수 있으며 최대 100MB까지 허용됩니다.
                    {postAttachmentFile ? ` 현재 선택: ${postAttachmentFile.name} (${formatFileSize(postAttachmentFile.size)})` : ""}
                  </p>
                </>
              )}
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
                        {selectedPost.conversionReady ? <span className="post-mode-badge success">변환 완료</span> : null}
                      </div>
                      <time>{new Date(selectedPost.createdAt).toLocaleString()}</time>
                    </div>
                    <div className="inline-actions">
                      {!selectedPost.conversionReady ? (
                        <button type="button" className="ghost-button" onClick={openPostEditPanel}>
                          수정
                        </button>
                      ) : null}
                      {isFileConversionMode(selectedPost.mode) && !selectedPost.conversionReady ? (
                        <button
                          type="button"
                          className="primary-button"
                          onClick={handleConvertPost}
                          disabled={submitting || aiSubmitting}
                        >
                          {submitting ? "변환 중..." : "파일 변환"}
                        </button>
                      ) : null}
                      <button type="button" className="danger-button" onClick={openPostDeletePanel}>
                        삭제
                      </button>
                    </div>
                  </div>
                  {isFileConversionMode(selectedPost.mode) ? (
                    <div className="conversion-summary-card">
                      <div className="conversion-summary-header">
                        <strong>Base64 본문 숨김</strong>
                        <span className={`post-mode-badge${selectedPost.conversionReady ? " success" : " file"}`}>
                          {selectedPost.conversionReady ? "변환 완료" : "변환 대기"}
                        </span>
                      </div>
                      <p className="section-meta">
                        파일 변환 요청 글의 raw Base64 본문은 상세 화면에서 숨겨집니다.
                      </p>
                      <div className="conversion-summary-stats">
                        <span>본문 길이 {selectedPost.body.length.toLocaleString()}자</span>
                        <span>
                          {selectedPost.conversionReady
                            ? "ZIP 파일이 생성되어 다운로드할 수 있습니다."
                            : "파일 변환 버튼을 누르면 ZIP 파일을 생성할 수 있습니다."}
                        </span>
                      </div>
                    </div>
                  ) : (
                    <p className="detail-body">{selectedPost.body}</p>
                  )}
                  {selectedPost.attachment ? (
                    <div className="attachment-panel">
                      <span className="attachment-label">
                        {isFileConversionMode(selectedPost.mode) ? "변환된 파일" : "첨부파일"}
                      </span>
                      <div className="attachment-card">
                        <div>
                          <strong>{selectedPost.attachment.originalFilename}</strong>
                          <p className="section-meta">
                            {formatFileSize(selectedPost.attachment.size)}
                            {selectedPost.attachment.contentType ? ` · ${selectedPost.attachment.contentType}` : ""}
                          </p>
                        </div>
                        <a
                          className="ghost-button attachment-link"
                          href={getApiUrl(selectedPost.attachment.downloadUrl)}
                          download={selectedPost.attachment.originalFilename}
                        >
                          다운로드
                        </a>
                      </div>
                    </div>
                  ) : null}
                  {postActionMode === "edit" ? (
                    <form className="form-grid compact-form action-panel" onSubmit={handleUpdatePost}>
                      <div className="field">
                        <span>작성 모드</span>
                        <div className="provider-options post-mode-options" role="radiogroup" aria-label="수정 게시글 모드">
                          {POST_MODE_OPTIONS.map((option) => (
                            <label key={option.value} className="provider-option">
                              <input
                                type="radio"
                                name="edit-post-mode"
                                value={option.value}
                                checked={postEditForm.mode === option.value}
                                onChange={(event) => {
                                  setPostActionError("");
                                  setPostEditForm((prev) => ({ ...prev, mode: event.target.value }));
                                  if (event.target.value === POST_MODES.FILE_CONVERSION_REQUEST) {
                                    setPostEditAttachmentFile(null);
                                    setPostEditAttachmentInputKey((prev) => prev + 1);
                                  }
                                }}
                              />
                              <span>{option.label}</span>
                            </label>
                          ))}
                        </div>
                      </div>
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
                        <span>{getPostBodyLabel(postEditForm.mode)}</span>
                        <textarea
                          value={postEditForm.body}
                          onChange={(event) => setPostEditForm((prev) => ({ ...prev, body: event.target.value }))}
                          rows={8}
                          required
                        />
                      </label>
                      <p className="section-meta">{getPostBodyHelp(postEditForm.mode)}</p>
                      <label className="field">
                        <span>비밀번호</span>
                        <input
                          type="password"
                          value={postEditForm.password}
                          onChange={(event) => {
                            setPostActionError("");
                            setPostEditForm((prev) => ({ ...prev, password: event.target.value }));
                          }}
                          maxLength={100}
                          required
                        />
                      </label>
                      {selectedPost.attachment ? (
                        <div className="attachment-panel">
                          <span className="attachment-label">현재 첨부파일</span>
                          <div className="attachment-card">
                            <div>
                              <strong>{selectedPost.attachment.originalFilename}</strong>
                              <p className="section-meta">{formatFileSize(selectedPost.attachment.size)}</p>
                            </div>
                            <a
                              className="ghost-button attachment-link"
                              href={getApiUrl(selectedPost.attachment.downloadUrl)}
                              download={selectedPost.attachment.originalFilename}
                            >
                              다운로드
                            </a>
                          </div>
                        </div>
                      ) : null}
                      {isFileConversionMode(postEditForm.mode) ? (
                        <p className="section-meta">파일 변환 요청 모드에서는 일반 첨부파일을 업로드할 수 없습니다.</p>
                      ) : (
                        <>
                          <label className="field">
                            <span>{selectedPost.attachment ? "새 첨부파일로 교체" : "첨부파일 추가"}</span>
                            <input
                              key={postEditAttachmentInputKey}
                              type="file"
                              onChange={(event) => {
                                const nextFile = event.target.files?.[0] ?? null;
                                setPostActionError("");
                                setPostEditAttachmentFile(nextFile);
                                if (nextFile) {
                                  setRemovePostAttachment(false);
                                }
                              }}
                            />
                          </label>
                          {postEditAttachmentFile ? (
                            <p className="section-meta">
                              새 파일 선택: {postEditAttachmentFile.name} ({formatFileSize(postEditAttachmentFile.size)})
                            </p>
                          ) : null}
                        </>
                      )}
                      {selectedPost.attachment ? (
                        <label className="checkbox-field">
                          <input
                            type="checkbox"
                            checked={removePostAttachment}
                            disabled={postEditAttachmentFile != null}
                            onChange={(event) => {
                              setPostActionError("");
                              setRemovePostAttachment(event.target.checked);
                            }}
                          />
                          <span>
                            {isFileConversionMode(postEditForm.mode) ? "현재 첨부파일 삭제 후 파일 변환 요청으로 변경" : "현재 첨부파일 삭제"}
                          </span>
                        </label>
                      ) : null}
                      {isFileConversionMode(postEditForm.mode) && selectedPost.attachment && !removePostAttachment ? (
                        <p className="panel-error">파일 변환 요청 모드로 바꾸려면 현재 첨부파일을 먼저 삭제해야 합니다.</p>
                      ) : null}
                      {postActionError ? <p className="panel-error">{postActionError}</p> : null}
                      <div className="action-form-actions">
                        <button
                          type="submit"
                          className="ghost-button"
                          disabled={
                            submitting ||
                            (isFileConversionMode(postEditForm.mode) &&
                              selectedPost.attachment != null &&
                              !removePostAttachment)
                          }
                        >
                          게시글 수정
                        </button>
                        <button type="button" className="ghost-button" onClick={closePostActionPanel}>
                          취소
                        </button>
                      </div>
                    </form>
                  ) : null}

                  {postActionMode === "delete" ? (
                    <form className="form-grid compact-form action-panel" onSubmit={handleDeletePost}>
                      <label className="field">
                        <span>비밀번호 확인</span>
                        <input
                          type="password"
                          value={postDeletePassword}
                          onChange={(event) => {
                            setPostActionError("");
                            setPostDeletePassword(event.target.value);
                          }}
                          maxLength={100}
                          required
                        />
                      </label>
                      {postActionError ? <p className="panel-error">{postActionError}</p> : null}
                      <div className="action-form-actions">
                        <button type="submit" className="danger-button" disabled={submitting}>
                          게시글 삭제
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
                      <label className="field">
                        <span>비밀번호</span>
                        <input
                          type="password"
                          value={replyForm.password}
                          onChange={(event) => setReplyForm((prev) => ({ ...prev, password: event.target.value }))}
                          maxLength={100}
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
                          {["GPT", "Claude", "Grok"].map((provider) => (
                            <label key={provider} className="provider-option">
                              <input
                                type="radio"
                                name="ai-provider"
                                value={provider}
                                checked={selectedAiProvider === provider}
                                onChange={(event) => {
                                  setAiReplyError("");
                                  setSelectedAiProvider(event.target.value);
                                }}
                              />
                              <span>{provider}</span>
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
                                onClick={() => openReplyDeletePanel(reply.id)}
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
                              <label className="field">
                                <span>비밀번호</span>
                                <input
                                  type="password"
                                  value={replyEditState.password}
                                  onChange={(event) => {
                                    setReplyActionError("");
                                    setReplyEditState((prev) => ({ ...prev, password: event.target.value }));
                                  }}
                                  maxLength={100}
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

                          {!reply.ai && replyDeleteState.replyId === reply.id ? (
                            <form className="form-grid compact-form action-panel" onSubmit={handleDeleteReply}>
                              <label className="field">
                                <span>삭제 비밀번호</span>
                                <input
                                  type="password"
                                  value={replyDeleteState.password}
                                  onChange={(event) => {
                                    setReplyActionError("");
                                    setReplyDeleteState((prev) => ({ ...prev, password: event.target.value }));
                                  }}
                                  maxLength={100}
                                  required
                                />
                              </label>
                              {replyActionError ? <p className="panel-error">{replyActionError}</p> : null}
                              <div className="action-form-actions">
                                <button type="submit" className="danger-button" disabled={submitting}>
                                  답변 삭제
                                </button>
                                <button type="button" className="ghost-button" onClick={closeReplyDeletePanel}>
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
