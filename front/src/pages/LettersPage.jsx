import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getHealth } from "../api";
import { PROMPT_CATALOG } from "../data/promptCatalog";

const HISTORY_KEY = "llm-demo-history";

function LettersPage() {
  const navigate = useNavigate();
  const [health, setHealth] = useState(null);
  const [history, setHistory] = useState([]);

  useEffect(() => {
    const stored = window.localStorage.getItem(HISTORY_KEY);
    if (stored) {
      setHistory(JSON.parse(stored));
    }

    getHealth()
      .then(setHealth)
      .catch(() => {
        setHealth({ status: "DOWN" });
      });
  }, []);

  return (
    <main className="page">
      <header className="page-header">
        <button type="button" className="btn text" onClick={() => navigate("/library")}>
          템플릿
        </button>
        <button type="button" className="btn text" onClick={() => navigate("/learn/summarize")}>
          데모
        </button>
      </header>

      <section className="section-card split-card">
        <div>
          <p className="eyebrow">Backend Health</p>
          <h2>{health?.status ?? "CHECKING"}</h2>
          <p className="section-copy">
            백엔드는 `/api/v1/health`와 `/api/v1/llm/demo` 두 축으로 최소 구성되어 있습니다.
          </p>
        </div>
        <div className="status-chip">{health?.status ?? "..."}</div>
      </section>

      <section className="section-card">
        <p className="eyebrow">Available Flows</p>
        <div className="mini-grid">
          {PROMPT_CATALOG.map((template) => (
            <button
              key={template.key}
              type="button"
              className="mini-card"
              onClick={() => navigate(`/learn/${template.key}`)}
            >
              <strong>{template.title}</strong>
              <span>{template.summary}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="section-card">
        <p className="eyebrow">Recent Runs</p>
        {history.length === 0 ? (
          <p className="empty-state">아직 실행 기록이 없습니다. 데모 화면에서 첫 요청을 보내보세요.</p>
        ) : (
          <div className="history-list">
            {history.map((item) => (
              <article key={item.id} className="history-card">
                <strong>{item.templateTitle}</strong>
                <p>{item.prompt}</p>
                <span>{item.model}</span>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}

export default LettersPage;
