# 보안 가이드

## 인증과 인가

현재 백엔드는 Spring Security filter chain을 쓰지 않고 각 컨트롤러가 공통 `JwtProvider.authenticate`를 호출하여 JWT와 현재 계정의 존재 여부를 검증합니다.

인증 필요:

- 게시글 생성/수정/삭제
- 일괄 삭제
- 댓글 생성/수정/삭제
- AI 답변 생성
- 업로드 세션 생성/조회/chunk/finalize
- 사용자 관리(`GET/POST /api/v1/users`, `PUT/DELETE /api/v1/users/{id}`) — JWT 인증에 더해 ADMIN 역할까지 필요
- `/api/v1/auth/me`

공개 접근:

- `GET /api/v1/health`
- `GET /api/v1/posts`
- `GET /api/v1/posts/{id}`
- `GET /api/v1/posts/{id}/attachments/{attachmentId}`
- `POST /api/v1/auth/login`

## 역할 기반 인가

계정 역할은 `ADMIN`(관리자)과 `USER`(일반사용자) 두 가지입니다. `admins` 테이블의 `role` 컬럼(V13)으로 관리되며, 기존 시드 `admin` 계정을 포함한 모든 기존 계정은 `ADMIN`으로 승계됩니다.

- 쓰기 기능(게시글 작성/댓글/AI 답변/업로드 세션)은 유효한 JWT만 있으면 `USER`도 전부 사용할 수 있습니다.
- 게시글/댓글 **수정/삭제**는 작성자 본인 또는 `ADMIN`만 가능합니다. 작성자가 null인 레거시 글/댓글은 `ADMIN`만 수정/삭제할 수 있습니다. `USER`가 남의 글/댓글을 수정/삭제하면 403 `FORBIDDEN`입니다(코드: `BoardService.ensureCanManagePost`/`ensureCanManageReply`). AI 답변은 작성자 없음 + `AI_REPLY_LOCKED`로 수정/삭제가 차단됩니다. 게시글 일괄 삭제도 포함된 id 전부에 대해 소유권/ADMIN을 검사하며, 하나라도 권한이 없으면 전체가 403으로 실패합니다.
- 역할 제한이 있는 기능(사용자 관리 API)은 `ADMIN` 전용이며, 이 외에는 게시글 소유권 검사가 추가로 적용됩니다.
- 인가 방식은 인증과 마찬가지로 Spring Security filter chain이 아니라 컨트롤러별 직접 JWT 검증입니다. JWT에는 role을 넣지 않고 고유 계정 ID를 subject로, `tokenVersion=2`를 claim으로 넣습니다. 이전 username 토큰은 거부하며 재로그인이 필요합니다.
- ADMIN 여부 판단은 `UserManagementService`에서 매 요청마다 계정 ID로 DB를 조회해 수행합니다. 토큰에 역할 정보가 내장되지 않으므로, 강등이나 계정 삭제가 다음 요청부터 즉시 반영됩니다.
- `USER`가 사용자 관리 API를 호출하면 403 `FORBIDDEN`입니다. 계정이 삭제된 경우 그 계정 JWT는 이후 요청에서 401 `INVALID_CREDENTIALS`로 거부됩니다.
- 마지막 남은 ADMIN은 삭제하거나 USER로 강등할 수 없습니다(409 `LAST_ADMIN_PROTECTED`). 자기 자신의 계정 삭제도 불가합니다(409 `SELF_DELETE_NOT_ALLOWED`).

프론트엔드는 로그인/me 응답의 `userId`와 `role`을 auth store와 `localStorage`(`auth_user_id`, `auth_role`)에 보관합니다. `/users` 사용자 관리 화면은 라우트 가드에서 ADMIN만 접근을 허용하며, ADMIN이 아니면 `/`로 리다이렉트합니다. 이는 UX 가드일 뿐이며 실제 인가는 백엔드에서 수행됩니다.

## JWT

- 서명: HS256
- secret: `APP_JWT_SECRET`
- 만료: `APP_JWT_EXPIRATION_MS`
- 고유 계정 ID(`admins.id`)가 JWT subject에 저장됩니다. username은 표시 및 로그인 입력용입니다.
- 글·댓글 소유권은 `author_user_id`, 업로드 소유권은 `created_by_user_id`로 검사합니다(V17). username 재사용으로 기존 토큰이나 작성자 권한이 승계되지 않습니다.
- 계정 ID를 연결하지 못한 기존 글·댓글은 ADMIN만 관리할 수 있습니다. 미연결 업로드 세션은 사용할 수 없고 만료 정리 대상이 됩니다.

운영 기준:

- `APP_JWT_SECRET`는 32바이트 이상 난수로 설정합니다.
- secret 변경 시 기존 token은 무효화됩니다.
- 코드 fallback secret은 개발용이며 운영에서 사용하지 않습니다.

## 클라이언트 세션 처리

백엔드 JWT는 발급 후 `APP_JWT_EXPIRATION_MS`(기본 1시간)에 고정 만료되며, 토큰 갱신이나 슬라이딩 세션은 없습니다. 프론트엔드는 이와 별개로 다음 두 가지 자동 로그아웃을 수행합니다.

- 유휴 자동 로그아웃: 로그인 상태에서 마지막 사용자 활동(`mousedown`/`keydown`/`scroll`/`touchstart`) 후 1시간 동안 동작이 없으면 자동 로그아웃합니다. 이 1시간은 프론트 코드의 하드코딩 상수(`front/composables/useIdleTimeout.ts`의 `IDLE_TIMEOUT_MS`)이며 별도 환경 변수가 없습니다.
- 401 강제 로그아웃: `Authorization` 헤더를 보낸 인증 요청이 `401`을 받으면(서버가 토큰을 거부) 세션 만료로 보고 즉시 로그아웃합니다. 로그인 요청은 `Authorization` 헤더가 없으므로 제외됩니다.

구현 세부:

- 마지막 활동 시각은 `localStorage`의 `auth_last_activity`에 보존됩니다. 리로드나 탭 복원이 유휴 데드라인을 리셋하지 않으며, 절전/탭 복귀 시 `visibilitychange`/`focus`로 유휴 시간을 재평가합니다.
- 로그아웃 시 `auth_token`/`auth_user_id`/`auth_username`/`auth_role`/`auth_last_activity`를 모두 제거합니다. 유휴 자동 로그아웃 등 기존 세션 동작은 역할과 무관하게 동일하게 적용됩니다.
- 자동 로그아웃 뒤에는 공개 상세(`/posts/:id`)를 제외하고 `/login`으로 history를 교체해 보호 화면이 남지 않게 합니다.
- 이 처리는 클라이언트 측 UX 보호이며 서버 세션 무효화가 아닙니다. 토큰 자체는 백엔드 만료 시점까지 유효하므로, 강한 세션 보장이 필요하면 `APP_JWT_EXPIRATION_MS`를 짧게 유지하는 것이 우선입니다.

## 관리자 계정

Flyway 마이그레이션이 기본 관리자 계정(시드)을 만듭니다. 운영 노출 전 다음 중 하나를 수행합니다.

- 기본 계정 비밀번호 hash 교체
- 별도 관리자 계정 생성 후 기본 계정 제거
- 최소한 외부 공개 전에 강한 비밀번호로 변경

기본 계정은 ADMIN 역할입니다(`admins.role` 컬럼 기본값, V13). 추가 관리자/일반사용자 계정 생성, 역할 변경(승격/강등), 비밀번호 재설정, 계정 삭제는 ADMIN 전용 사용자 관리 API로 수행합니다. 마지막 남은 ADMIN은 삭제/강등할 수 없습니다.

username은 코드상 영문/숫자만 허용합니다.

## Secret 관리

문서와 Git에 기록하면 안 되는 값:

- `APP_DB_PASSWORD`
- `APP_JWT_SECRET`
- `APP_UPLOAD_SESSIONS_SECRET`
- `LLM_UPLOAD_SESSIONS_SECRET`
- `LLM_JWT_TOKEN`
- `LLM_PASSWORD`
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `XAI_API_KEY`
- PEM/PPK private key 내용

현재 코드에는 로컬 개발 편의를 위한 fallback 값이 일부 Git에 남아 있습니다. `application.properties`의 DB password fallback, JWT fallback secret, 업로드 세션 fallback secret은 운영 secret으로 쓰면 안 됩니다. 운영에서는 반드시 `.env`의 `APP_DB_PASSWORD`, `APP_JWT_SECRET`, `APP_UPLOAD_SESSIONS_SECRET`로 덮어씁니다.

점검:

```bash
git status --short
rg -n 'API_''KEY=|SEC''RET=|PASS''WORD=|TO''KEN=|BEGIN .*PRIVATE ''KEY' .
```

필요하면 `.env`, key 파일, 백업 파일은 검색 대상에서 제외하고 별도로 권한을 확인합니다.

## CORS

`APP_CORS_ALLOWED_ORIGINS`에 명시된 origin만 `/api/**` CORS 요청을 허용합니다.

운영에서는 필요한 도메인만 남깁니다. 임시 IP나 localhost가 꼭 필요한지 주기적으로 검토합니다.

## 파일 업로드

일반 첨부파일 제한:

- `APP_ATTACHMENTS_MAX_FILE_SIZE` (파일당, 기본 100MB)
- `APP_ATTACHMENTS_MAX_REQUEST_SIZE` (요청 전체, 기본 500MB)
- `APP_ATTACHMENTS_MAX_COUNT` (게시글당 첨부 개수, 기본 5)

ZIP 세션 결과 제한:

- `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE`
- `APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE`

저장 시 원본 파일명에서 경로 요소를 제거하고 UUID 기반 저장명을 사용합니다.

## 업로드 세션 암호화

업로드 세션 API는 alias 필드와 AES-GCM 암호문을 사용합니다.

- secret은 `APP_UPLOAD_SESSIONS_SECRET`
- nonce는 12 bytes
- tag는 128 bits
- alias 이름을 AAD로 사용

이 방식은 wire payload의 의미를 숨기는 용도입니다. TLS를 대체하지 않습니다. 외부 공개 운영에서는 HTTPS가 필요합니다.

## 네트워크 노출

EC2 현재 구성:

- front: host `8083` publish
- back: host publish 없음, `default` 및 외부 `auto_default` Docker 네트워크에서 `8080` expose
- DB: `127.0.0.1:5432` publish 및 Docker network 접근

권장:

- 외부에는 front 또는 HTTPS reverse proxy만 공개합니다.
- backend와 DB는 외부 inbound에서 닫습니다.
- SSH 22번은 필요한 IP로 제한합니다.

## 운영 보안 체크리스트

- [ ] 기본 관리자 계정 변경
- [ ] 기본 admin 외 추가 관리자/일반사용자 계정은 사용자 관리 API(`/users`)로 생성·관리
- [ ] `.env` secret 난수화
- [ ] `.env`와 key 파일 권한 제한
- [ ] SSH inbound IP 제한
- [ ] backend/DB 외부 직접 노출 차단
- [ ] HTTPS 적용 또는 upstream reverse proxy 확인
- [ ] 백업 파일 접근 권한 제한
- [ ] 운영 로그에 secret 값이 출력되지 않는지 확인
- [ ] AI provider key rotation 절차 확보
- [ ] `APP_JWT_EXPIRATION_MS`(백엔드 토큰 만료)와 프론트 유휴 자동 로그아웃(하드코딩 1시간)의 정합성 확인
