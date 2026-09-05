// 인증 스토어 — localStorage JWT 토큰/사용자명/역할 관리.
import { defineStore } from "pinia";
import { getMe, login as apiLogin } from "~/services/api";
import type { UserRole } from "~/types/api";

const TOKEN_KEY = "auth_token";
const USERNAME_KEY = "auth_username";
const USER_ID_KEY = "auth_user_id";
const ROLE_KEY = "auth_role";
// 유휴 타임아웃용 마지막 활동 시각 키 — useIdleTimeout과 공유.
export const LAST_ACTIVITY_KEY = "auth_last_activity";

interface AuthState {
  token: string | null;
  username: string | null;
  userId: number | null;
  role: UserRole | null;
  checked: boolean;
}

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => ({
    token: null,
    username: null,
    userId: null,
    role: null,
    checked: false
  }),
  getters: {
    // 사용자 관리 메뉴 등 ADMIN 전용 기능 노출 판단.
    isAdmin(): boolean {
      return this.role === "ADMIN";
    }
  },
  actions: {
    // 클라이언트 부팅 시 localStorage → state 동기화.
    hydrate() {
      if (!import.meta.client) return;
      this.token = localStorage.getItem(TOKEN_KEY);
      this.username = localStorage.getItem(USERNAME_KEY);
      const storedId = Number(localStorage.getItem(USER_ID_KEY));
      this.userId = Number.isSafeInteger(storedId) && storedId > 0 ? storedId : null;
      const storedRole = localStorage.getItem(ROLE_KEY);
      this.role = storedRole === "ADMIN" || storedRole === "USER" ? storedRole : null;
    },
    setAuth(token: string, userId: number, username: string, role: UserRole) {
      this.token = token;
      this.username = username;
      this.userId = userId;
      this.role = role;
      this.checked = true;
      if (import.meta.client) {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USERNAME_KEY, username);
        localStorage.setItem(USER_ID_KEY, String(userId));
        localStorage.setItem(ROLE_KEY, role);
        // 새 로그인 시점을 유휴 타이머 기준 활동으로 시드 (이전 세션 잔여 값 무시).
        localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
      }
    },
    clearStoredAuth() {
      if (import.meta.client) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USERNAME_KEY);
        localStorage.removeItem(USER_ID_KEY);
        localStorage.removeItem(ROLE_KEY);
        localStorage.removeItem(LAST_ACTIVITY_KEY);
      }
    },
    async login(username: string, password: string) {
      const result = await apiLogin(username, password);
      this.setAuth(result.token, result.userId, result.username, result.role);
      return result;
    },
    async fetchMe() {
      if (!this.token) {
        this.checked = true;
        return;
      }
      try {
        const result = await getMe(this.token);
        this.username = result.username;
        this.userId = result.userId;
        if (import.meta.client) localStorage.setItem(USER_ID_KEY, String(result.userId));
        this.role = result.role;
        this.checked = true;
      } catch {
        // 토큰 무효/만료/계정 삭제 → 저장값 정리.
        this.logout();
      }
    },
    logout() {
      this.clearStoredAuth();
      this.token = null;
      this.username = null;
      this.userId = null;
      this.role = null;
      this.checked = true;
    }
  }
});
