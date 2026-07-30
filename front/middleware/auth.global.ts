// 전역 라우트 가드.
// - /posts/:id (공개 상세) · /login 은 토큰 없이 접근 허용
// - 토큰 있는데 /login 접근 → / 로 이동
// - 그 외(보드 등)는 토큰 없으면 /login 으로 리다이렉트
import { useAuthStore } from "~/stores/auth";

export default defineNuxtRouteMiddleware((to) => {
  const auth = useAuthStore();
  const isPublicPost = /^\/posts\/\d+$/.test(to.path);
  const isLogin = to.path === "/login";

  if (isPublicPost) {
    return;
  }

  if (isLogin) {
    if (auth.token) {
      return navigateTo("/", { replace: true });
    }
    return;
  }

  if (!auth.token) {
    return navigateTo("/login", { replace: true });
  }
});
