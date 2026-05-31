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
  "username": "admin",
  "password": "admin"
}
```

제약:

- `username`은 영문과 숫자만 허용합니다.
- `password`는 빈 값이면 안 됩니다.

응답:

```json
{
  "token": "<jwt>",
  "username": "admin"
}
```

### `GET /api/v1/auth/me`

인증: 필요

토큰 누락 또는 무효 토큰이면 body 없이 HTTP 401을 반환합니다.

응답:

```json
{
  "username": "admin"
}
```

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
  "createdAt": "2026-05-31T00:00:00Z",
  "updatedAt": "2026-05-31T00:00:00Z",
  "attachment": null,
  "replies": []
}
```

`attachment`가 있으면 다음 형식입니다.

```json
{
  "id": 10,
  "originalFilename": "archive.zip",
  "size": 12345,
  "contentType": "application/zip",
  "downloadUrl": "/api/v1/posts/1/attachment"
}
```

### `POST /api/v1/posts`

인증: 필요

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하 |
| `bodyBase64` | 예 | UTF-8 body를 Base64로 인코딩한 값 |
| `mode` | 아니오 | 기본 `NORMAL`. 수동 `FILE_CONVERSION_REQUEST` 생성은 거부 |
| `attachment` | 아니오 | 첨부파일 |

응답: 게시글 상세, HTTP 201

### `PUT /api/v1/posts/{id}`

인증: 필요

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하 |
| `bodyBase64` | 예 | UTF-8 body를 Base64로 인코딩한 값 |
| `mode` | 아니오 | 기본 `NORMAL` |
| `attachment` | 아니오 | 새 첨부파일 |
| `removeAttachment` | 아니오 | `true`면 기존 첨부파일 삭제 |

응답: 게시글 상세, HTTP 200

제약:

- `removeAttachment=true`와 새 `attachment`를 동시에 보낼 수 없습니다.
- `mode=FILE_CONVERSION_REQUEST`는 생성과 수정 모두에서 거부됩니다. 파일 변환 게시글은 업로드 세션 finalize로만 만들어집니다.
- `FILE_CONVERSION_REQUEST` 게시글에 첨부파일이 있으면 수정할 수 없습니다.

### `DELETE /api/v1/posts/{id}`

인증: 필요

응답: HTTP 204

### `POST /api/v1/posts/batch-delete`

인증: 필요

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

응답: HTTP 204

제약: AI 답변은 삭제할 수 없습니다.

## AI replies

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

응답: 게시글 상세, HTTP 201

제약:

- `FILE_CONVERSION_REQUEST` 게시글에는 AI 답변을 생성할 수 없습니다.
- provider API key가 없으면 `AI_PROVIDER_NOT_CONFIGURED` 오류가 납니다.

## Attachments

### `GET /api/v1/posts/{id}/attachment`

인증: 필요 없음

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
| `INVALID_CREDENTIALS` | 인증 실패 또는 토큰 누락/만료. 단, `/api/v1/auth/me`는 body 없이 401을 반환 |
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
