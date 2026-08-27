# llm monorepo

익명 게시글, 답변, 첨부파일, AI 답변 생성을 제공하는 게시판 저장소입니다. 단일 ZIP 청크 업로드 도구와 결과 게시글 다운로드 흐름도 함께 제공합니다.

## 아키텍처

```text
[Browser]
  -> Frontend (:8083)
      -> /api/v1/*
         -> Backend (container 8080)
             -> PostgreSQL (:5432)
```

- Frontend는 Nuxt 3 + Vue 3 + TypeScript + Pinia로 동작하며, 정적 생성 결과를 Nginx가 제공합니다.
- Backend는 Spring Boot 3.5.11, Java 25, PostgreSQL 18, Flyway를 사용합니다.
- 프론트는 관리자 로그인 후 Bearer 토큰으로 생성/수정/삭제와 AI 답변 API를 호출합니다.

## 빠른 시작

```bash
cp .env.example .env
docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
docker compose up -d --wait
```

- Frontend: `http://localhost:8083`
- Backend health: `http://localhost:8083/api/v1/health`

중지:

```bash
docker compose down
```

## 환경 변수

공통 compose는 `.env`에서 환경별 값을 읽습니다. 로컬과 EC2는 각각 자기 머신의 `.env`만 다르게 두고, 실제 `.env` 파일은 `.gitignore`로 관리 대상에서 제외합니다.

- `NUXT_PUBLIC_API_BASE`: 프론트 이미지를 빌드할 때 사용할 API base URL (빌드 타임에 번들로 고정되어 런타임 변경 불가, 변경 시 front 이미지 재빌드 필요)
- `LLM_BACK_IMAGE`, `LLM_FRONT_IMAGE`: compose가 실행할 Docker 이미지. 기본값은 로컬 태그 `llm-back:1.0`, `llm-front:1.0` (Hub 없음)
- `LLM_FRONT_PORT`: 프론트 컨테이너를 호스트에 공개할 포트
- `APP_CORS_ALLOWED_ORIGINS`: 허용 Origin 목록
- `APP_DB_HOST`, `APP_DB_PORT`, `APP_DB_NAME`, `APP_DB_USER`, `APP_DB_PASSWORD`, `APP_DB_SCHEMA`: 백엔드 DB 연결
- `APP_ATTACHMENTS_ROOT_PATH`: 첨부파일 저장 경로
- `APP_ATTACHMENTS_MAX_FILE_SIZE`, `APP_ATTACHMENTS_MAX_REQUEST_SIZE`: 업로드 제한
- `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE`: 청크 업로드 후 복원되는 최종 ZIP의 최대 크기
- `APP_UPLOAD_SESSIONS_ROOT_PATH`: 청크 업로드 세션 임시 파일 저장 경로
- `APP_UPLOAD_SESSIONS_EXPIRATION_MS`: 업로드 세션 만료 시간
- `APP_UPLOAD_SESSIONS_CLEANUP_FIXED_DELAY_MS`: 업로드 세션 정리 주기
- `APP_UPLOAD_SESSIONS_SECRET`: 업로드 세션 wire JSON 암호화 비밀키
- `LLM_UPLOAD_SESSIONS_SECRET`: `upload_zip_post.py` 전용 비밀키 오버라이드. 없으면 `APP_UPLOAD_SESSIONS_SECRET`를 사용합니다.
- `APP_JWT_SECRET`: 운영 필수 권장값, 반드시 설정해야 함
- `APP_JWT_EXPIRATION_MS`: JWT 만료 시간
- `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_API_BASE_URL`
- `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `ANTHROPIC_API_BASE_URL`
- `XAI_API_KEY`, `XAI_MODEL`, `XAI_API_BASE_URL`

주의:
- `APP_JWT_SECRET`은 코드상 fallback이 있어도 운영에서는 반드시 설정하세요.
- 기본 DB 값과 첨부파일 경로는 `.env.example`을 기준으로 맞춰 두면 됩니다.

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

- Health: `http://localhost:8080/api/v1/health`

### 단일 ZIP 청크 업로드

```bash
python3 upload_zip_post.py
```

- 현재 디렉터리에서 업로드할 `.zip` 파일 1개를 선택합니다.
- 기본 청크 크기는 `1398104` base64 문자이며 `--chunk-size-base64-chars` 또는 `LLM_UPLOAD_CHUNK_SIZE_BASE64_CHARS`로 바꿀 수 있습니다.
- 스크립트는 ZIP 바이트를 읽어서 base64로 인코딩한 뒤, 업로드 세션 요청/응답을 alias 키와 AES-GCM 암호문 JSON으로 전송합니다.
- 업로드 세션 JSON은 의미 있는 필드명이 아니라 내부 alias 키만 사용합니다. 외부에서 payload를 봐도 필드 의미를 추론하기 어렵게 설계되어 있습니다.
- `APP_UPLOAD_SESSIONS_SECRET`와 `LLM_UPLOAD_SESSIONS_SECRET`는 backend와 script가 같은 값을 써야 합니다.
- 업로드 중간에 끊기면 `<archive>.llm-upload-session.json` 사이드카를 사용해 누락 청크만 이어올립니다. 이전 byte-size 사이드카는 새 포맷과 호환되지 않으므로 새 세션으로 다시 생성됩니다.
- `LLM_API_BASE_URL` 또는 `NUXT_PUBLIC_API_BASE`를 우선 사용하고, `LLM_JWT_TOKEN`이 없으면 `LLM_USERNAME` / `LLM_PASSWORD`로 로그인합니다.
- 모든 청크를 업로드한 뒤 `finalize`가 호출되면 서버가 원본 ZIP 바이트를 복원하고 게시글을 자동 생성합니다.
- 게시판 웹 UI는 생성된 결과 게시글의 조회와 다운로드만 담당합니다.

## 검증

```bash
cd back && ./gradlew clean test
cd front && npm run typecheck && npm run build
```

통합 확인 (외부 네트워크 `auto_default` 필요):

```bash
docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
docker compose up -d --wait
```

로컬 소스 기준으로 이미지를 다시 만들 때:

```bash
cd front && npm ci && npm run build && cd ..
docker compose --profile build build back-build front-build
docker compose up -d --wait
```

## EC2 배포

EC2 배포도 루트 [`docker-compose.yml`](/home/yangyag/llm/docker-compose.yml) 하나를 사용합니다. 운영 파일은 EC2의 `/home/ubuntu/llm` 아래에서 관리하고, 환경값은 `/home/ubuntu/llm/.env`에 둡니다. 시작점은 `/home/yangyag/llm/.env.example`입니다.

사용 이미지:
- `llm-front:1.0` (Windows에서 빌드 후 tar로 EC2 `docker load`. Hub 없음)
- `llm-back:1.0` (Windows에서 빌드 후 tar로 EC2 `docker load`. Hub 없음)

준비 절차:

EC2에서 `/home/ubuntu/llm/.env`를 `.env.example` 형식으로 작성하고 운영값으로 바꿉니다.

배포 절차:

```bash
cd /home/ubuntu/llm
docker info >/dev/null
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml ps
```

운영 메모:
- 외부 HTTPS는 ALB 또는 리버스 프록시에서 종료하고, front/back 컨테이너는 내부 HTTP로만 통신합니다.
- 외부 공개는 front/public proxy 포트만 허용하고, backend/DB 포트는 사설망 또는 방화벽으로만 접근되게 유지합니다.
- EC2에서는 `/home/ubuntu/llm/.env` 하나만 관리하면 됩니다.
- EC2 운영 시 기대값은 다음과 같습니다.
  - `APP_DB_HOST=yangyag-postgres`
  - `APP_DB_NAME=llm`
  - `APP_DB_SCHEMA=llm`
  - `APP_CORS_ALLOWED_ORIGINS=https://<your-domain>`
  - `LLM_API_BASE_URL=https://<your-domain>`
  - `APP_JWT_SECRET`와 `APP_UPLOAD_SESSIONS_SECRET`는 배포 전에 반드시 설정해야 합니다.
  - `APP_UPLOAD_SESSIONS_ROOT_PATH=/var/lib/llm/upload-sessions`를 반드시 설정합니다. 없으면 백엔드가 `${java.io.tmpdir}/llm-upload-sessions`로 fallback해 upload-session volume이 무시되고 ZIP finalize 청크가 유실될 수 있습니다.
  - `APP_UPLOAD_SESSIONS_SECRET`는 backend와 `upload_zip_post.py`가 같은 값을 쓰도록 맞춰야 하며, `LLM_UPLOAD_SESSIONS_SECRET`를 바꾸면 스크립트도 같은 값을 사용해야 합니다.
- EC2 운영 DB는 `yangyag-postgres` 컨테이너의 전용 database `llm`(schema `llm`)을 사용합니다. 예전처럼 `auto` database에 스키마만 얹지 않습니다.
- `yangyag-postgres`는 LLM compose 외부 컨테이너이므로 `auto_default` 네트워크에 연결되어 있어야 합니다. EC2에서는 `auto_default`를 수동 생성하지 마세요 — 이 네트워크는 compose 프로젝트 `auto` 소유이며, 빈 네트워크를 만들면 DB 누락을 가립니다. 배포 전 `docker network inspect auto_default`로 존재를 확인합니다.

## 프로젝트 규칙

### 디렉터리
- `front/`: UI, 라우팅, 상태관리, 프론트 Docker 빌드
- `back/`: API, 도메인, 테스트, 정적 자산, 백엔드 Docker 빌드
- 루트: 통합 실행/문서(`docker-compose.yml`, `.env.example`, `README.md`)

### Git
- 저장소: `git@github.com:yangyag/llm.git`
- `git commit` 메시지는 한글로 작성합니다.

### 배포
- Docker Hub를 쓰지 않습니다. Windows에서 이미지를 만들고 tar로 EC2에 `docker load`합니다.
- 프론트: `.\aws\deploy-front.ps1` (`llm-front:1.0`)
- 백엔드: `.\aws\deploy-back.ps1` (`llm-back:1.0`)

### 품질 게이트
- 테스트 원칙: 백엔드 API JUnit(`MockMvc`) 중심
- 백엔드 기능 변경 시 `back` 테스트를 반드시 통과시킵니다.
- 프론트 변경 시 `front` 빌드를 반드시 통과시킵니다.
- 통합 영향이 있으면 루트 기준 컨테이너 실행과 헬스 체크를 확인합니다.
- 최소 검증 명령은 위 [검증](#검증) 섹션을 따릅니다.

## 현재 기능 범위

- 관리자 로그인
- 관리자 전용 사용자 관리(사용자 추가/수정/삭제, ADMIN/USER 레벨)
- 게시글 목록, 상세, 작성, 수정, 삭제 (수정/삭제는 작성자 본인 또는 ADMIN만)
- 답변 작성, 수정, 삭제 (댓글도 작성자 본인 또는 ADMIN만 수정/삭제)
- 게시글당 첨부파일 다중 업로드(일반 게시글 최대 5개), 개별 삭제, 다운로드
- 단일 ZIP 청크 업로드 후 자동 게시글 생성 및 ZIP 다운로드
- AI 답변 생성(OpenAI / Claude / Grok)
- 검색
- 배치 삭제

## 주의 사항

- 관리자 로그인 ID는 영문과 숫자만 허용합니다.
- 일반 게시글 본문은 비워둘 수 있습니다. 본문을 보낼 때는 게시글과 답변 모두 프론트에서 Base64로 인코딩해 전송하고, 서버는 이를 디코딩해 저장합니다.
- 단일 ZIP 청크 업로드는 `upload_zip_post.py`를 통해 처리합니다.
- 업로드 세션 wire JSON은 alias 키와 AES-GCM 암호문만 사용합니다. 파일명도 실제 값이 암호화되어 전송됩니다.
- Base64는 보안 기능이 아닙니다.
- 첨부파일은 일반 게시글당 최대 5개까지 허용합니다. 업로드 세션으로 생성된 파일 변환 게시글은 첨부 1개(원본 ZIP)만 가집니다.
- 최대 업로드 크기는 기본 `100MB`입니다.
- 게시글 목록, 상세, 첨부파일 다운로드는 로그인 없이 공개 접근할 수 있으며, 이 동작은 유지합니다.
- 게시글/댓글 작성 시 작성자(`posts.author_username` / `post_replies.author_username`)가 기록됩니다. 수정/삭제는 **작성자 본인 또는 ADMIN**만 가능하며, 작성자가 없는 레거시 글/댓글은 ADMIN만 수정/삭제할 수 있습니다. 게시글 일괄 삭제도 같은 권한 규칙이 적용됩니다.
