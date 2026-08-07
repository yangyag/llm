# 아키텍처

## 논리 구성

```text
Browser
  -> Frontend: Nuxt/Vue static SPA served by Nginx
      -> /api/* proxy
          -> Backend: Spring Boot API
              -> PostgreSQL
              -> Attachment volume
              -> Upload-session volume
              -> External AI APIs
```

## 런타임 구성

| 컴포넌트 | 컨테이너/프로세스 | 역할 |
| --- | --- | --- |
| Frontend | `llm-front` | Nuxt 정적 산출물 제공, `/api/` 요청을 백엔드로 proxy |
| Backend | `llm-back` | REST API, 인증, 게시판, 업로드 세션, AI 답변 생성 |
| Database | `auto-postgres` 운영 기준 | PostgreSQL 데이터 저장 |
| Volumes | `*-llm-back-attachments`, `*-llm-back-upload-sessions` | 첨부파일과 임시 청크 저장 |

`docker-compose.yml`의 `back` 서비스는 `default` 네트워크와 외부 `auto_default` 네트워크에 동시에 연결됩니다. 운영 DB `auto-postgres`는 `auto_default` 네트워크를 통해 접근합니다.

## 요청 흐름

### 일반 게시글 조회

1. 브라우저가 `GET /api/v1/posts` 또는 `GET /api/v1/posts/{id}`를 호출합니다.
2. Nginx가 `/api/` 요청을 `http://llm-back:8080`으로 proxy합니다.
3. 목록 API는 게시글 요약, 댓글 수, 첨부파일 존재 여부, 변환 준비 여부를 조회합니다.
4. 상세 API는 게시글 본문, 댓글, 첨부파일 메타데이터를 조회합니다.
5. 상세 응답의 `attachments` 배열 각 항목은 `downloadUrl`(`/api/v1/posts/{id}/attachments/{attachmentId}` 형식)을 포함합니다.

### 인증 쓰기 작업

1. 사용자가 `POST /api/v1/auth/login`으로 JWT를 받습니다.
2. 프론트는 쓰기 API에 `Authorization: Bearer <token>`을 보냅니다.
3. 각 컨트롤러가 `JwtProvider.validateAndGetUsername`으로 직접 토큰을 검증하고 JWT subject(username)를 서비스에 전달합니다.
4. 서비스 계층이 게시글 생성 시 `author_username`을 기록하고, 수정/삭제 시 **작성자 본인 또는 ADMIN 여부**(`admins.role`)를 검증한 뒤 게시글, 댓글, 첨부파일을 처리합니다.

### ZIP 청크 업로드

1. 배포된 `upload_zip_post.py`가 ZIP 바이트를 읽고 SHA-256을 계산합니다.
2. 백엔드는 `APP_UPLOAD_SESSIONS_SECRET`으로 alias 필드별 AES-GCM 암호문 JSON을 복호화합니다. 스크립트는 `LLM_UPLOAD_SESSIONS_SECRET`이 있으면 그 값을 쓰고, 없으면 `APP_UPLOAD_SESSIONS_SECRET`을 사용하므로 최종 secret 값이 백엔드와 같아야 합니다.
3. `POST /api/v1/upload-sessions`가 세션을 만듭니다.
4. `POST /api/v1/upload-sessions/{sessionId}/chunks`가 각 청크를 저장합니다.
5. `POST /api/v1/upload-sessions/{sessionId}/finalize`가 청크를 합치고 SHA-256을 검증한 뒤 게시글과 ZIP 첨부파일을 생성합니다.
6. 세션 row와 임시 디렉터리는 성공 후 정리됩니다.

### AI 답변 생성

1. 관리자가 `POST /api/v1/posts/{id}/ai-replies`에 provider를 보냅니다.
2. 백엔드는 `GPT`, `CLAUDE`, `GROK` 중 하나로 변환합니다.
3. provider별 외부 API를 호출하고 생성 답변을 `post_replies`에 `is_ai=true`로 저장합니다.
4. AI 답변은 수정/삭제할 수 없습니다.

### 프론트 자동 로그아웃

1. 로그인 상태에서만 프론트가 유휴 타이머를 동작시킵니다. 마지막 사용자 활동(`mousedown`/`keydown`/`scroll`/`touchstart`) 후 1시간(`IDLE_TIMEOUT_MS`, 프론트 하드코딩 상수) 무동작이면 자동 로그아웃합니다.
2. 활동 시각은 `localStorage`의 `auth_last_activity`에 5초 throttle로 기록되며, 리로드/탭 복원이 유휴 데드라인을 리셋하지 않습니다(로그인 시점에 시드). `visibilitychange`/`focus`로 탭 복귀 시 유휴 시간을 재평가합니다.
3. `front/services/api.ts`의 인증 요청(`Authorization` 헤더 포함)이 `401`을 받으면 `window`에 `auth:unauthorized` 이벤트를 보내고, 인증 플러그인이 이를 수신해 강제 로그아웃한 뒤 `/login`으로 이동합니다. 로그인 요청은 `Authorization` 헤더가 없어 제외됩니다.
4. 자동 로그아웃은 `auth_token`/`auth_username`/`auth_last_activity`를 제거합니다. 이는 프론트 전용 동작이며, 백엔드 JWT는 기존대로 `APP_JWT_EXPIRATION_MS`(기본 1시간) 후 고정 만료하고 토큰 갱신/슬라이딩 세션은 없습니다.

## 계층 구조

| 계층 | 주요 패키지 |
| --- | --- |
| Controller | `com.llm.app.auth`, `com.llm.app.board.controller`, `com.llm.app.common.web` |
| Service | `com.llm.app.auth`, `com.llm.app.board.service`, `com.llm.app.board.ai` |
| Repository | `com.llm.app.board.repository`, `com.llm.app.auth.AdminRepository` |
| Domain | `com.llm.app.board.model`, `com.llm.app.auth.Admin` |
| DTO | `com.llm.app.board.dto`, `com.llm.app.auth.*Response`, `LoginRequest` |

## 배포 경계

- 프론트 이미지는 빌드 시점 `NUXT_PUBLIC_API_BASE` 값을 정적 번들에 포함할 수 있습니다.
- 운영에서는 Nginx proxy가 같은 origin의 `/api/`를 백엔드로 전달하므로 `NUXT_PUBLIC_API_BASE`를 비워 둡니다.
- 백엔드는 DB와 파일 volume을 상태 저장소로 사용합니다.
- AI provider API key가 비어 있으면 해당 provider 호출 시 `AI_PROVIDER_NOT_CONFIGURED` 오류를 반환합니다.
