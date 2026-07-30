// 인증 부트스트랩 (클라이언트 전용).
// - localStorage → auth store 동기화(hydrate)
// - 토큰 있으면 getMe 검증, 실패 시 로그아웃
// - auth:unauthorized 이벤트(인증 요청 401) → 강제 로그아웃 (앱 수명 동안 전역 리스너)
import { useAuthStore } from "~/stores/auth";

function isPublicPath(path: string): boolean {
  return path === "/login" || /^\/posts\/\d+$/.test(path);
}

export default defineNuxtPlugin(async () => {
  const auth = useAuthStore();
  auth.hydrate();

  if (auth.token) {
    // 라우트 미들웨어가 보호 경로를 판정하기 전에 저장 토큰 검증을 끝낸다.
    await auth.fetchMe();
  } else {
    auth.checked = true;
  }

  function handleUnauthorized() {
    auth.logout();
    if (!isPublicPath(window.location.pathname)) {
      void navigateTo("/login", { replace: true });
    }
  }

  window.addEventListener("auth:unauthorized", handleUnauthorized);
});
