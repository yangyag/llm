// 게시글 상세/작성/댓글/AI/첨부 스토어 — WelcomePage.jsx 핵심 로직 이관.
import { defineStore } from "pinia";
import {
  createAiReply,
  createPost,
  createReply,
  deletePost,
  deleteReply,
  getPost,
  updatePost,
  updateReply
} from "~/services/api";
import type { AiProvider, ApiError, PostDetail } from "~/types/api";
import {
  ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE,
  MAX_ATTACHMENTS,
  attachmentFileKey,
  mergeAttachmentFiles
} from "~/utils/post";
import { useAuthStore } from "./auth";
import { usePostsStore } from "./posts";

const EMPTY_POST_FORM = { title: "", body: "" };
const EMPTY_REPLY_FORM = { body: "" };

type View = "list" | "write" | "detail";
type PostActionMode = "none" | "edit";

interface PostFormState {
  title: string;
  body: string;
}

interface ReplyEditState {
  replyId: number | null;
  body: string;
}

interface PostDetailState {
  view: View;
  selectedPostId: number | null;
  selectedPost: PostDetail | null;
  detailLoading: boolean;
  postActionMode: PostActionMode;
  postForm: PostFormState;
  postEditForm: PostFormState;
  postAttachmentFiles: File[];
  postEditAttachmentFiles: File[];
  postAttachmentConfirmed: boolean;
  postEditAttachmentConfirmed: boolean;
  postAttachmentInputKey: number;
  postEditAttachmentInputKey: number;
  removeAttachmentIds: Set<number>;
  replyForm: { body: string };
  replyEditState: ReplyEditState;
  selectedAiProvider: AiProvider;
  submitting: boolean;
  aiSubmitting: boolean;
  error: string;
  message: string;
  postActionError: string;
  replyActionError: string;
  aiReplyError: string;
  postLinkCopied: boolean;
}

export const usePostDetailStore = defineStore("postDetail", {
  state: (): PostDetailState => ({
    view: "list",
    selectedPostId: null,
    selectedPost: null,
    detailLoading: false,
    postActionMode: "none",
    postForm: { ...EMPTY_POST_FORM },
    postEditForm: { ...EMPTY_POST_FORM },
    postAttachmentFiles: [],
    postEditAttachmentFiles: [],
    postAttachmentConfirmed: false,
    postEditAttachmentConfirmed: false,
    postAttachmentInputKey: 0,
    postEditAttachmentInputKey: 0,
    removeAttachmentIds: new Set(),
    replyForm: { ...EMPTY_REPLY_FORM },
    replyEditState: { replyId: null, body: "" },
    selectedAiProvider: "GPT",
    submitting: false,
    aiSubmitting: false,
    error: "",
    message: "",
    postActionError: "",
    replyActionError: "",
    aiReplyError: "",
    postLinkCopied: false
  }),
  actions: {
    resetListViewState() {
      this.view = "list";
      this.selectedPostId = null;
      this.selectedPost = null;
      this.postActionMode = "none";
      this.postActionError = "";
      this.replyActionError = "";
      this.aiReplyError = "";
      this.message = "";
      this.error = "";
    },
    async refreshListView() {
      const posts = usePostsStore();
      this.resetListViewState();
      await posts.loadPosts(posts.currentPage, posts.searchQuery);
    },
    openWrite() {
      this.view = "write";
      this.postForm = { ...EMPTY_POST_FORM };
      this.postAttachmentFiles = [];
      this.postAttachmentConfirmed = false;
      this.postAttachmentInputKey += 1;
      this.postActionMode = "none";
      this.postActionError = "";
      this.replyActionError = "";
      this.aiReplyError = "";
      this.message = "";
      this.error = "";
    },
    openDetail(postId: number) {
      this.selectedPostId = postId;
      this.view = "detail";
      this.postActionMode = "none";
      this.replyForm = { ...EMPTY_REPLY_FORM };
      this.replyEditState = { replyId: null, body: "" };
      this.postEditAttachmentFiles = [];
      this.postEditAttachmentConfirmed = false;
      this.postEditAttachmentInputKey += 1;
      this.removeAttachmentIds = new Set();
      this.postActionError = "";
      this.replyActionError = "";
      this.aiReplyError = "";
      this.selectedAiProvider = "GPT";
      this.message = "";
      this.error = "";
      this.loadPostDetail(postId);
    },
    async loadPostDetail(postId: number) {
      this.detailLoading = true;
      this.error = "";
      try {
        const payload = await getPost(postId);
        this.selectedPost = payload;
        this.postEditForm = { title: payload.title, body: payload.body };
        this.postEditAttachmentFiles = [];
        this.postEditAttachmentConfirmed = false;
        this.postEditAttachmentInputKey += 1;
        this.removeAttachmentIds = new Set();
      } catch (loadError) {
        this.error = (loadError as Error).message;
      } finally {
        this.detailLoading = false;
      }
    },
    openPostEditPanel() {
      if (!this.selectedPost || this.selectedPost.conversionReady || this.selectedPost.mode === "FILE_CONVERSION_REQUEST") {
        return;
      }
      this.postEditForm = { title: this.selectedPost.title, body: this.selectedPost.body };
      this.postEditAttachmentFiles = [];
      this.postEditAttachmentConfirmed = false;
      this.postEditAttachmentInputKey += 1;
      this.removeAttachmentIds = new Set();
      this.postActionError = "";
      this.postActionMode = "edit";
    },
    closePostActionPanel() {
      this.postActionMode = "none";
      this.postActionError = "";
      this.postEditAttachmentFiles = [];
      this.postEditAttachmentConfirmed = false;
      this.postEditAttachmentInputKey += 1;
      this.removeAttachmentIds = new Set();
    },
    confirmAttachmentUploadEnvironment(): boolean {
      return window.confirm(ATTACHMENT_ENVIRONMENT_CONFIRM_MESSAGE);
    },
    handleCreateAttachmentChange(event: Event) {
      const input = event.target as HTMLInputElement;
      const selectedFiles = Array.from(input.files ?? []);
      // 입력을 비워 같은 파일을 다시 골라도 onChange가 발생하고, 누적은 상태가 관리하게 한다.
      this.postAttachmentInputKey += 1;
      if (selectedFiles.length === 0) {
        return;
      }
      if (!this.confirmAttachmentUploadEnvironment()) {
        return;
      }
      this.error = "";
      const { files, truncated } = mergeAttachmentFiles(this.postAttachmentFiles, selectedFiles);
      this.postAttachmentFiles = files;
      this.postAttachmentConfirmed = true;
      if (truncated) {
        this.error = `첨부파일은 최대 ${MAX_ATTACHMENTS}개까지만 첨부할 수 있습니다.`;
      }
    },
    removeCreateAttachment(targetKey: string) {
      this.postAttachmentFiles = this.postAttachmentFiles.filter(
        (file) => attachmentFileKey(file) !== targetKey
      );
    },
    handleEditAttachmentChange(event: Event) {
      const input = event.target as HTMLInputElement;
      const selectedFiles = Array.from(input.files ?? []);
      this.postEditAttachmentInputKey += 1;
      this.postActionError = "";
      if (selectedFiles.length === 0) {
        return;
      }
      if (!this.confirmAttachmentUploadEnvironment()) {
        return;
      }
      const keptExistingCount = (this.selectedPost?.attachments ?? []).filter(
        (attachment) => !this.removeAttachmentIds.has(attachment.id)
      ).length;
      const availableSlots = Math.max(MAX_ATTACHMENTS - keptExistingCount, 0);
      const { files, truncated } = mergeAttachmentFiles(this.postEditAttachmentFiles, selectedFiles, availableSlots);
      this.postEditAttachmentFiles = files;
      this.postEditAttachmentConfirmed = true;
      if (truncated) {
        this.postActionError = `첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지만 등록할 수 있습니다.`;
      }
    },
    removeEditAttachment(targetKey: string) {
      this.postEditAttachmentFiles = this.postEditAttachmentFiles.filter(
        (file) => attachmentFileKey(file) !== targetKey
      );
    },
    toggleRemoveExistingAttachment(attachmentId: number) {
      this.postActionError = "";
      const next = new Set(this.removeAttachmentIds);
      if (next.has(attachmentId)) {
        next.delete(attachmentId);
      } else {
        next.add(attachmentId);
      }
      this.removeAttachmentIds = next;
    },
    openReplyEditPanel(replyId: number, body: string) {
      this.replyActionError = "";
      this.replyEditState = { replyId, body };
    },
    closeReplyEditPanel() {
      this.replyActionError = "";
      this.replyEditState = { replyId: null, body: "" };
    },
    async handleCreatePost() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!auth.token) return;

      if (this.postAttachmentFiles.length > 0 && !this.postAttachmentConfirmed && !this.confirmAttachmentUploadEnvironment()) {
        return;
      }

      this.submitting = true;
      this.error = "";
      this.message = "";
      try {
        const created = await createPost(
          { ...this.postForm, attachments: this.postAttachmentFiles },
          auth.token
        );
        this.postForm = { ...EMPTY_POST_FORM };
        this.postAttachmentFiles = [];
        this.postAttachmentConfirmed = false;
        this.postAttachmentInputKey += 1;
        posts.navigateToList(1, posts.searchQuery, { replace: true });
        await posts.loadPosts(1);
        this.openDetail(created.id);
        this.message = "게시글을 등록했습니다.";
      } catch (submitError) {
        const err = submitError as ApiError;
        if (err.code === "INVALID_ATTACHMENT_REQUEST") {
          this.error = `첨부파일 요청이 올바르지 않습니다. 첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지 등록할 수 있습니다.`;
        } else {
          this.error = err.message;
        }
      } finally {
        this.submitting = false;
      }
    },
    async handleUpdatePost() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!this.selectedPostId || !auth.token) return;

      if (this.postEditAttachmentFiles.length > 0 && !this.postEditAttachmentConfirmed && !this.confirmAttachmentUploadEnvironment()) {
        return;
      }

      // 삭제 표시 해제 등으로 (유지 기존 + 신규) 합계가 한도를 넘을 수 있으므로 제출 직전 재검증.
      const keptExistingCount = (this.selectedPost?.attachments ?? []).filter(
        (attachment) => !this.removeAttachmentIds.has(attachment.id)
      ).length;
      if (keptExistingCount + this.postEditAttachmentFiles.length > MAX_ATTACHMENTS) {
        this.postActionError = `첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지만 등록할 수 있습니다.`;
        return;
      }

      this.submitting = true;
      this.error = "";
      this.message = "";
      this.postActionError = "";
      try {
        const updated = await updatePost(
          this.selectedPostId,
          {
            ...this.postEditForm,
            attachments: this.postEditAttachmentFiles,
            removeAttachmentIds: [...this.removeAttachmentIds]
          },
          auth.token
        );
        this.selectedPost = updated;
        this.postActionMode = "none";
        this.postActionError = "";
        this.postEditAttachmentFiles = [];
        this.postEditAttachmentConfirmed = false;
        this.postEditAttachmentInputKey += 1;
        this.removeAttachmentIds = new Set();
        await posts.loadPosts(posts.currentPage);
        this.message = "게시글을 수정했습니다.";
      } catch (submitError) {
        const err = submitError as ApiError;
        if (err.code === "FILE_CONVERSION_LOCKED") {
          this.error = "암호화 업로드 완료된 글은 수정할 수 없습니다.";
        } else if (err.code === "INVALID_ATTACHMENT_REQUEST") {
          this.error = `첨부파일 요청이 올바르지 않습니다. 첨부파일은 글당 최대 ${MAX_ATTACHMENTS}개까지 등록할 수 있습니다.`;
        } else {
          this.error = err.message;
        }
      } finally {
        this.submitting = false;
      }
    },
    async handleDeletePost() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!this.selectedPostId || !auth.token) return;

      this.submitting = true;
      this.error = "";
      this.message = "";
      try {
        await deletePost(this.selectedPostId, auth.token);
        await posts.loadPosts(posts.currentPage);
        this.resetListViewState();
        this.message = "게시글을 삭제했습니다.";
      } catch (submitError) {
        this.error = (submitError as Error).message;
      } finally {
        this.submitting = false;
      }
    },
    async handleCreateReply() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!this.selectedPostId || !auth.token) return;

      this.submitting = true;
      this.error = "";
      this.message = "";
      try {
        const detail = await createReply(this.selectedPostId, this.replyForm.body, auth.token);
        this.selectedPost = detail;
        this.replyForm = { ...EMPTY_REPLY_FORM };
        await posts.loadPosts(posts.currentPage);
        this.message = "답변을 등록했습니다.";
      } catch (submitError) {
        this.error = (submitError as Error).message;
      } finally {
        this.submitting = false;
      }
    },
    async handleUpdateReply() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!this.replyEditState.replyId || !auth.token) return;

      this.submitting = true;
      this.error = "";
      this.message = "";
      this.replyActionError = "";
      try {
        const detail = await updateReply(this.replyEditState.replyId, this.replyEditState.body, auth.token);
        this.selectedPost = detail;
        this.replyActionError = "";
        this.replyEditState = { replyId: null, body: "" };
        await posts.loadPosts(posts.currentPage);
        this.message = "답변을 수정했습니다.";
      } catch (submitError) {
        this.error = (submitError as Error).message;
      } finally {
        this.submitting = false;
      }
    },
    async handleDeleteReply(replyId: number) {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!replyId || !this.selectedPostId || !auth.token) return;

      this.submitting = true;
      this.error = "";
      this.message = "";
      try {
        await deleteReply(replyId, auth.token);
        await this.loadPostDetail(this.selectedPostId);
        await posts.loadPosts(posts.currentPage);
        this.message = "답변을 삭제했습니다.";
      } catch (submitError) {
        this.error = (submitError as Error).message;
      } finally {
        this.submitting = false;
      }
    },
    async handleCreateAiReply() {
      const auth = useAuthStore();
      const posts = usePostsStore();
      if (!this.selectedPostId || !auth.token) return;

      this.aiSubmitting = true;
      this.aiReplyError = "";
      this.error = "";
      this.message = "";
      try {
        const detail = await createAiReply(this.selectedPostId, this.selectedAiProvider, auth.token);
        this.selectedPost = detail;
        await posts.loadPosts(posts.currentPage);
        this.message = "AI 답변을 등록했습니다.";
      } catch (submitError) {
        this.aiReplyError = (submitError as Error).message;
      } finally {
        this.aiSubmitting = false;
      }
    },
    setAiProvider(provider: AiProvider) {
      this.aiReplyError = "";
      this.selectedAiProvider = provider;
    },
    async handleCopyPostLink() {
      if (!this.selectedPost?.id) return;
      const postUrl = `${window.location.origin}/posts/${this.selectedPost.id}`;
      try {
        await window.navigator.clipboard.writeText(postUrl);
        this.postLinkCopied = true;
        window.setTimeout(() => {
          this.postLinkCopied = false;
        }, 2000);
      } catch {
        this.error = "게시글 링크를 클립보드에 복사하지 못했습니다.";
      }
    }
  }
});
