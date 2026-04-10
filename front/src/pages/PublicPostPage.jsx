import { useEffect, useState } from "react";
import { getApiUrl, getPost } from "../api";

function isFileConversionMode(mode) {
  return mode === "FILE_CONVERSION_REQUEST";
}

function getPostModeLabel(mode) {
  return isFileConversionMode(mode) ? "암호화 업로드" : "일반";
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

function PublicPostPage({ postId }) {
  const [post, setPost] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const replies = post?.replies ?? [];

  useEffect(() => {
    let cancelled = false;

    async function loadPost() {
      setLoading(true);
      setError("");

      try {
        const payload = await getPost(postId);
        if (cancelled) {
          return;
        }
        setPost(payload);
      } catch (loadError) {
        if (cancelled) {
          return;
        }
        setPost(null);
        setError(loadError.message);
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadPost();

    return () => {
      cancelled = true;
    };
  }, [postId]);

  function goToBoard() {
    window.location.href = "/";
  }

  return (
    <main className="board-page">
      <section className="board-shell">
        <header className="board-header">
          <div>
            <p className="eyebrow">Anonymous Board</p>
            <h1>게시글 보기</h1>
          </div>
          <div className="board-actions">
            <button type="button" className="ghost-button" onClick={goToBoard}>
              ← 게시판으로
            </button>
          </div>
        </header>

        {loading ? (
          <section className="card">
            <p className="empty-state">불러오는 중...</p>
          </section>
        ) : error ? (
          <section className="card">
            <p className="message-banner error">{error}</p>
            <div className="inline-actions">
              <button type="button" className="ghost-button" onClick={() => window.location.reload()}>
                다시 시도
              </button>
              <button type="button" className="ghost-button" onClick={goToBoard}>
                홈으로
              </button>
            </div>
          </section>
        ) : post ? (
          <>
            <section className="card">
              <div className="section-heading">
                <h2>게시글 상세</h2>
                <span>댓글 {replies.length}개</span>
              </div>

              <article className="detail-panel">
                <div className="detail-top">
                  <div>
                    <h3>{post.title}</h3>
                    <div className="detail-meta-row">
                      <span className={`post-mode-badge${isFileConversionMode(post.mode) ? " file" : ""}`}>
                        {getPostModeLabel(post.mode)}
                      </span>
                      {post.conversionReady ? <span className="post-mode-badge success">암호화 업로드 완료</span> : null}
                    </div>
                    <time>{new Date(post.createdAt).toLocaleString()}</time>
                  </div>
                </div>

                {isFileConversionMode(post.mode) ? (
                  <p className="detail-body">암호화 업로드 글입니다. 본문은 공개 상세 화면에서 표시되지 않습니다.</p>
                ) : (
                  <p className="detail-body">{post.body}</p>
                )}

                {post.attachment ? (
                  <div className="attachment-panel">
                    <span className="attachment-label">
                      {isFileConversionMode(post.mode) ? "복원 파일" : "첨부파일"}
                    </span>
                    <div className="attachment-card">
                      <div>
                        <strong>{post.attachment.originalFilename}</strong>
                        <p className="section-meta">
                          {formatFileSize(post.attachment.size)}
                          {post.attachment.contentType ? ` · ${post.attachment.contentType}` : ""}
                        </p>
                      </div>
                      <a
                        className="ghost-button attachment-link"
                        href={getApiUrl(post.attachment.downloadUrl)}
                        download={post.attachment.originalFilename}
                      >
                        다운로드
                      </a>
                    </div>
                  </div>
                ) : null}
              </article>
            </section>

            <section className="reply-section">
              <div className="section-heading">
                <h3>댓글</h3>
              </div>

              {replies.length === 0 ? (
                <p className="empty-state">아직 댓글이 없습니다.</p>
              ) : (
                <div className="reply-list">
                  {replies.map((reply) => (
                    <article key={reply.id} className="card inset-card reply-card">
                      <div className="reply-top">
                        <div className="reply-heading">
                          <strong>댓글 #{reply.id}</strong>
                          {reply.ai ? <span className="ai-badge">AI · {reply.aiProvider}</span> : null}
                        </div>
                        <time>{new Date(reply.createdAt).toLocaleString()}</time>
                      </div>
                      <p className="detail-body">{reply.body}</p>
                    </article>
                  ))}
                </div>
              )}
            </section>

            <section className="card">
              <div className="section-heading">
                <h2>관리자 전용 기능</h2>
              </div>
              <p className="section-meta">수정, 삭제, 댓글 작성 등 관리자 기능은 로그인 후 사용할 수 있습니다.</p>
              <a
                className="ghost-button"
                href="/"
                onClick={(event) => {
                  event.preventDefault();
                  goToBoard();
                }}
              >
                관리자 로그인
              </a>
            </section>
          </>
        ) : null}
      </section>
    </main>
  );
}

export default PublicPostPage;
