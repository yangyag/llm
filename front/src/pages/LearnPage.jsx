import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { generateDemoResponse } from "../api";
import { PROMPT_CATALOG } from "../data/promptCatalog";

const HISTORY_KEY = "llm-demo-history";

function LearnPage() {
  const navigate = useNavigate();
  const { templateKey } = useParams();
  const template = PROMPT_CATALOG.find((item) => item.key === templateKey) ?? PROMPT_CATALOG[0];
  const [prompt, setPrompt] = useState(template.starterPrompt);
  const [model, setModel] = useState("demo-model");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    setPrompt(template.starterPrompt);
    setResult(null);
    setError("");
  }, [template]);

  async function handleSubmit(event) {
    event.preventDefault();
    setIsSubmitting(true);
    setError("");

    try {
      const payload = await generateDemoResponse({ prompt, model });
      setResult(payload);

      const nextHistory = [
        {
          id: `${Date.now()}`,
          templateTitle: template.title,
          prompt: payload.prompt,
          model: payload.model
        },
        ...JSON.parse(window.localStorage.getItem(HISTORY_KEY) ?? "[]")
      ].slice(0, 6);

      window.localStorage.setItem(HISTORY_KEY, JSON.stringify(nextHistory));
    } catch (submitError) {
      setError(submitError.message);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="page">
      <header className="page-header">
        <button type="button" className="btn text" onClick={() => navigate("/library")}>
          템플릿
        </button>
        <button type="button" className="btn text" onClick={() => navigate("/letters")}>
          실행 기록
        </button>
      </header>

      <section className="section-card split-card">
        <div>
          <p className="eyebrow">{template.key}</p>
          <h2>{template.title}</h2>
          <p className="section-copy">{template.summary}</p>
        </div>
        <div className="status-chip">POST /api/v1/llm/demo</div>
      </section>

      <section className="demo-layout">
        <form className="section-card prompt-form" onSubmit={handleSubmit}>
          <label className="field">
            <span>Model</span>
            <input value={model} onChange={(event) => setModel(event.target.value)} />
          </label>

          <label className="field">
            <span>Prompt</span>
            <textarea
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              rows={10}
              placeholder="프롬프트를 입력하세요."
            />
          </label>

          <button type="submit" className="btn primary" disabled={isSubmitting}>
            {isSubmitting ? "전송 중..." : "데모 요청 보내기"}
          </button>

          {error ? <p className="error-text">{error}</p> : null}
        </form>

        <section className="section-card response-card">
          <p className="eyebrow">Response</p>
          {result ? (
            <>
              <h3>{result.model}</h3>
              <pre>{result.response}</pre>
              <p className="meta-text">Prompt: {result.prompt}</p>
            </>
          ) : (
            <p className="empty-state">아직 응답이 없습니다. 왼쪽 폼에서 첫 요청을 보내세요.</p>
          )}
        </section>
      </section>
    </main>
  );
}

export default LearnPage;
