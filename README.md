# llm monorepo

익명 게시글과 답변을 작성할 수 있는 게시판 저장소입니다.

## 디렉터리 구조
```text
llm/
  front/                  # React 게시판 UI
  back/                   # Spring Boot 게시판 API
  docker-compose.yml      # 통합 실행
  .env.example
  README.md
  AGENTS.md
```

## 통합 실행
```bash
cp .env.example .env
cp ai-keys.env.example ai-keys.env
docker compose up -d --build
```

접속:
- Frontend: `http://localhost:8083`
- Backend health: `http://localhost:8082/api/v1/health`

중지:
```bash
docker compose down
```

## 현재 범위
- 익명 게시글 작성, 목록 조회, 상세 조회
- 답변 작성/수정/삭제
- AI 답변 생성(GPT / Claude / Grok)
- 게시글 수정/삭제
- 게시글/답변 본문은 프론트에서 Base64 인코딩 후 전송
- 서버는 디코딩 후 PostgreSQL 18에 저장

## 전송 방식 주의
- Base64는 보안 기능이 아닙니다.
- 현재 구조는 본문을 전송 직전에 문자열로 포장하는 수준이며, 서버에는 디코딩된 평문이 저장됩니다.
- 실제 기밀성이 필요해지면 Base64가 아니라 별도의 암호화 설계를 추가해야 합니다.

## DB 연결
- 기존 Docker PostgreSQL 컨테이너 재사용
- 포트: `5432`
- DB: `yangyag`
- User: `yangyag`
- Password: `yangyag1!`
- `llm-back` 컨테이너는 `host.docker.internal:5432` 경유로 접속

## 개별 개발
### Frontend
```bash
cd front
npm ci
npm run dev
```

기본 접속:
- `http://localhost:5174`

### Backend
```bash
cd back
APP_DB_HOST=localhost ./gradlew bootRun
```

## 검증 명령
```bash
cd back && ./gradlew clean test
cd front && npm run build
```

## AI 설정 파일
- 루트 `ai-keys.env`에 API 키를 입력합니다.
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `XAI_API_KEY`
