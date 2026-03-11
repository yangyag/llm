export const PROMPT_CATALOG = [
  {
    key: "summarize",
    title: "문서 요약",
    summary: "긴 텍스트를 핵심만 정리하는 기본 데모",
    starterPrompt: "Summarize the following release notes in 5 bullets."
  },
  {
    key: "extract",
    title: "정보 추출",
    summary: "본문에서 구조화된 필드를 뽑아내는 데모",
    starterPrompt: "Extract the company name, product, and launch date from this paragraph."
  },
  {
    key: "rewrite",
    title: "문장 재작성",
    summary: "톤이나 길이를 바꿔 다시 쓰는 데모",
    starterPrompt: "Rewrite this email in a concise and confident tone."
  },
  {
    key: "qa",
    title: "질의응답",
    summary: "컨텍스트 기반 질문에 답하는 데모",
    starterPrompt: "Answer the user's question using only the provided policy text."
  }
];
