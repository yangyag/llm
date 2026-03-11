# Backend (`back/`)

익명 게시판 백엔드 모듈입니다. 이 디렉터리는 `llm` monorepo의 일부입니다.

## 역할
- REST API 제공
- PostgreSQL 게시글/답변 저장
- 본문 Base64 디코딩 및 비밀번호 검증
- AI 답변 생성 프록시(OpenAI / Anthropic / xAI)
- 백엔드 API 테스트(JUnit/MockMvc) 제공

## 전송 방식 주의
- `bodyBase64`는 보안용 필드가 아닙니다.
- 서버는 요청을 받은 뒤 Base64를 디코딩하고 평문 본문을 DB에 저장합니다.
- 실제 암호화가 필요하면 API 계약부터 별도로 바꿔야 합니다.

## API
- `GET /api/v1/health`
- `GET /api/v1/posts`
- `POST /api/v1/posts`
- `GET /api/v1/posts/{id}`
- `PUT /api/v1/posts/{id}`
- `DELETE /api/v1/posts/{id}`
- `POST /api/v1/posts/{id}/replies`
- `POST /api/v1/posts/{id}/ai-replies`
- `PUT /api/v1/posts/replies/{id}`
- `DELETE /api/v1/posts/replies/{id}`

## 요구 환경
- Java 25
- Docker / Docker Compose

## 로컬 실행
```bash
cd back
./gradlew clean build
APP_DB_HOST=localhost ./gradlew bootRun
```

접속:
- Health: `http://localhost:8082/api/v1/health`

## 통합 Docker 실행 (권장)
루트에서 front/back를 함께 기동:
```bash
docker compose up -d --build
```

## 백엔드 단독 Docker 실행
```bash
docker build -t llm-backend:local .
docker run --rm --name llm-back -p 8082:8080 \
  -e APP_CORS_ALLOWED_ORIGINS="http://localhost:8083,http://localhost:5174" \
  -e APP_DB_HOST="host.docker.internal" \
  -e APP_DB_PORT="5432" \
  -e APP_DB_NAME="yangyag" \
  -e APP_DB_USER="yangyag" \
  -e APP_DB_PASSWORD="yangyag1!" \
  --add-host=host.docker.internal:host-gateway \
  llm-backend:local
```

## CORS
- 기본 허용 Origin:
  - `http://localhost:5174`
  - `http://localhost:8083`
- 설정 키: `app.cors.allowed-origins`

## DB 설정 키
- `APP_DB_HOST`
- `APP_DB_PORT`
- `APP_DB_NAME`
- `APP_DB_USER`
- `APP_DB_PASSWORD`

## AI 설정 키
- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `ANTHROPIC_API_KEY`
- `ANTHROPIC_MODEL`
- `XAI_API_KEY`
- `XAI_MODEL`
