# llm monorepo

익명 게시글, 답변, 첨부파일, AI 답변 생성을 제공하는 게시판 저장소입니다. 단일 ZIP 청크 업로드 도구와 결과 게시글 다운로드 흐름도 함께 제공합니다.

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
- 프론트는 관리자 로그인 후 Bearer 토큰으로 생성/수정/삭제와 AI 답변 API를 호출합니다.

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

로컬 루트 compose는 `llm.env.example`을 복사해 만든 `llm.env`에서 관리합니다. EC2는 별도 예시 파일을 사용합니다.

- `VITE_API_BASE_URL`: 프론트에서 사용할 API base URL
- `APP_CORS_ALLOWED_ORIGINS`: 허용 Origin 목록
- `APP_DB_HOST`, `APP_DB_PORT`, `APP_DB_NAME`, `APP_DB_USER`, `APP_DB_PASSWORD`: 백엔드 DB 연결
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
- `LLM_API_BASE_URL` 또는 `VITE_API_BASE_URL`을 우선 사용하고, `LLM_JWT_TOKEN`이 없으면 `LLM_USERNAME` / `LLM_PASSWORD`로 로그인합니다.
- 모든 청크를 업로드한 뒤 `finalize`가 호출되면 서버가 원본 ZIP 바이트를 복원하고 게시글을 자동 생성합니다.
- 게시판 웹 UI는 생성된 결과 게시글의 조회와 다운로드만 담당합니다.

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

EC2 배포는 [`aws/docker-compose.ec2.yml`](/home/yangyag/llm/aws/docker-compose.ec2.yml)와 `/home/ubuntu/llm.env`를 기준으로 동작합니다. 시작점은 `/home/yangyag/llm/aws/llm.ec2.env.example`입니다.

사용 이미지:
- `yangyag2/llm-front:latest`
- `yangyag2/llm-back:latest`
- `yangyag2/llm-db:18`

준비 절차:

```bash
cp /home/yangyag/llm/aws/llm.ec2.env.example /home/ubuntu/llm.env
```

기본 흐름:

```bash
cd /home/yangyag/llm/aws
docker compose --env-file /home/ubuntu/llm.env -f docker-compose.ec2.yml pull
docker compose --env-file /home/ubuntu/llm.env -f docker-compose.ec2.yml up -d --wait --wait-timeout 180
docker compose --env-file /home/ubuntu/llm.env -f docker-compose.ec2.yml ps
```

스크립트로 순차 배포:

```bash
cd /home/yangyag/llm/aws
chmod +x deploy-ec2.sh
./deploy-ec2.sh
# 옵션 예시: ./deploy-ec2.sh --env-file /home/ubuntu/llm.env --wait-timeout 180
```

운영 메모:
- 외부 HTTPS는 ALB 또는 리버스 프록시에서 종료하고, front/back 컨테이너는 내부 HTTP로만 통신합니다.
- 외부 공개는 front/public proxy 포트만 허용하고, backend/DB 포트는 사설망 또는 방화벽으로만 접근되게 유지합니다.
- EC2에서는 `/home/ubuntu/llm.env` 하나만 관리하면 됩니다.
- EC2 운영 시 기대값은 다음과 같습니다.
  - `APP_DB_HOST=db`
  - `APP_CORS_ALLOWED_ORIGINS=https://<your-domain>`
  - `LLM_API_BASE_URL=https://<your-domain>`
  - `APP_JWT_SECRET`와 `APP_UPLOAD_SESSIONS_SECRET`는 배포 전에 반드시 설정해야 합니다.
  - `APP_UPLOAD_SESSIONS_SECRET`는 backend와 `upload_zip_post.py`가 같은 값을 쓰도록 맞춰야 하며, `LLM_UPLOAD_SESSIONS_SECRET`를 바꾸면 스크립트도 같은 값을 사용해야 합니다.
- 백엔드는 내부 `db:5432`로만 접속합니다.

## 현재 기능 범위

- 관리자 로그인
- 게시글 목록, 상세, 작성, 수정, 삭제
- 답변 작성, 수정, 삭제
- 게시글당 첨부파일 1개 업로드/교체/삭제/다운로드
- 단일 ZIP 청크 업로드 후 자동 게시글 생성 및 ZIP 다운로드
- AI 답변 생성(OpenAI / Claude / Grok)
- 검색
- 배치 삭제

## 주의 사항

- 관리자 로그인 ID는 영문과 숫자만 허용합니다.
- 시드된 기본 관리자 계정(`admin` / `admin`)은 공용 노출 전에 반드시 변경하세요.
- 일반 게시글 본문과 답변 본문은 프론트에서 Base64로 인코딩해 전송하고, 서버는 이를 디코딩해 저장합니다.
- 단일 ZIP 청크 업로드는 `upload_zip_post.py`를 통해 처리합니다.
- 업로드 세션 wire JSON은 alias 키와 AES-GCM 암호문만 사용합니다. 파일명도 실제 값이 암호화되어 전송됩니다.
- Base64는 보안 기능이 아닙니다.
- 첨부파일은 게시글당 1개만 허용합니다.
- 최대 업로드 크기는 기본 `100MB`입니다.
- 게시글 목록, 상세, 첨부파일 다운로드는 로그인 없이 공개 접근할 수 있으며, 이 동작은 유지합니다.
