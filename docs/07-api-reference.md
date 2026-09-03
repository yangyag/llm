# API 가이드

모든 API path는 `/api/v1` 아래에 있습니다. 프론트/Nginx 배포에서는 같은 origin의 `/api/...` 요청이 백엔드로 proxy됩니다.

## 공통

인증이 필요한 API는 아래 header가 필요합니다.

```http
Authorization: Bearer <jwt>
```

오류 응답은 대체로 다음 형식입니다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "...",
  "timestamp": "2026-05-31T00:00:00Z",
  "path": "/api/v1/..."
}
```

## Health

### `GET /api/v1/health`

인증: 필요 없음

응답:

```json
{
  "status": "UP",
  "timestamp": "2026-05-31T00:00:00Z"
}
```

## Auth

### `POST /api/v1/auth/login`

인증: 필요 없음

요청:

```json
{
  "username": "<username>",
  "password": "<password>"
}
```

제약:

- `username`은 영문과 숫자만 허용합니다.
- `password`는 빈 값이면 안 됩니다.

응답:

```json
{
  "token": "<jwt>",
  "username": "admin",
  "role": "ADMIN"
}
```

`role`은 `ADMIN`(관리자) 또는 `USER`(일반사용자)입니다.

### `GET /api/v1/auth/me`

인증: 필요

토큰 누락 또는 무효 토큰이면 body 없이 HTTP 401을 반환합니다. me는 토큰의 username으로 DB를 조회하므로 계정이 삭제된 경우에도 body 없이 HTTP 401을 반환합니다.

응답:

```json
{
  "username": "admin",
  "role": "ADMIN"
}
```

## Users

사용자 관리 API입니다. 네 엔드포인트 모두 JWT 인증과 ADMIN 역할이 필요합니다. 유효한 JWT라도 호출자가 USER면 HTTP 403 `FORBIDDEN`입니다. 토큰이 없거나 유효하지 않으면 401 `INVALID_CREDENTIALS`, 대상 id가 없으면 404 `NOT_FOUND`, 요청 validation 실패는 400 `INVALID_REQUEST`입니다. 모든 응답에서 비밀번호는 반환되지 않습니다.

### `GET /api/v1/users?query=<아이디부분검색>`

인증: 필요(ADMIN 전용)

`query`는 선택입니다. 지정하면 username 부분 검색(대소문자 구분 없음)으로 필터링하고, 생략하면 전체 사용자를 반환합니다.

응답: HTTP 200, 사용자 배열.

```json
[
  {
    "id": 1,
    "username": "admin",
    "role": "ADMIN",
    "createdAt": "2026-05-31T00:00:00Z"
  }
]
```

| 필드 | 설명 |
| --- | --- |
| `id` | 사용자 id |
| `username` | 계정 아이디 |
| `role` | `ADMIN` 또는 `USER` |
| `createdAt` | 계정 생성 시각 |

### `POST /api/v1/users`

인증: 필요(ADMIN 전용)

요청:

```json
{
  "username": "operator1",
  "password": "...",
  "role": "USER"
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `username` | 예 | 영문과 숫자만 허용, 100자 이하, 중복 불가 |
| `password` | 예 | 4~64자 |
| `role` | 예 | `ADMIN` 또는 `USER`. 대소문자 구분 없이 `ADMIN`/`USER`로 정규화 |

응답: HTTP 201, 생성된 사용자(id, username, role, createdAt). 비밀번호는 포함되지 않습니다.

제약:

- `username`이 이미 존재하면 409 `DUPLICATE_USERNAME`입니다.

### `PUT /api/v1/users/{id}`

인증: 필요(ADMIN 전용)

요청:

```json
{
  "password": "...",
  "role": "USER"
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `role` | 예 | `ADMIN` 또는 `USER`. 대소문자 구분 없이 정규화 |
| `password` | 아니오 | 생략하면 기존 비밀번호를 유지합니다. 지정할 때는 4~64자 |

응답: HTTP 200, 수정된 사용자(id, username, role, createdAt).

제약:

- `username`은 변경할 수 없습니다.
- 마지막 남은 ADMIN을 USER로 강등할 수 없습니다. 위반 시 409 `LAST_ADMIN_PROTECTED`입니다.

### `DELETE /api/v1/users/{id}`

인증: 필요(ADMIN 전용)

응답: HTTP 204

제약:

- 마지막 남은 ADMIN은 삭제할 수 없습니다. 위반 시 409 `LAST_ADMIN_PROTECTED`입니다.
- 자기 자신의 계정은 삭제할 수 없습니다. 위반 시 409 `SELF_DELETE_NOT_ALLOWED`입니다.
- 삭제된 계정의 기존 JWT는 이후 `GET /api/v1/auth/me`와 사용자 관리 API에서 DB 조회 시 거부됩니다.

## Posts

### `GET /api/v1/posts?page=1&query=keyword`

인증: 필요 없음

응답 필드:

| 필드 | 설명 |
| --- | --- |
| `items` | 게시글 요약 배열 |
| `page` | 현재 페이지. 1부터 시작 |
| `pageSize` | 현재 코드 기준 10 |
| `totalItems` | 전체 항목 수 |
| `totalPages` | 전체 페이지 수 |
| `hasPrevious` | 이전 페이지 여부 |
| `hasNext` | 다음 페이지 여부 |

게시글 요약:

```json
{
  "id": 1,
  "title": "title",
  "mode": "NORMAL",
  "conversionReady": false,
  "replyCount": 0,
  "hasAttachment": false,
  "authorUsername": "member1",
  "createdAt": "2026-05-31T00:00:00Z"
}
```

### `GET /api/v1/posts/{id}`

인증: 필요 없음

응답:

```json
{
  "id": 1,
  "title": "title",
  "body": "plain text",
  "mode": "NORMAL",
  "conversionReady": false,
  "authorUsername": "member1",
  "createdAt": "2026-05-31T00:00:00Z",
  "updatedAt": "2026-05-31T00:00:00Z",
  "attachments": [],
  "replies": []
}
```

`attachments`는 첨부파일 배열입니다(없으면 빈 배열). 각 항목은 다음 형식이며, 일반 게시글은 최대 5개까지 가질 수 있습니다. 업로드 세션 finalize로 만들어진 `FILE_CONVERSION_REQUEST` 게시글은 항상 1개(원본 ZIP)입니다.

```json
{
  "id": 10,
  "originalFilename": "archive.zip",
  "size": 12345,
  "contentType": "application/zip",
  "downloadUrl": "/api/v1/posts/1/attachments/10"
}
```

`replies`는 댓글 배열입니다. 일반 댓글의 `aiProvider`와 `aiModel`은 `null`이고, AI 댓글의 `aiProvider`는 `GPT`, `CLAUDE`, `GROK` 중 하나입니다.

```json
{
  "id": 20,
  "body": "reply",
  "ai": false,
  "aiProvider": null,
  "aiModel": null,
  "authorUsername": "member1",
  "createdAt": "2026-05-31T00:00:00Z",
  "updatedAt": "2026-05-31T00:00:00Z"
}
```

### `POST /api/v1/posts`

인증: 필요

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하 |
| `bodyBase64` | 아니오 | UTF-8 body를 Base64로 인코딩한 값. 누락 또는 빈 값이면 빈 본문으로 저장 |
| `mode` | 아니오 | 기본 `NORMAL`. 수동 `FILE_CONVERSION_REQUEST` 생성은 거부 |
| `attachments` | 아니오 | 첨부파일. 같은 이름 `attachments`로 여러 개 전송 가능(최대 5개, 파일당 100MB) |

응답: 게시글 상세(`authorUsername`은 요청 JWT의 subject), HTTP 201

### `PUT /api/v1/posts/{id}`

인증: 필요

권한: 작성자 본인 또는 `ADMIN`. `authorUsername`이 없는 레거시 글은 `ADMIN`만 수정 가능. 그 외는 `403 FORBIDDEN`.

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하 |
| `bodyBase64` | 아니오 | UTF-8 body를 Base64로 인코딩한 값. 누락 또는 빈 값이면 빈 본문으로 저장 |
| `mode` | 아니오 | 기본 `NORMAL` |
| `attachments` | 아니오 | 추가할 새 첨부파일. 같은 이름으로 여러 개 전송 가능 |
| `removeAttachmentIds` | 아니오 | 삭제할 기존 첨부파일 id. 여러 개 전송 가능 |

응답: 게시글 상세, HTTP 200

제약:

- 새 `attachments` 추가와 `removeAttachmentIds` 삭제는 한 요청에서 함께 보낼 수 있습니다(일부 삭제 + 일부 추가).
- 삭제·추가 반영 후 게시글의 총 첨부파일 수가 5개를 넘으면 거부됩니다(`INVALID_ATTACHMENT_REQUEST`).
- `removeAttachmentIds`에 해당 게시글의 첨부가 아닌 id가 있으면 거부됩니다(`INVALID_ATTACHMENT_REQUEST`).
- `mode=FILE_CONVERSION_REQUEST`는 생성과 수정 모두에서 거부됩니다. 파일 변환 게시글은 업로드 세션 finalize로만 만들어집니다.
- `FILE_CONVERSION_REQUEST` 게시글에 첨부파일이 있으면 수정할 수 없습니다.

### `DELETE /api/v1/posts/{id}`

인증: 필요

권한: 작성자 본인 또는 `ADMIN`. `authorUsername`이 없는 레거시 글은 `ADMIN`만 삭제 가능. 그 외는 `403 FORBIDDEN`.

응답: HTTP 204

### `POST /api/v1/posts/batch-delete`

인증: 필요

권한: 요청에 포함된 모든 id에 대해 작성자 본인 또는 `ADMIN`이어야 한다. 하나라도 권한이 없으면 전체 요청이 `403 FORBIDDEN`으로 실패하며 삭제되지 않는다.

요청:

```json
{
  "ids": [1, 2, 3]
}
```

응답: HTTP 204

존재하지 않는 id는 무시됩니다.

## Replies

### `POST /api/v1/posts/{id}/replies`

인증: 필요

요청:

```json
{
  "bodyBase64": "..."
}
```

응답: 게시글 상세, HTTP 201

### `PUT /api/v1/posts/replies/{replyId}`

인증: 필요

권한: 댓글 작성자 본인 또는 `ADMIN`. `authorUsername`이 없는 레거시 일반 댓글은 `ADMIN`만 수정 가능. 그 외는 `403 FORBIDDEN`.

요청:

```json
{
  "bodyBase64": "..."
}
```

응답: 게시글 상세, HTTP 200

제약: AI 답변은 수정할 수 없습니다.

### `DELETE /api/v1/posts/replies/{replyId}`

인증: 필요

권한: 댓글 작성자 본인 또는 `ADMIN`. `authorUsername`이 없는 레거시 일반 댓글은 `ADMIN`만 삭제 가능. 그 외는 `403 FORBIDDEN`.

응답: HTTP 204

제약: AI 답변은 삭제할 수 없습니다.

## AI replies

> 2026-09-03 이후 미사용 — 댓글 AI 답변 기능 종료. `POST /api/v1/posts/{id}/ai-replies`는 410 `AI_REPLY_DISABLED`를 반환하는 비활성 스텁으로만 유지됩니다. 아래 내용은 종료 이전 동작의 기록입니다.

### `POST /api/v1/posts/{id}/ai-replies`

인증: 필요

요청:

```json
{
  "provider": "GPT"
}
```

허용 provider:

- `GPT`
- `CLAUDE`
- `GROK`

응답: 게시글 상세, HTTP 201 (2026-09-03 이후 종료되어 현재는 410 `AI_REPLY_DISABLED`)

제약:

- `FILE_CONVERSION_REQUEST` 게시글에는 AI 답변을 생성할 수 없습니다.
- provider API key가 없으면 `AI_PROVIDER_NOT_CONFIGURED` 오류가 납니다.

## Attachments

### `GET /api/v1/posts/{id}/attachments/{attachmentId}`

인증: 필요 없음

`attachmentId`는 상세 응답 `attachments[].id`(또는 `downloadUrl`)에서 얻습니다. 해당 첨부가 그 게시글의 것이 아니면 404입니다.

응답:

- 파일 stream
- `Content-Disposition: attachment`
- `Content-Type`은 저장된 content type이 있으면 사용하고, 없으면 `application/octet-stream`

## Upload sessions

업로드 세션 API는 모두 인증이 필요합니다. 요청/응답 body는 의미 있는 필드명이 아니라 alias 필드와 AES-GCM 암호문을 사용합니다.

### `POST /api/v1/upload-sessions`

요청 alias:

| Alias | 평문 의미 |
| --- | --- |
| `A1` | archiveName |
| `A2` | fileSizeBytes |
| `A3` | chunkSizeBase64Chars |
| `A4` | totalChunks |
| `A5` | fileSha256 |

응답 alias:

| Alias | 평문 의미 |
| --- | --- |
| `A6` | sessionId |
| `A1` | archiveName |
| `A2` | fileSizeBytes |
| `A3` | chunkSizeBase64Chars |
| `A4` | totalChunks |
| `A7` | uploadedChunks |
| `A8` | complete |
| `A9` | expiresAt |

### `GET /api/v1/upload-sessions/{sessionId}`

해당 세션의 암호화된 status를 반환합니다. 세션 생성자와 같은 JWT subject만 접근할 수 있습니다.

### `POST /api/v1/upload-sessions/{sessionId}/chunks`

요청 alias:

| Alias | 평문 의미 |
| --- | --- |
| `A10` | chunkNumber |
| `A11` | chunkDataBase64 |

동일 chunk를 다시 보내면 이미 저장된 chunk로 보고 현재 status를 반환합니다.

### `POST /api/v1/upload-sessions/{sessionId}/finalize`

모든 chunk가 업로드된 뒤 호출합니다. 성공하면 `FILE_CONVERSION_REQUEST` 게시글 상세를 반환합니다.

검증:

- chunk 번호가 1부터 연속인지 확인
- 각 chunk decode 크기 확인
- 조립된 파일 크기 확인
- 조립된 파일 SHA-256 확인

## 주요 오류 코드

| 코드 | 의미 |
| --- | --- |
| `INVALID_CREDENTIALS` | 인증 실패, 토큰 누락/만료, 삭제된 계정. 단, `/api/v1/auth/me`는 body 없이 401을 반환 |
| `FORBIDDEN` | 권한 없음. 사용자 관리 API를 USER가 호출한 경우, 남의 게시글/댓글을 수정/삭제하려는 경우(작성자 본인/ADMIN 아님), 레거시(작성자 없음) 글/댓글을 USER가 수정/삭제하려는 경우 |
| `DUPLICATE_USERNAME` | 사용자 추가 시 username 중복 |
| `LAST_ADMIN_PROTECTED` | 마지막 남은 ADMIN 삭제/강등 불가 |
| `SELF_DELETE_NOT_ALLOWED` | 자기 자신의 계정 삭제 불가 |
| `INVALID_REQUEST` | validation 또는 JSON parsing 실패 |
| `INVALID_ENCODED_BODY` | bodyBase64 decode 실패 |
| `FILE_CONVERSION_LOCKED` | 파일 변환 게시글 수정 불가 |
| `AI_REPLY_LOCKED` | AI 답변 수정/삭제 불가 |
| `AI_REPLY_NOT_ALLOWED` | 해당 게시글에 AI 답변 생성 불가 |
| `INVALID_AI_PROVIDER` | provider 값 오류 |
| `INVALID_ATTACHMENT_REQUEST` | 첨부파일 요청 조합 오류 |
| `INVALID_FILE_CONVERSION_REQUEST` | 수동 파일 변환 게시글 생성/수정 요청 오류 |
| `AI_PROVIDER_NOT_CONFIGURED` | provider API key 누락 |
| `AI_REPLY_GENERATION_FAILED` | 외부 AI API 호출 실패 |
| `ATTACHMENT_TOO_LARGE` | 일반 첨부파일 또는 최종 생성 첨부파일 크기 초과 |
| `ATTACHMENT_STORAGE_ERROR` | 파일 저장/읽기/삭제 실패 |
| `INVALID_UPLOAD_SESSION_REQUEST` | 업로드 세션 요청 오류. 청크 크기/번호/해시 불일치 포함 |
| `UPLOAD_SESSION_STATE_ERROR` | 만료, 완료, finalizing 상태 오류 |
| `NOT_FOUND` | 리소스 없음 |
| `INTERNAL_ERROR` | 예상하지 못한 서버 오류 |
