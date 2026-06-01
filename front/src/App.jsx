import { useEffect, useState } from "react";
import { getMe } from "./api";
import LoginPage from "./pages/LoginPage";
import PublicPostPage from "./pages/PublicPostPage";
import WelcomePage from "./pages/WelcomePage";

// 마지막 활동 후 이 시간 동안 아무 동작이 없으면 자동 로그아웃 (유휴 타임아웃, 1시간)
const IDLE_TIMEOUT_MS = 60 * 60 * 1000;
// 유휴 판단의 기준이 되는 사용자 활동 이벤트
const ACTIVITY_EVENTS = ["mousedown", "keydown", "scroll", "touchstart"];
const LAST_ACTIVITY_KEY = "auth_last_activity";

function clearStoredAuth() {
  localStorage.removeItem("auth_token");
  localStorage.removeItem("auth_username");
  localStorage.removeItem(LAST_ACTIVITY_KEY);
}

function App() {
  const [auth, setAuth] = useState({ token: null, username: null, checked: false });
  const publicPostMatch = window.location.pathname.match(/^\/posts\/(\d+)$/);

  useEffect(() => {
    const token = localStorage.getItem("auth_token");
    if (!token) {
      setAuth({ token: null, username: null, checked: true });
      return;
    }

    getMe(token)
      .then((result) => setAuth({ token, username: result.username, checked: true }))
      .catch(() => {
        clearStoredAuth();
        setAuth({ token: null, username: null, checked: true });
      });
  }, []);

  // 자동 로그아웃: 유휴 타임아웃 + 서버의 토큰 거부(401) 처리.
  // 로그인 상태(auth.token)일 때만 활성화하고, 로그아웃/언마운트 시 정리한다.
  useEffect(() => {
    if (!auth.token) {
      return undefined;
    }

    let timerId;
    let lastWrite = 0;

    function logout() {
      clearStoredAuth();
      setAuth({ token: null, username: null, checked: true });
    }

    function checkIdle() {
      window.clearTimeout(timerId);
      const last = Number(localStorage.getItem(LAST_ACTIVITY_KEY)) || Date.now();
      const remaining = last + IDLE_TIMEOUT_MS - Date.now();
      if (remaining <= 0) {
        logout();
        return;
      }
      // 남은 시간만큼만 재예약 → 활동이 있었으면 자연히 데드라인이 미뤄진다
      timerId = window.setTimeout(checkIdle, remaining);
    }

    function markActivity() {
      const now = Date.now();
      // 스크롤 등 빈번한 이벤트로 인한 과도한 저장을 막기 위해 throttle (1시간 기준 5초 오차 무시 가능)
      if (now - lastWrite < 5000) {
        return;
      }
      lastWrite = now;
      localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
    }

    function handleVisible() {
      // 절전/탭 복귀 시 setTimeout이 늦게 깨어날 수 있으므로 즉시 재평가
      if (document.visibilityState === "visible") {
        checkIdle();
      }
    }

    // 저장된 마지막 활동 시각을 보존한다 — 리로드/탭 복원이 유휴 데드라인을 리셋하지 않도록.
    // 값이 없을 때(최초 진입 등)만 현재 시각으로 시드한다. 로그인 시점 시드는 handleLoginSuccess에서 처리.
    if (!localStorage.getItem(LAST_ACTIVITY_KEY)) {
      localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
    }
    checkIdle();

    // capture 단계로 등록해 내부 스크롤 컨테이너(scroll은 버블링 안 함)의 활동도 포착
    ACTIVITY_EVENTS.forEach((eventName) => window.addEventListener(eventName, markActivity, true));
    document.addEventListener("visibilitychange", handleVisible);
    window.addEventListener("focus", handleVisible);
    window.addEventListener("auth:unauthorized", logout);

    return () => {
      window.clearTimeout(timerId);
      ACTIVITY_EVENTS.forEach((eventName) => window.removeEventListener(eventName, markActivity, true));
      document.removeEventListener("visibilitychange", handleVisible);
      window.removeEventListener("focus", handleVisible);
      window.removeEventListener("auth:unauthorized", logout);
    };
  }, [auth.token]);

  function handleLoginSuccess(token, username) {
    // 새 로그인 시점을 유휴 타이머의 기준 활동으로 시드 (이전 세션의 잔여 값 무시)
    localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
    setAuth({ token, username, checked: true });
  }

  function handleLogout() {
    clearStoredAuth();
    setAuth({ token: null, username: null, checked: true });
  }

  if (publicPostMatch) {
    return <PublicPostPage postId={Number.parseInt(publicPostMatch[1], 10)} />;
  }

  if (!auth.checked) {
    return null;
  }

  if (!auth.token) {
    return <LoginPage onLoginSuccess={handleLoginSuccess} />;
  }

  return <WelcomePage authToken={auth.token} authUsername={auth.username} onLogout={handleLogout} />;
}

export default App;
