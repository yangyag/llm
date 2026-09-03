# AI 답변 연동

> 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. `POST /api/v1/posts/{id}/ai-replies`는 410 `AI_REPLY_DISABLED`를 반환합니다. 아래 내용은 종료 이전 동작의 기록이며, 관련 클래스·컬럼·DTO는 레거시 조회/보호용 잔재로 유지됩니다.

백엔드는 게시글 본문을 바탕으로 AI 답변을 생성합니다. 지원 provider는 `GPT`, `CLAUDE`, `GROK`입니다.

## 코드 위치

| 파일 | 역할 |
| --- | --- |
| `back/src/main/java/com/llm/app/board/ai/AiProvider.java` | provider enum과 입력값 변환 |
| `back/src/main/java/com/llm/app/board/ai/ExternalAiReplyGenerator.java` | 외부 API 호출 구현 |
| `back/src/main/java/com/llm/app/board/controller/BoardPostController.java` | `/ai-replies` endpoint |
| `back/src/main/java/com/llm/app/board/service/BoardService.java` | 게시글 제약과 답변 저장 |

## Provider별 설정

| Provider | API key | Model | Base URL | Endpoint |
| --- | --- | --- | --- | --- |
| GPT | `OPENAI_API_KEY` | `OPENAI_MODEL` | `OPENAI_API_BASE_URL` | `/chat/completions` |
| Claude | `ANTHROPIC_API_KEY` | `ANTHROPIC_MODEL` | `ANTHROPIC_API_BASE_URL` | `/messages` |
| Grok | `XAI_API_KEY` | `XAI_MODEL` | `XAI_API_BASE_URL` | `/chat/completions` |

Claude 호출에는 `anthropic-version: 2023-06-01` header를 사용합니다.

## 요청 방식

```http
POST /api/v1/posts/{id}/ai-replies
Authorization: Bearer <jwt>
Content-Type: application/json
```

```json
{
  "provider": "GPT"
}
```

허용 provider 값:

- `GPT`
- `CLAUDE`
- `GROK`

`AiProvider.from`은 enum 이름과 label을 대소문자 구분 없이 받아들입니다.

## 시스템 프롬프트

백엔드는 AI에게 익명 게시판 답변 작성 도우미로 동작하라고 지시합니다. 응답은 한국어, 간결한 평문으로 기대합니다.

## 저장 방식

생성된 답변은 `post_replies`에 저장됩니다.

| 컬럼 | 값 |
| --- | --- |
| `body` | AI 응답 본문 |
| `is_ai` | `true` |
| `ai_provider` | `GPT`, `Claude`, `Grok` label |

AI 답변은 수정/삭제할 수 없습니다.

## 제한 사항

- `FILE_CONVERSION_REQUEST` 게시글에는 AI 답변을 만들 수 없습니다.
- API key가 비어 있으면 해당 provider는 `AI_PROVIDER_NOT_CONFIGURED` 오류를 반환합니다.
- 외부 API가 오류를 반환하면 `AI_REPLY_GENERATION_FAILED` 오류를 반환합니다.
- GPT/Grok 응답은 `choices[0].message.content`에서 추출합니다.
- Claude 응답은 `content[]` 중 `type=text`인 값을 줄바꿈으로 이어 붙입니다.

## 운영 점검

현재 EC2 확인값:

```text
OPENAI_MODEL=gpt-5.5
ANTHROPIC_MODEL=claude-opus-4-7
XAI_MODEL=grok-4.3
```

모델명은 운영 `.env` 값이 이미지 기본값보다 우선합니다. 변경 후에는 컨테이너 환경과 실제 응답을 확인합니다.

```bash
cd /home/ubuntu/llm
grep -E '^(OPENAI_MODEL|ANTHROPIC_MODEL|XAI_MODEL)=' .env
docker inspect llm-back --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E '^(OPENAI_MODEL|ANTHROPIC_MODEL|XAI_MODEL)='
docker logs --tail 100 llm-back
```

secret 값은 `grep` 대상에서 제외합니다.

## 변경 절차

1. 운영 `.env`에서 model 또는 base URL을 변경합니다.
2. `llm-back`을 재기동합니다.
3. `docker inspect`로 컨테이너 환경이 반영됐는지 확인합니다.
4. 관리자 UI에서 테스트 게시글을 만들고 각 provider 답변 생성을 확인합니다.
5. 오류가 나면 백엔드 로그의 status code와 provider별 설정을 확인합니다.
