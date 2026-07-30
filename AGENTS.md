# AGENTS.md

게시판 + AI 답변 + ZIP 청크 업로드 모노레포. Nuxt 3/Vue 3/TypeScript/Pinia 프론트, Spring Boot 백엔드(Java 25), PostgreSQL(Flyway), Docker Compose.

## 저장소 구조
- `front/` — Nuxt 3/Vue 3/TypeScript/Pinia UI, API 클라이언트, Nginx 설정, 프론트 Dockerfile (Node 22 / npm)
- `back/` — Spring Boot API, JPA 도메인, Flyway 마이그레이션, 테스트 (Gradle wrapper 포함)
- `docker-compose.yml` — 루트 스택 정의 (배포 단위). PostgreSQL 서비스 미포함, 외부 네트워크 `auto_default` 필요
- `deploy-ec2.sh` — EC2 배포 스크립트
- `.env` (Git 미추적) / `.env.example` (작성 기준 템플릿)
- `docs/` — 상세 문서 (아래 표 참조). 운영 기준은 항상 EC2 실제 파일/컨테이너 상태

## 자주 쓰는 명령 (게이트)
- 백엔드 테스트: `cd back && ./gradlew clean test` — controller/service/domain 변경 시 필수
- 프론트 검사/빌드: `cd front && npm run typecheck && npm run build` (`nuxi typecheck`, `nuxi generate`) — UI/API client 변경 시 필수
- 통합 기동 + health: `docker compose up -d --wait` 후 `curl -fsS http://127.0.0.1:8083/api/v1/health` (정상: `{"status":"UP"}`)
- 외부 네트워크 선결: `docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default`
- 백엔드 단독: `cd back && APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun`
- 프론트 단독: `cd front && npm ci && npm run dev` (Nuxt dev 5174, Nitro `/api` proxy → 8082)
- 작업 전후 `git status --short`로 범위 확인. 커밋 메시지는 한글.

## 아키텍처 핵심
- 포트: back **8080**(내부 expose 전용, host publish 안 함) / **8082**(로컬 bootRun + Nitro dev proxy 대상) / **8083**(front proxy host 포트 = health 진입점) / 5174(Nuxt dev) / 5432(DB, 외부 차단). health는 어디서든 **8083** 경유.
- 모든 API는 `/api/v1` 아래. front/Nginx가 `/api/` → `http://llm-back:8080` proxy. 운영에선 `NUXT_PUBLIC_API_BASE`를 비워 상대경로 `/api` 사용.
- 인증은 Spring Security filter chain이 아니라 **컨트롤러별 직접 JWT 검증**. 새 보호 엔드포인트는 컨트롤러에서 수동 추가. 공개 엔드포인트 목록은 docs/14.
- 세션 종료는 **두 경로**: 백엔드 JWT 고정 만료(`APP_JWT_EXPIRATION_MS`, 기본 1시간) ↔ 프론트 유휴 자동 로그아웃(하드코딩 1시간, `front/composables/useIdleTimeout.ts`의 `IDLE_TIMEOUT_MS`). 토큰 갱신/슬라이딩 세션 없음 → 둘은 독립이며 한쪽만 바꾸면 만료 시점이 어긋남. 프론트는 인증 요청(`Authorization` 포함) 401 시 `auth:unauthorized` 이벤트로 강제 로그아웃(`front/services/api.ts`, `front/plugins/auth.client.ts`). 새 env 없음 (docs/14).
- AI provider는 `GPT`/`CLAUDE`/`GROK`만. 구현은 `back/.../board/ai/` (docs/09).
- 운영 DB는 별도 공용 컨테이너 `auto-postgres` (외부 `auto_default` 네트워크). 정상 컨테이너: `llm-front`, `llm-back`, `auto-postgres` healthy.

## 설정과 비밀값 규칙
- 전체 환경 변수와 fallback 동작은 **docs/05-configuration.md** + `.env.example` 참조. 실제 실행 기준은 항상 대상 환경의 `.env`.
- secret(`APP_JWT_SECRET`, `APP_UPLOAD_SESSIONS_SECRET`, `APP_DB_PASSWORD`, `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`XAI_API_KEY`, `LLM_*`, PEM key)은 **문서·Git·로그·화면에 절대 기록 금지**. `.env.example`엔 placeholder만.
- `.env`, `.env.*`, `llm.env*`는 커밋 금지. `front/node_modules/`, `front/.nuxt/`, `front/.output/`, `back/build/`, `.gradle/`도 커밋 금지.
- `application.properties`의 DB password/JWT/업로드 secret은 **개발용 fallback** — 운영에선 secret으로 쓰지 말고 `.env`로 덮어쓴다.

## 반드시 지킬 제약 (gotchas)
- health/검증은 **항상 front proxy 8083** 경유. back 8080은 host에 publish되지 않음 (8080 health 실패는 정상).
- compose는 외부 네트워크 `auto_default`(auto-postgres 거주) 필수. 없으면 `up -d --wait`가 health 단계에서 실패 → 네트워크 + 접근 가능한 PostgreSQL/권한 선결.
- `APP_ATTACHMENTS_ROOT_PATH` / `APP_UPLOAD_SESSIONS_ROOT_PATH`가 volume mount 경로와 불일치하면 조용히 JVM temp(`${java.io.tmpdir}/llm-*`)로 fallback → volume 무시, ZIP finalize 실패.
- 로컬 백엔드 기본 포트는 8080 → Nitro dev proxy(8082)와 맞추려면 `SERVER_PORT=8082`로 실행 (또는 `front/nuxt.config.ts` 수정).
- Flyway 적용된 `V1~V12` SQL 수정 금지, 새 `V13+`로만 추가. JPA(`ddl-auto=validate`)와 Flyway는 동일 `APP_DB_SCHEMA`. 테스트는 H2(create-drop, Flyway off)라 DDL 경로가 운영과 다름.
- `FILE_CONVERSION_REQUEST` 게시글은 수동 생성 불가(업로드 세션 finalize로만), 첨부 있으면 수정 불가, AI 답변 불가. AI 답변(`is_ai=true`)은 수정·삭제 불가.
- 업로드 세션 secret은 백엔드(`APP_UPLOAD_SESSIONS_SECRET`)와 스크립트가 **동일**해야 함 (alias A1~A11 + AES-GCM wire format, docs/08).
- 게시글/댓글 본문은 `bodyBase64`(UTF-8→Base64, 보안 아님). 생성/수정은 `multipart/form-data`.
- 기본 관리자 `admin/admin`(마이그레이션 시드)은 공용 노출 전 반드시 변경.
- 운영에서 `docker compose down -v` / 무분별한 volume·prune 금지 (첨부 데이터 손실). 수동 compose는 `--project-name ubuntu --env-file .env` 명시.
- 빌드/배포: Docker Hub namespace `yangyag2`, 태그 `latest`만 push (롤백 위해 시각/SHA/digest 기록). compose 이미지 빌드는 `docker compose --profile build build back-build front-build`.

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
| 전체 문서 인덱스 | docs/README.md |
