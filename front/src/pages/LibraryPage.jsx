import { useNavigate } from "react-router-dom";
import { PROMPT_CATALOG } from "../data/promptCatalog";

function LibraryPage() {
  const navigate = useNavigate();

  return (
    <main className="page">
      <header className="page-header">
        <button type="button" className="btn text" onClick={() => navigate("/")}>
          홈
        </button>
        <button type="button" className="btn text" onClick={() => navigate("/letters")}>
          실행 기록
        </button>
      </header>

      <section className="section-card">
        <p className="eyebrow">Prompt Library</p>
        <h2>데모 템플릿</h2>
        <p className="section-copy">
          실제 LLM 제공자 연결 전에도 프론트 라우팅과 API 왕복을 검증할 수 있게,
          대표 사용 사례 4가지를 템플릿으로 넣어 두었습니다.
        </p>
      </section>

      <section className="template-grid">
        {PROMPT_CATALOG.map((template) => (
          <article key={template.key} className="template-card">
            <p className="template-key">{template.key}</p>
            <h3>{template.title}</h3>
            <p>{template.summary}</p>
            <button
              type="button"
              className="btn primary"
              onClick={() => navigate(`/learn/${template.key}`)}
            >
              이 템플릿으로 시작
            </button>
          </article>
        ))}
      </section>
    </main>
  );
}

export default LibraryPage;
