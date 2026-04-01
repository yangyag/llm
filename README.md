# llm monorepo

익명 게시글, 답변, 첨부파일, AI 답변 생성을 제공하는 게시판 저장소입니다. 루트에서는 통합 실행과 운영 배포만 다룹니다.

## 아키텍처

```text
[Browser]
  -> Frontend (:8083)
      -> /api/v1/*
         -> Backend (:8082, container 8080)
             -> PostgreSQL (:5432)
```

- Frontend는 Vite + React로 동작합니다.
- Backend는 Spring Boot 3.5.11, Java 25, PostgreSQL 18, Flyway를 사용합니다.
- 프론트는 관리자 로그인 후 Bearer 토큰으로 생성/수정/삭제, AI 답변, 파일 변환 API를 호출합니다.

## 빠른 시작

```bash
cp llm.env.example llm.env
docker compose --env-file llm.env up -d --build
```

- Frontend: `http://localhost:8083`
- Backend health: `http://localhost:8082/api/v1/health`

중지:

```bash
docker compose down
```

## 환경 변수

루트 `llm.env`에서 관리합니다.

- `VITE_API_BASE_URL`: 프론트에서 사용할 API base URL
- `APP_CORS_ALLOWED_ORIGINS`: 허용 Origin 목록
- `APP_DB_HOST`, `APP_DB_PORT`, `APP_DB_NAME`, `APP_DB_USER`, `APP_DB_PASSWORD`: 백엔드 DB 연결
- `APP_ATTACHMENTS_ROOT_PATH`: 첨부파일 저장 경로
- `APP_ATTACHMENTS_MAX_FILE_SIZE`, `APP_ATTACHMENTS_MAX_REQUEST_SIZE`: 업로드 제한
- `APP_JWT_SECRET`: 운영 필수 권장값, 반드시 설정해야 함
- `APP_JWT_EXPIRATION_MS`: JWT 만료 시간
- `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_API_BASE_URL`
- `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `ANTHROPIC_API_BASE_URL`
- `XAI_API_KEY`, `XAI_MODEL`, `XAI_API_BASE_URL`
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`: EC2 DB 컨테이너용

주의:
- `APP_JWT_SECRET`은 코드상 fallback이 있어도 운영에서는 반드시 설정하세요.
- 기본 DB 값과 첨부파일 경로는 `llm.env.example`을 기준으로 맞춰 두면 됩니다.

## 개별 개발

### Frontend

```bash
cd front
npm ci
npm run dev
```

- 기본 접속: `http://localhost:5174`

### Backend

```bash
cd back
APP_DB_HOST=localhost ./gradlew bootRun
```

- Health: `http://localhost:8082/api/v1/health`

## 검증

```bash
cd back && ./gradlew clean test
cd front && npm run build
```

통합 확인:

```bash
docker compose --env-file llm.env up -d --build
```

## EC2 배포

EC2 배포는 [`aws/docker-compose.ec2.yml`](/home/yangyag/llm/aws/docker-compose.ec2.yml) 기준으로 동작합니다.

사용 이미지:
- `yangyag2/llm-front:latest`
- `yangyag2/llm-back:latest`
- `yangyag2/llm-db:18`

기본 흐름:

```bash
docker compose -f aws/docker-compose.ec2.yml up -d
```

운영 메모:
- 외부 공개는 보통 `8083`만 열고, `8080`, `8082`, `5432`는 외부에 열지 않습니다.
- EC2에서는 `/home/ubuntu/llm.env` 하나만 관리하면 됩니다.
- 백엔드는 내부 `llm-db:5432`로만 접속합니다.

## 현재 기능 범위

- 관리자 로그인
- 게시글 목록, 상세, 작성, 수정, 삭제
- 답변 작성, 수정, 삭제
- 게시글당 첨부파일 1개 업로드/교체/삭제/다운로드
- 파일 변환 요청 게시글 작성 및 ZIP 변환
- AI 답변 생성(OpenAI / Claude / Grok)
- 검색
- 배치 삭제

## 주의 사항

- 관리자 로그인 ID는 영문과 숫자만 허용합니다.
- 일반 게시글 본문과 답변 본문은 프론트에서 Base64로 인코딩해 전송하고, 서버는 이를 디코딩해 저장합니다.
- `FILE_CONVERSION_REQUEST` 모드는 ZIP의 Base64 문자열을 그대로 저장하고, 상세 화면에서 서버가 ZIP 파일로 변환해 첨부 슬롯에 저장합니다.
- Base64는 보안 기능이 아닙니다.
- 첨부파일은 게시글당 1개만 허용합니다.
- 최대 업로드 크기는 기본 `100MB`입니다.
- 첨부파일은 게시글 상세에서 누구나 다운로드할 수 있습니다.
