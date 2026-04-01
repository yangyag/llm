import { useState } from "react";
import { login } from "../api";

const usernamePatternMessage = "아이디는 영문과 숫자만 입력할 수 있습니다.";

function sanitizeUsername(value) {
  return value.replace(/[^A-Za-z0-9]/g, "");
}

function isValidUsername(value) {
  return /^[A-Za-z0-9]+$/.test(value);
}

function LoginPage({ onLoginSuccess }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [usernameHint, setUsernameHint] = useState("");

  function showUsernameHint() {
    setUsernameHint(usernamePatternMessage);
  }

  function updateUsername(nextValue) {
    const sanitizedValue = sanitizeUsername(nextValue);

    setUsername(sanitizedValue);
    setUsernameHint(nextValue === sanitizedValue ? "" : usernamePatternMessage);

    if (error) {
      setError("");
    }
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError("");
    const currentUsername = sanitizeUsername(username);

    if (!isValidUsername(currentUsername)) {
      setUsername(currentUsername);
      showUsernameHint();
      setError(usernamePatternMessage);
      return;
    }

    setSubmitting(true);
    setUsername(currentUsername);

    try {
      const result = await login(currentUsername, password);
      localStorage.setItem("auth_token", result.token);
      localStorage.setItem("auth_username", result.username);
      onLoginSuccess(result.token, result.username);
    } catch (err) {
      if (err.status === 401) {
        setError("아이디 또는 비밀번호가 올바르지 않습니다.");
      } else if (err.status === 400 && err.code === "INVALID_REQUEST") {
        setError(usernamePatternMessage);
      } else {
        setError(err.message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleUsernamePaste(event) {
    const pastedText = event.clipboardData.getData("text");
    const sanitizedText = sanitizeUsername(pastedText);

    if (pastedText === sanitizedText) {
      return;
    }

    event.preventDefault();

    const input = event.currentTarget;
    const currentValue = input.value;
    const selectionStart = input.selectionStart ?? currentValue.length;
    const selectionEnd = input.selectionEnd ?? currentValue.length;
    const nextValue = `${currentValue.slice(0, selectionStart)}${sanitizedText}${currentValue.slice(selectionEnd)}`;

    setUsername(nextValue);
    showUsernameHint();

    if (error) {
      setError("");
    }
  }

  function handleUsernameChange(event) {
    const nextValue = event.target.value;

    if (event.nativeEvent.isComposing) {
      setUsername(nextValue);
      return;
    }

    updateUsername(nextValue);
  }

  function handleUsernameCompositionEnd(event) {
    updateUsername(event.target.value);
  }

  function handleUsernameBlur(event) {
    updateUsername(event.target.value);
  }

  return (
    <main className="board-page">
      <section className="board-shell login-shell">
        <header className="board-header">
          <div>
            <p className="eyebrow">Admin Login</p>
            <h1>관리자 로그인</h1>
          </div>
        </header>

        <section className="card login-card">
          <form className="form-grid" onSubmit={handleSubmit}>
            <label className="field">
              <span>아이디</span>
              <input
                value={username}
                onChange={handleUsernameChange}
                onCompositionEnd={handleUsernameCompositionEnd}
                onBlur={handleUsernameBlur}
                onPaste={handleUsernamePaste}
                autoFocus
                autoComplete="username"
                inputMode="text"
                pattern="[A-Za-z0-9]+"
                title={usernamePatternMessage}
                required
              />
            </label>
            {usernameHint ? <p className="field-hint">{usernameHint}</p> : null}
            <label className="field">
              <span>비밀번호</span>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
              />
            </label>
            {error ? <p className="message-banner error">{error}</p> : null}
            <button type="submit" className="primary-button wide-button" disabled={submitting}>
              {submitting ? "로그인 중..." : "로그인"}
            </button>
          </form>
        </section>
      </section>
    </main>
  );
}

export default LoginPage;
