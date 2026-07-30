// 인증 부트스트랩 (클라이언트 전용).
// - localStorage → auth store 동기화(hydrate)
// - 토큰 있으면 getMe 검증, 실패 시 로그아웃
// - auth:unauthorized 이벤트(인증 요청 401) → 강제 로그아웃 (앱 수명 동안 전역 리스너)
import { useAuthStore } from "~/stores/auth";

export default defineNuxtPlugin(() => {
  const auth = useAuthStore();
  auth.hydrate();

  if (auth.token) {
    // 비동기 검증. 완료 전 checked=false → 페이지는 빈 화면 유지(원본 App.jsx 동등).
    auth.fetchMe();
  } else {
    auth.checked = true;
  }

  function handleUnauthorized() {
    auth.logout();
  }

  window.addEventListener("auth:unauthorized", handleUnauthorized);
});
