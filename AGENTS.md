# AGENTS.md

게시판 + AI 답변 + ZIP 청크 업로드 모노레포. Nuxt 3/Vue 3/TypeScript/Pinia 프론트, Spring Boot 백엔드(Java 25), PostgreSQL(Flyway), Docker Compose.

## 저장소 구조
- `front/` — Nuxt 3/Vue 3/TypeScript/Pinia UI, API 클라이언트, Nginx 설정, 프론트 Dockerfile (Node 22 / npm)
- `back/` — Spring Boot API, JPA 도메인, Flyway 마이그레이션, 테스트 (Gradle wrapper 포함)
- `docker-compose.yml` — 루트 스택 정의 (배포 단위). PostgreSQL 서비스 미포함, 외부 네트워크 `auto_default` 필요
- `.env` (Git 미추적) / `.env.example` (작성 기준 템플릿)
- `docs/` — 상세 문서 (아래 표 참조). 운영 기준은 항상 EC2 실제 파일/컨테이너 상태

## 자주 쓰는 명령 (게이트)
- 백엔드 테스트: `cd back && ./gradlew clean test` — controller/service/domain 변경 시 필수
- 프론트 검사/빌드: `cd front && npm run typecheck && npm run build` (`nuxi typecheck`, `nuxi generate`) — UI/API client 변경 시 필수
- 통합 기동 + health: `docker compose up -d --wait` 후 `curl -fsS http://127.0.0.1:8083/api/v1/health` (정상: `{"status":"UP"}`)
- 로컬 외부 네트워크 선결(EC2는 존재 확인만 수행): `docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default`
- 백엔드 단독: `cd back && APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun`
- 프론트 단독: `cd front && npm ci && npm run dev` (Nuxt dev 5174, Nitro `/api` proxy → 8082)
- 작업 전후 `git status --short`로 범위 확인. 커밋 메시지는 한글.

## 아키텍처 핵심
- 포트: back **8080**(내부 expose 전용, host publish 안 함) / **8082**(로컬 bootRun + Nitro dev proxy 대상) / **8083**(front proxy host 포트 = health 진입점) / 5174(Nuxt dev) / 5432(DB, 외부 차단). health는 어디서든 **8083** 경유.
- 모든 API는 `/api/v1` 아래. front/Nginx가 `/api/` → `http://llm-back:8080` proxy. 운영에선 `NUXT_PUBLIC_API_BASE`를 비워 상대경로 `/api` 사용.
- 인증은 Spring Security filter chain이 아니라 **컨트롤러별 직접 JWT 검증**. 새 보호 엔드포인트는 컨트롤러에서 공통 `JwtProvider.authenticate`를 호출해 JWT와 계정 존재 여부를 확인. JWT subject는 계정 ID + `tokenVersion=2`이며 이전 토큰은 재로그인 필요. 공개 엔드포인트 목록은 docs/14.
- 게시글/댓글 **수정/삭제는 작성자 본인 또는 ADMIN만** 가능. 작성자 권한은 `posts.author_user_id`/`post_replies.author_user_id`(V17), username은 표시용. 계정 ID가 null인 레거시 글/댓글은 ADMIN만 관리. 업로드 소유권도 `created_by_user_id`로 검사. 검사는 `BoardService.ensureCanManagePost`/`ensureCanManageReply`(USER가 남의 글/댓글 → 403 `FORBIDDEN`). AI 답변은 작성자 없음 + `AI_REPLY_LOCKED`. 게시글 일괄 삭제도 포함 id 전체에 대해 검사 후 부분 삭제 없이 실패. (docs/07, docs/14)
- 세션 종료는 **두 경로**: 백엔드 JWT 고정 만료(`APP_JWT_EXPIRATION_MS`, 기본 1시간) ↔ 프론트 유휴 자동 로그아웃(하드코딩 1시간, `front/composables/useIdleTimeout.ts`의 `IDLE_TIMEOUT_MS`). 토큰 갱신/슬라이딩 세션 없음 → 둘은 독립이며 한쪽만 바꾸면 만료 시점이 어긋남. 프론트는 인증 요청(`Authorization` 포함) 401 시 `auth:unauthorized` 이벤트로 강제 로그아웃(`front/services/api.ts`, `front/plugins/auth.client.ts`). 새 env 없음 (docs/14).
- AI 답변 기능은 2026-09-03 이후 종료. `POST /api/v1/posts/{id}/ai-replies`는 410 `AI_REPLY_DISABLED` 스텁으로만 유지, 관련 클래스·컬럼은 레거시 조회/보호용 잔재. (docs/09).
- 운영 DB는 공용 컨테이너 `yangyag-postgres`(외부 네트워크 `auto_default`, compose 프로젝트 `auto`)의 전용 database `llm`(schema `llm`). `APP_DB_HOST=yangyag-postgres`. 컨테이너 안 `127.0.0.1`은 호스트 루프백이 아님(docs/04). 정상 컨테이너: `llm-front`, `llm-back`, `yangyag-postgres` healthy.

- 첨부파일 삭제는 DB 커밋 후 수행. metadata 삭제와 `attachment_file_deletions` 등록을 같은 트랜잭션에서 처리(V18), 실패 시 1분마다 재시도. 새 파일은 롤백 시 정리. 계정 전환·검증 절차는 docs/18.

## 설정과 비밀값 규칙
- 전체 환경 변수와 fallback 동작은 **docs/05-configuration.md** + `.env.example` 참조. 실제 실행 기준은 항상 대상 환경의 `.env`.
- secret(`APP_JWT_SECRET`, `APP_UPLOAD_SESSIONS_SECRET`, `APP_DB_PASSWORD`, `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`XAI_API_KEY`, `LLM_*`, PEM key)은 **문서·Git·로그·화면에 절대 기록 금지**. `.env.example`엔 placeholder만.
- `.env`, `.env.*`, `llm.env*`는 커밋 금지. `front/node_modules/`, `front/.nuxt/`, `front/.output/`, `back/build/`, `.gradle/`도 커밋 금지.
- `application.properties`의 DB password/JWT/업로드 secret은 **개발용 fallback** — 운영에선 secret으로 쓰지 말고 `.env`로 덮어쓴다.

## 반드시 지킬 제약 (gotchas)
- health/검증은 **항상 front proxy 8083** 경유. back 8080은 host에 publish되지 않음 (8080 health 실패는 정상).
- compose는 외부 네트워크 `auto_default`(compose 프로젝트 `auto` 소유, `yangyag-postgres` 거주) 필수. 없으면 `up -d --wait`가 health 단계에서 실패 → 네트워크 + 접근 가능한 PostgreSQL/권한 선결.
- `APP_ATTACHMENTS_ROOT_PATH` / `APP_UPLOAD_SESSIONS_ROOT_PATH`가 volume mount 경로와 불일치하면 조용히 JVM temp(`${java.io.tmpdir}/llm-*`)로 fallback → volume 무시, ZIP finalize 실패.
- 로컬 백엔드 기본 포트는 8080 → Nitro dev proxy(8082)와 맞추려면 `SERVER_PORT=8082`로 실행 (또는 `front/nuxt.config.ts` 수정).
- Flyway 적용된 `V1~V12` SQL 수정 금지, 새 `V13+`로만 추가. JPA(`ddl-auto=validate`)와 Flyway는 동일 `APP_DB_SCHEMA`. 테스트는 H2(create-drop, Flyway off)라 DDL 경로가 운영과 다름.
- **EC2 DB의 flyway history와 로컬 migration 파일이 어긋나면 체크섬 충돌로 `llm-back` 시작 실패** (2026-08-08: EC2에 구버전 `V13 create ai reply jobs`가 있어 로컬 `V13 add role to admins`와 충돌 → history에서 version=13 행 제거 후 V13~V15 적용). 배포 전 `select version, description from llm.flyway_schema_history`로 로컬 파일과 대조. (docs/12, docs/15)
- 게시글 수정/삭제는 작성자 본인 또는 ADMIN만 가능(레거시 null 작성자 글은 ADMIN만). 프론트는 목록/상세에서 권한 없는 글의 수정·삭제·체크박스를 숨김. (docs/07, docs/14)
- `FILE_CONVERSION_REQUEST` 게시글은 수동 생성 불가(업로드 세션 finalize로만), 첨부 있으면 수정 불가, AI 답변 불가. AI 답변(`is_ai=true`)은 수정·삭제 불가.
- 업로드 세션 secret은 백엔드(`APP_UPLOAD_SESSIONS_SECRET`)와 스크립트가 **동일**해야 함 (alias A1~A11 + AES-GCM wire format, docs/08).
- 게시글/댓글 본문은 `bodyBase64`(UTF-8→Base64, 보안 아님). 생성/수정은 `multipart/form-data`.
- 운영에서 `docker compose down -v` / 무분별한 volume·prune 금지 (첨부 데이터 손실). 수동 compose는 `LLM_ENV_FILE=/home/ubuntu/llm/.env`를 설정하고 `--project-name ubuntu --env-file .env -f docker-compose.yml`을 명시.
- 로컬 `.env`의 `LLM_FRONT_IMAGE`/`LLM_BACK_IMAGE`는 `llm-front:1.0`/`llm-back:1.0`(compose 기본값) 유지. 다른 태그로 이탈하면 규약대로 빌드한 이미지가 컨테이너에 안 붙음(2026-09-04 확인). 의심되면 `docker compose config`의 해석 image와 `docker inspect --format '{{.Config.Image}}' llm-front llm-back`을 대조.
- 빌드/배포: Hub를 쓰지 않는다. Windows에서 이미지 빌드 → `docker save` tar → EC2 `~/llm/` scp → `docker load` → compose up. 프론트 `.\aws\deploy-front.ps1` (`llm-front:1.0`, `mem_limit: 64m`), 백엔드 `.\aws\deploy-back.ps1` (`llm-back:1.0`). 둘 다 `pull_policy: never`. EC2에서 소스 빌드하지 않는다.


## README에서 이관한 개발·운영 안내

README는 프로젝트 소개와 이용 안내를 담는다. 인프라·환경 설정·개발·배포 정보는 AGENTS.md와 docs/에서 관리한다. 아래 명령은 Bash 기준이다.

### 아키텍처


```text
[Browser]
  -> Frontend (:8083)
      -> /api/v1/*
         -> Backend (container 8080)
             -> PostgreSQL (:5432)
```

- Frontend는 Nuxt 3 + Vue 3 + TypeScript + Pinia로 동작하며, 정적 생성 결과를 Nginx가 제공합니다.
- Backend는 Spring Boot 3.5.11, Java 25, PostgreSQL 18, Flyway를 사용합니다.
- 프론트는 로그인 후 Bearer 토큰으로 보호 API를 호출합니다. AI 답변 생성은 종료되었습니다.

### 빠른 시작

로컬 환경용 절차입니다. 접근 가능한 PostgreSQL과 권한을 먼저 준비합니다. EC2에서는 네트워크를 수동 생성하지 말고 아래 배포 절차를 따릅니다.


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

### 환경 변수


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
- AI 답변 종료 전 레거시 설정: `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_API_BASE_URL`
- `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `ANTHROPIC_API_BASE_URL`
- `XAI_API_KEY`, `XAI_MODEL`, `XAI_API_BASE_URL`

주의:
- `APP_JWT_SECRET`은 코드상 fallback이 있어도 운영에서는 반드시 설정하세요.
- 기본 DB 값과 첨부파일 경로는 `.env.example`을 기준으로 맞춰 두면 됩니다.

### 개별 개발


#### Frontend

```bash
cd front
npm ci
npm run dev
```

- 기본 접속: `http://localhost:5174`

#### Backend

```bash
cd back
APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun
```

- Health: `http://localhost:8083/api/v1/health`

#### 단일 ZIP 청크 업로드

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

### 검증


```bash
cd back && ./gradlew clean test
cd front && npm run typecheck && npm run build
```

로컬 통합 확인 (외부 네트워크 `auto_default` 필요):

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

### EC2 배포


EC2 배포도 루트 [`docker-compose.yml`](docker-compose.yml) 하나를 사용합니다. 운영 파일은 EC2의 `/home/ubuntu/llm` 아래에서 관리하고, 환경값은 `/home/ubuntu/llm/.env`에 둡니다. 시작점은 저장소의 `.env.example`입니다.

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

### 프로젝트 규칙


#### 디렉터리
- `front/`: UI, 라우팅, 상태관리, 프론트 Docker 빌드
- `back/`: API, 도메인, 테스트, 정적 자산, 백엔드 Docker 빌드
- 루트: 통합 실행/문서(`docker-compose.yml`, `.env.example`, `README.md`)

#### Git
- 저장소: `git@github.com:yangyag/llm.git`
- `git commit` 메시지는 한글로 작성합니다.

#### 배포
- Docker Hub를 쓰지 않습니다. Windows에서 이미지를 만들고 tar로 EC2에 `docker load`합니다.
- 프론트: `.\aws\deploy-front.ps1` (`llm-front:1.0`)
- 백엔드: `.\aws\deploy-back.ps1` (`llm-back:1.0`)

#### 품질 게이트
- 테스트 원칙: 백엔드 API JUnit(`MockMvc`) 중심
- 백엔드 기능 변경 시 `back` 테스트를 반드시 통과시킵니다.
- 프론트 변경 시 `front` 빌드를 반드시 통과시킵니다.
- 통합 영향이 있으면 루트 기준 컨테이너 실행과 헬스 체크를 확인합니다.
- 최소 검증 명령은 위 검증 섹션을 따릅니다.

## 상세 문서 안내 (docs/)
| 주제 | 경로 |
|------|------|
| 프로젝트 개요·저장소 구성·운영 제약 | docs/01-project-overview.md |
| 개발 환경 설정·로컬 실행 | docs/02-development-setup.md |
| 로컬 개발 (단독 실행·포트·fallback) | docs/03-local-development.md |
| 아키텍처 (네트워크·proxy·upload 흐름) | docs/04-architecture.md |
| 설정·환경 변수 (전체) | docs/05-configuration.md |
| 데이터베이스 (Flyway 스키마·제약·백업) | docs/06-database.md |
| API 레퍼런스 (엔드포인트·필드·오류 코드) | docs/07-api-reference.md |
| ZIP 청크 업로드 도구 | docs/08-upload-session-tool.md |
| AI 답변 연동 (provider 설정·제약) | docs/09-ai-integration.md |
| 테스트·품질 게이트 | docs/10-testing-quality.md |
| 빌드·릴리스·배포 | docs/11-build-release.md |
| EC2 배포·접속·검증 | docs/12-ec2-deployment.md |
| 운영 점검·장애 대응 Runbook | docs/13-operations-runbook.md |
| 보안 (인증·JWT·secret·CORS·노출) | docs/14-security.md |
| 문제 해결 (network/health/DB/첨부/ZIP/AI) | docs/15-troubleshooting.md |
| 문서 에이전트·EC2 읽기전용 점검 | docs/16-document-agents.md |
| 계정 ID 전환·첨부 일관성 검증 | docs/18-integrity-hardening.md |
| 전체 문서 인덱스 | docs/README.md |
