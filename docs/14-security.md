# 보안 가이드

## 인증과 인가

현재 백엔드는 Spring Security filter chain을 쓰지 않고 각 컨트롤러에서 JWT를 직접 검증합니다.

인증 필요:

- 게시글 생성/수정/삭제
- 일괄 삭제
- 댓글 생성/수정/삭제
- AI 답변 생성
- 업로드 세션 생성/조회/chunk/finalize
- `/api/v1/auth/me`

공개 접근:

- `GET /api/v1/health`
- `GET /api/v1/posts`
- `GET /api/v1/posts/{id}`
- `GET /api/v1/posts/{id}/attachment`
- `POST /api/v1/auth/login`

## JWT

- 서명: HS256
- secret: `APP_JWT_SECRET`
- 만료: `APP_JWT_EXPIRATION_MS`
- username은 JWT subject에 저장됩니다.

운영 기준:

- `APP_JWT_SECRET`는 32바이트 이상 난수로 설정합니다.
- secret 변경 시 기존 token은 무효화됩니다.
- 코드 fallback secret은 개발용이며 운영에서 사용하지 않습니다.

## 클라이언트 세션 처리

백엔드 JWT는 발급 후 `APP_JWT_EXPIRATION_MS`(기본 1시간)에 고정 만료되며, 토큰 갱신이나 슬라이딩 세션은 없습니다. 프론트엔드는 이와 별개로 다음 두 가지 자동 로그아웃을 수행합니다.

- 유휴 자동 로그아웃: 로그인 상태에서 마지막 사용자 활동(`mousedown`/`keydown`/`scroll`/`touchstart`) 후 1시간 동안 동작이 없으면 자동 로그아웃합니다. 이 1시간은 프론트 코드의 하드코딩 상수(`front/src/App.jsx`의 `IDLE_TIMEOUT_MS`)이며 별도 환경 변수가 없습니다.
- 401 강제 로그아웃: `Authorization` 헤더를 보낸 인증 요청이 `401`을 받으면(서버가 토큰을 거부) 세션 만료로 보고 즉시 로그아웃합니다. 로그인 요청은 `Authorization` 헤더가 없으므로 제외됩니다.

구현 세부:

- 마지막 활동 시각은 `localStorage`의 `auth_last_activity`에 보존됩니다. 리로드나 탭 복원이 유휴 데드라인을 리셋하지 않으며, 절전/탭 복귀 시 `visibilitychange`/`focus`로 유휴 시간을 재평가합니다.
- 로그아웃 시 `auth_token`/`auth_username`/`auth_last_activity`를 모두 제거합니다.
- 이 처리는 클라이언트 측 UX 보호이며 서버 세션 무효화가 아닙니다. 토큰 자체는 백엔드 만료 시점까지 유효하므로, 강한 세션 보장이 필요하면 `APP_JWT_EXPIRATION_MS`를 짧게 유지하는 것이 우선입니다.

## 관리자 계정

Flyway 마이그레이션이 기본 `admin`/`admin` 계정을 만듭니다. 운영 노출 전 다음 중 하나를 수행합니다.

- 기본 계정 비밀번호 hash 교체
- 별도 관리자 계정 생성 후 기본 계정 제거
- 최소한 외부 공개 전에 강한 비밀번호로 변경

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

- `APP_ATTACHMENTS_MAX_FILE_SIZE`
- `APP_ATTACHMENTS_MAX_REQUEST_SIZE`
- 게시글당 1개 첨부파일

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
- [ ] `.env` secret 난수화
- [ ] `.env`와 key 파일 권한 제한
- [ ] SSH inbound IP 제한
- [ ] backend/DB 외부 직접 노출 차단
- [ ] HTTPS 적용 또는 upstream reverse proxy 확인
- [ ] 백업 파일 접근 권한 제한
- [ ] 운영 로그에 secret 값이 출력되지 않는지 확인
- [ ] AI provider key rotation 절차 확보
- [ ] `APP_JWT_EXPIRATION_MS`(백엔드 토큰 만료)와 프론트 유휴 자동 로그아웃(하드코딩 1시간)의 정합성 확인
