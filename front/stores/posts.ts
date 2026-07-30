// 게시글 목록 스토어 — 페이지네이션/검색/URL 동기화/일괄삭제.
import { defineStore } from "pinia";
import { batchDeletePosts, getPosts } from "~/services/api";
import type { Pagination, PostListResponse, PostSummary } from "~/types/api";
import {
  getListStateFromLocation,
  normalizeSearchQuery,
  updateListStateInUrl
} from "~/composables/useListUrlState";
import { useAuthStore } from "./auth";

const DEFAULT_PAGINATION: Pagination = {
  page: 1,
  pageSize: 10,
  totalItems: 0,
  totalPages: 0,
  hasPrevious: false,
  hasNext: false
};

interface PostsState {
  items: PostSummary[];
  pagination: Pagination;
  currentPage: number;
  searchQuery: string;
  searchInput: string;
  loading: boolean;
  selectedPostIds: Set<number>;
  listError: string;
  listMessage: string;
}

export const usePostsStore = defineStore("posts", {
  state: (): PostsState => {
    const initial = getListStateFromLocation();
    return {
      items: [],
      pagination: { ...DEFAULT_PAGINATION },
      currentPage: initial.page,
      searchQuery: initial.query,
      searchInput: initial.query,
      loading: true,
      selectedPostIds: new Set(),
      listError: "",
      listMessage: ""
    };
  },
  getters: {
    pageNumbers: (state): number[] =>
      Array.from({ length: state.pagination.totalPages }, (_, index) => index + 1)
  },
  actions: {
    setSearchInput(value: string) {
      this.searchInput = value;
    },
    navigateToList(page: number, query: string = this.searchQuery, options: { replace?: boolean } = {}) {
      const nextPage = Math.max(page, 1);
      const normalizedQuery = normalizeSearchQuery(query);
      updateListStateInUrl(nextPage, normalizedQuery, options);
      this.currentPage = nextPage;
      this.searchQuery = normalizedQuery;
    },
    async loadPosts(page: number = this.currentPage, query: string = this.searchQuery): Promise<PostListResponse | null> {
      this.loading = true;
      this.selectedPostIds = new Set();
      this.listError = "";

      try {
        const normalizedQuery = normalizeSearchQuery(query);
        const payload = await getPosts(page, normalizedQuery);
        if ((payload.totalPages ?? 0) > 0 && page > payload.totalPages) {
          this.navigateToList(payload.totalPages, normalizedQuery, { replace: true });
          return this.loadPosts(payload.totalPages, normalizedQuery);
        }
        if ((payload.totalPages ?? 0) === 0 && page > 1) {
          this.navigateToList(1, normalizedQuery, { replace: true });
          return this.loadPosts(1, normalizedQuery);
        }

        this.items = payload.items ?? [];
        this.pagination = {
          page: payload.page ?? page,
          pageSize: payload.pageSize ?? DEFAULT_PAGINATION.pageSize,
          totalItems: payload.totalItems ?? 0,
          totalPages: payload.totalPages ?? 0,
          hasPrevious: payload.hasPrevious ?? false,
          hasNext: payload.hasNext ?? false
        };
        this.currentPage = payload.page ?? page;
        this.searchQuery = normalizedQuery;
        return payload;
      } catch (loadError) {
        this.listError = (loadError as Error).message;
        return null;
      } finally {
        this.loading = false;
      }
    },
    handleSearchSubmit() {
      const nextQuery = normalizeSearchQuery(this.searchInput);
      this.searchInput = nextQuery;
      this.navigateToList(1, nextQuery);
    },
    handleSearchReset() {
      this.searchInput = "";
      this.navigateToList(1, "");
    },
    toggleSelection(postId: number) {
      const next = new Set(this.selectedPostIds);
      if (next.has(postId)) {
        next.delete(postId);
      } else {
        next.add(postId);
      }
      this.selectedPostIds = next;
    },
    toggleSelectAll() {
      if (this.selectedPostIds.size === this.items.length) {
        this.selectedPostIds = new Set();
      } else {
        this.selectedPostIds = new Set(this.items.map((p) => p.id));
      }
    },
    async batchDelete(): Promise<number> {
      const auth = useAuthStore();
      const count = this.selectedPostIds.size;
      if (!auth.token || count === 0) return 0;

      this.listError = "";
      this.listMessage = "";
      try {
        await batchDeletePosts([...this.selectedPostIds], auth.token);
        this.selectedPostIds = new Set();
        await this.loadPosts(this.currentPage, this.searchQuery);
        this.listMessage = `${count}개의 게시글을 삭제했습니다.`;
        return count;
      } catch (batchError) {
        this.listError = (batchError as Error).message;
        return 0;
      }
    }
  }
});
