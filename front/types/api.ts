// API 타입 정의 — docs/07-api-reference.md 기반 수동 정의.
// 백엔드에 springdoc/OpenAPI가 없으므로 문서에서 도출.

export type AiProvider = "GPT" | "CLAUDE" | "GROK";

export type PostMode = "NORMAL" | "FILE_CONVERSION_REQUEST";

export interface Attachment {
  id: number;
  originalFilename: string;
  size: number;
  contentType: string;
  downloadUrl: string;
}

export interface Reply {
  id: number;
  body: string;
  ai: boolean;
  aiProvider: AiProvider | null;
  aiModel: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 게시글 목록과 상세 응답이 공유하는 필드 */
export interface Post {
  id: number;
  title: string;
  mode: PostMode;
  conversionReady: boolean;
  createdAt: string;
}

/** GET /posts 목록의 개별 요약 */
export interface PostSummary extends Post {
  replyCount: number;
  hasAttachment: boolean;
}

/** GET /posts/{id} 상세 */
export interface PostDetail extends Post {
  body: string;
  updatedAt: string;
  attachments: Attachment[];
  replies: Reply[];
}

export interface Pagination {
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
  hasPrevious: boolean;
  hasNext: boolean;
}

export interface PostListResponse extends Pagination {
  items: PostSummary[];
}

export interface ApiError {
  code: string | null;
  status: number;
  message: string;
}

export interface LoginResponse {
  token: string;
  username: string;
}

export interface MeResponse {
  username: string;
}

/** 게시글 생성/수정 폼 데이터 */
export interface PostMutationInput {
  title: string;
  body: string;
  attachments?: File[];
  removeAttachmentIds?: number[];
}
