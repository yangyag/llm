// 로그인 세션 유휴 타임아웃.
// 1시간 비활동 시 자동 로그아웃. activity 이벤트 capture 단계, throttle 5초,
// visibilitychange/focus 시 즉시 재평가, localStorage 시드 보존(리로드 시 리셋 방지).
import { onMounted, onUnmounted } from "vue";
import { LAST_ACTIVITY_KEY, useAuthStore } from "~/stores/auth";

const IDLE_TIMEOUT_MS = 60 * 60 * 1000;
const ACTIVITY_EVENTS = ["mousedown", "keydown", "scroll", "touchstart"];

export function useIdleTimeout(): void {
  const auth = useAuthStore();
  let timerId: ReturnType<typeof setTimeout> | undefined;
  let lastWrite = 0;

  function logout() {
    if (!auth.token) {
      return;
    }
    auth.logout();
    void navigateTo("/login", { replace: true });
  }

  function checkIdle() {
    window.clearTimeout(timerId);
    const last = Number(localStorage.getItem(LAST_ACTIVITY_KEY)) || Date.now();
    const remaining = last + IDLE_TIMEOUT_MS - Date.now();
    if (remaining <= 0) {
      logout();
      return;
    }
    // 남은 시간만큼만 재예약 → 활동이 있었으면 자연히 데드라인이 미뤄진다.
    timerId = window.setTimeout(checkIdle, remaining);
  }

  function markActivity() {
    const now = Date.now();
    const storedLastActivity = Number(localStorage.getItem(LAST_ACTIVITY_KEY));
    // 절전 등으로 타이머 실행이 늦었다면 첫 활동이 만료 시각을 덮어쓰기 전에 로그아웃한다.
    if (storedLastActivity && storedLastActivity + IDLE_TIMEOUT_MS <= now) {
      logout();
      return;
    }
    // 스크롤 등 빈번한 이벤트로 인한 과도한 저장 방지 (1시간 기준 5초 오차 무시 가능).
    if (now - lastWrite < 5000) {
      return;
    }
    lastWrite = now;
    localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
  }

  function handleVisible() {
    // 절전/탭 복귀 시 setTimeout이 늦게 깨어날 수 있으므로 즉시 재평가.
    if (document.visibilityState === "visible") {
      checkIdle();
    }
  }

  onMounted(() => {
    if (!auth.token) {
      return;
    }
    // 저장된 마지막 활동 시각 보존 — 리로드/탭 복원이 유휴 데드라인을 리셋하지 않도록.
    // 값이 없을 때(최초 진입 등)만 현재 시각으로 시드. 로그인 시점 시드는 auth.setAuth에서 처리.
    if (!localStorage.getItem(LAST_ACTIVITY_KEY)) {
      localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
    }
    checkIdle();
    // capture 단계로 등록해 내부 스크롤 컨테이너(scroll은 버블링 안 함)의 활동도 포착.
    ACTIVITY_EVENTS.forEach((eventName) => window.addEventListener(eventName, markActivity, true));
    document.addEventListener("visibilitychange", handleVisible);
    window.addEventListener("focus", handleVisible);
  });

  onUnmounted(() => {
    window.clearTimeout(timerId);
    ACTIVITY_EVENTS.forEach((eventName) => window.removeEventListener(eventName, markActivity, true));
    document.removeEventListener("visibilitychange", handleVisible);
    window.removeEventListener("focus", handleVisible);
  });
}
