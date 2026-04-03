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

const POST_MODES = {
  NORMAL: "NORMAL",
  FILE_CONVERSION_REQUEST: "FILE_CONVERSION_REQUEST"
};

const EMPTY_POST_FORM = {
  title: "",
  body: "",
  mode: POST_MODES.NORMAL
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
  return mode === POST_MODES.FILE_CONVERSION_REQUEST;
}

function getPostModeLabel(mode) {
  return isFileConversionMode(mode) ? "ZIP 결과" : "일반";
}

function getPostBodyLabel() {
  return "본문";
}

function getPostBodyHelp() {
  return "일반 게시글 본문을 작성합니다. 첨부파일은 일반 게시글에만 업로드할 수 있습니다.";
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
  const sampleZipDownloadPath = "/upload_zip_post.zip";
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
  const [postAttachmentFile, setPostAttachmentFile] = useState(null);
  const [postAttachmentInputKey, setPostAttachmentInputKey] = useState(0);
  const [replyForm, setReplyForm] = useState(EMPTY_REPLY_FORM);
  const [selectedAiProvider, setSelectedAiProvider] = useState("GPT");
  const [postEditForm, setPostEditForm] = useState(EMPTY_POST_FORM);
  const [postEditAttachmentFile, setPostEditAttachmentFile] = useState(null);
  const [postEditAttachmentInputKey, setPostEditAttachmentInputKey] = useState(0);
  const [removePostAttachment, setRemovePostAttachment] = useState(false);
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
  }, [view, currentPage, searchQuery]);

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
        body: payload.body,
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
    setReplyEditState({ replyId: null, body: "" });
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
    if (!selectedPost || selectedPost.conversionReady || isFileConversionMode(selectedPost.mode)) {
      return;
    }

    setPostEditForm({
      title: selectedPost.title,
      body: selectedPost.body,
      mode: selectedPost.mode ?? POST_MODES.NORMAL
    });
    setPostEditAttachmentFile(null);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemovePostAttachment(false);
    setPostActionError("");
    setPostActionMode("edit");
  }

  function openPostDeletePanel() {
    setPostActionError("");
    setPostActionMode("delete");
  }

  function closePostActionPanel() {
    setPostActionMode("none");
    setPostActionError("");
    setPostEditAttachmentFile(null);
    setPostEditAttachmentInputKey((prev) => prev + 1);
    setRemovePostAttachment(false);
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
    setSubmitting(true);
    setError("");
    setMessage("");

    try {
      const created = await createPost({
        title: postForm.title,
        body: postForm.body,
        attachment: postAttachmentFile
      }, authToken);
      setPostForm(EMPTY_POST_FORM);
      setPostAttachmentFile(null);
      setPostAttachmentInputKey((prev) => prev + 1);
      navigateToList(1, searchQuery, { replace: true });
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
        title: postEditForm.title,
        body: postEditForm.body,
        attachment: postEditAttachmentFile,
        removeAttachment: removePostAttachment
      }, authToken);
      setSelectedPost(updated);
      setPostActionMode("none");
      setPostActionError("");
      setPostEditAttachmentFile(null);
      setPostEditAttachmentInputKey((prev) => prev + 1);
      setRemovePostAttachment(false);
      await loadPosts(currentPage);
      setMessage("게시글을 수정했습니다.");
    } catch (submitError) {
      if (submitError?.code === "FILE_CONVERSION_LOCKED") {
        setError("파일 생성 결과 게시글은 수정할 수 없습니다.");
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
      openList();
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

  return (
    <main className="board-page">
      <section className="board-shell">
        <header className="board-header">
          <div>
            <p className="eyebrow">Anonymous Board</p>
            <h1>결과 파일 게시판</h1>
            <p className="section-meta">단일 ZIP 업로드 처리로 생성된 결과 게시글을 조회하고 다운로드합니다.</p>
          </div>
          <div className="board-actions">
            <a
              className="ghost-button action-link-button"
              href={sampleZipDownloadPath}
              download="upload_zip_post.zip"
            >
              ZIP 다운로드
            </a>
            {authUsername ? (
              <span className="auth-username">{authUsername}</span>
            ) : null}
            {onLogout ? (
              <button type="button" className="ghost-button" onClick={onLogout}>
                로그아웃
              </button>
            ) : null}
            <button type="button" className="ghost-button" onClick={openList}>
              목록
            </button>
          </div>
        </header>

        {message ? <p className="message-banner success">{message}</p> : null}
        {error ? <p className="message-banner error">{error}</p> : null}

        {view === "list" ? (
          <>
            <section className="card">
              <div className="section-heading">
                <div>
                  <h2>게시글 목록</h2>
                  <p className="section-meta">
                    {searchQuery ? `검색 결과 ${pagination.totalItems}개` : `총 ${pagination.totalItems}개`}, 페이지 {pagination.page}
                    {pagination.totalPages > 0 ? ` / ${pagination.totalPages}` : ""}
                  </p>
                </div>
                <button type="button" className="ghost-button" onClick={() => loadPosts(currentPage, searchQuery)}>
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
              {loading ? (
                <p className="empty-state">불러오는 중...</p>
              ) : posts.length === 0 ? (
                <p className="empty-state">
                  {searchQuery ? "검색 결과가 없습니다. 다른 검색어로 다시 시도해 보세요." : "표시할 결과 게시글이 없습니다."}
                </p>
              ) : (
                <div className="post-list">
                  {posts.map((post) => (
                    <div key={post.id} className="post-list-item-row">
                      <button
                        type="button"
                        className="post-list-item"
                        onClick={() => openDetail(post.id)}
                      >
                        <div className="post-title-row">
                          <strong>{post.title}</strong>
                          <span className={`post-mode-badge${isFileConversionMode(post.mode) ? " file" : ""}`}>
                            {getPostModeLabel(post.mode)}
                          </span>
                          {post.conversionReady ? <span className="post-mode-badge success">파일 준비 완료</span> : null}
                          {post.hasAttachment ? <span className="attachment-badge">첨부</span> : null}
                        </div>
                        <span>
                          {isFileConversionMode(post.mode)
                            ? (post.conversionReady ? "다운로드 가능" : "결과 파일 준비 중")
                            : `답변 ${post.replyCount}개`}
                        </span>
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

        {view === "detail" ? (
          <section className="card">
            <div className="section-heading">
              <h2>게시글 상세</h2>
              {selectedPost ? (
                <span>
                  {isFileConversionMode(selectedPost.mode)
                    ? (selectedPost.conversionReady ? "다운로드 가능" : "결과 파일 준비 중")
                    : `답변 ${selectedPost.replies.length}개`}
                </span>
              ) : null}
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
                        {selectedPost.conversionReady ? <span className="post-mode-badge success">파일 준비 완료</span> : null}
                      </div>
                      <time>{new Date(selectedPost.createdAt).toLocaleString()}</time>
                    </div>
                  </div>
                  {isFileConversionMode(selectedPost.mode) ? (
                    <div className="attachment-panel">
                      <span className="attachment-label">처리 상태</span>
                      <div className="attachment-card">
                        <div>
                          <strong>{selectedPost.conversionReady ? "결과 파일 준비 완료" : "결과 파일 준비 중"}</strong>
                          <p className="section-meta">
                            {selectedPost.conversionReady
                              ? "단일 ZIP 업로드 처리로 생성된 결과 파일을 아래에서 다운로드할 수 있습니다."
                              : "단일 ZIP 업로드 처리 중이며, 원본 데이터 본문은 웹 UI에 표시하지 않습니다."}
                          </p>
                        </div>
                      </div>
                    </div>
                  ) : (
                    <p className="detail-body">{selectedPost.body}</p>
                  )}
                  {selectedPost.attachment ? (
                    <div className="attachment-panel">
                      <span className="attachment-label">{isFileConversionMode(selectedPost.mode) ? "생성된 결과 파일" : "첨부파일"}</span>
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
                </article>

                {selectedPost.replies.length > 0 ? (
                  <section className="reply-section">
                    <div className="section-heading">
                      <h3>답변</h3>
                    </div>
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
                        </article>
                      ))}
                    </div>
                  </section>
                ) : null}
              </>
            )}
          </section>
        ) : null}
      </section>
    </main>
  );
}

export default WelcomePage;
