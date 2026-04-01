import { useEffect, useState } from "react";
import { getMe } from "./api";
import LoginPage from "./pages/LoginPage";
import WelcomePage from "./pages/WelcomePage";

function App() {
  const [auth, setAuth] = useState({ token: null, username: null, checked: false });

  useEffect(() => {
    const token = localStorage.getItem("auth_token");
    if (!token) {
      setAuth({ token: null, username: null, checked: true });
      return;
    }

    getMe(token)
      .then((result) => setAuth({ token, username: result.username, checked: true }))
      .catch(() => {
        localStorage.removeItem("auth_token");
        localStorage.removeItem("auth_username");
        setAuth({ token: null, username: null, checked: true });
      });
  }, []);

  function handleLoginSuccess(token, username) {
    setAuth({ token, username, checked: true });
  }

  function handleLogout() {
    localStorage.removeItem("auth_token");
    localStorage.removeItem("auth_username");
    setAuth({ token: null, username: null, checked: true });
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
