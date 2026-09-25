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
  "userId": 1,
  "username": "admin",
  "role": "ADMIN"
}
```

`role`은 `ADMIN`(관리자) 또는 `USER`(일반사용자)입니다.

오류:

- 없는 아이디와 틀린 비밀번호는 같은 401 `INVALID_CREDENTIALS`와 같은 메시지를 반환합니다. 없는 아이디도 같은 비용의 비밀번호 해시 비교를 거쳐 응답 시간으로 아이디 존재 여부를 구분하기 어렵게 합니다.
- 클라이언트 IP마다 `APP_AUTH_LOGIN_WINDOW`(기본 15분) 동안 `APP_AUTH_LOGIN_MAX_FAILURES`(기본 10)번까지 시도할 수 있습니다. 로그인에 성공하면 그 IP의 기록이 지워집니다. 한도를 넘으면 비밀번호가 맞아도 HTTP 429 `TOO_MANY_LOGIN_ATTEMPTS`와 `Retry-After`(남은 초) 헤더를 반환합니다. 기록은 백엔드 메모리에만 있어 재시작하면 초기화됩니다.
- 입력 validation 실패(빈 비밀번호 등)는 400 `INVALID_REQUEST`이며 시도 횟수에 들어가지 않습니다.

### `GET /api/v1/auth/me`

인증: 필요

토큰 누락·만료·구형 토큰·삭제된 계정이면 공통 오류 JSON과 함께 HTTP 401 `INVALID_CREDENTIALS`를 반환합니다. JWT subject는 계정 ID이며 `tokenVersion=2`가 필요합니다.

응답:

```json
{
  "userId": 1,
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
- 마지막 남은 ADMIN을 USER로 강등할 수 없습니다. 위반 시 409 `LAST_ADMIN_PROTECTED`입니다. ADMIN을 강등·삭제하는 요청은 ADMIN 행을 잠근 뒤 판단하므로, 관리자 둘이 동시에 서로를 강등·삭제해도 한쪽은 이 오류로 거부됩니다.

### `DELETE /api/v1/users/{id}`

인증: 필요(ADMIN 전용)

응답: HTTP 204

제약:

- 마지막 남은 ADMIN은 삭제할 수 없습니다. 위반 시 409 `LAST_ADMIN_PROTECTED`입니다.
- 자기 자신의 계정은 삭제할 수 없습니다. 위반 시 409 `SELF_DELETE_NOT_ALLOWED`입니다.
- 삭제된 계정의 기존 JWT는 모든 보호 API에서 거부됩니다. 같은 username으로 재등록해도 이전 토큰 및 작성자 권한을 물려받지 않습니다.

글·댓글 응답의 `authorUserId`는 nullable 계정 ID입니다. 권한 판정에는 이 ID를 사용하고, `authorUsername`은 표시용으로만 사용합니다. 삭제된 계정이나 연결을 확정하지 못한 레거시 데이터는 `authorUserId=null`일 수 있습니다.

## Posts

### `GET /api/v1/posts?page=1&query=keyword`

인증: 필요 없음

`query`는 선택이며 **제목**만 대소문자 구분 없이 부분 일치로 찾습니다(본문은 검색하지 않음). 검색어의 `%`·`_`는 와일드카드가 아니라 글자 그대로 찾습니다. NUL 문자가 들어 있으면 400 `INVALID_REQUEST`입니다. 목록은 `createdAt` 내림차순이고, 같은 시각이면 `id` 내림차순이라 페이지 경계에서 순서가 바뀌지 않습니다.

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
  "authorUserId": 2,
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
  "bodyFormat": "PLAIN_TEXT",
  "bodyDocument": null,
  "mode": "NORMAL",
  "conversionReady": false,
  "authorUsername": "member1",
  "authorUserId": 2,
  "createdAt": "2026-05-31T00:00:00Z",
  "updatedAt": "2026-05-31T00:00:00Z",
  "attachments": [],
  "replies": []
}
```

`bodyFormat`은 `PLAIN_TEXT` 또는 `TIPTAP_JSON`입니다. `body`는 두 형식 모두 항상 평문이며, rich 글은 서버가 검증된 문서에서 추출한 텍스트입니다(본문 복사용). `bodyDocument`는 검증·정규화된 Tiptap JSON 객체이고 `TIPTAP_JSON` 글에서만 값이 있으며, `PLAIN_TEXT` 글은 `null`입니다. 두 필드는 additive이므로 새 backend와 기존 클라이언트를 조합해도 클라이언트는 `body` 평문을 그대로 표시할 수 있습니다.

rich 글의 본문 필드 예시:

```json
{
  "body": "텍스트\n[이미지: 스크린샷]",
  "bodyFormat": "TIPTAP_JSON",
  "bodyDocument": {
    "type": "doc",
    "content": [
      {
        "type": "paragraph",
        "content": [{ "type": "text", "text": "텍스트" }]
      },
      {
        "type": "inlineAttachmentImage",
        "attrs": {
          "imageKey": "b1e09b73-1111-4444-8888-123456789abc",
          "alt": "스크린샷"
        }
      }
    ]
  }
}
```

`inlineAttachmentImage`는 블록 노드입니다. 문단(`paragraph`)이나 제목 안에는 넣을 수 없고 `doc`·`blockquote`의 자식, 또는 `listItem`의 두 번째 이후 자식으로만 올 수 있으며, 위반하면 400 `INVALID_RICH_DOCUMENT`입니다. 평문 `body`에서는 블록 사이가 줄바꿈으로 이어집니다.

`attachments`는 첨부파일 배열입니다(없으면 빈 배열). 각 항목은 다음 형식이며, 일반 게시글은 일반 첨부와 inline 이미지를 합쳐 최대 5개까지 가질 수 있습니다. 업로드 세션 finalize로 만들어진 `FILE_CONVERSION_REQUEST` 게시글은 항상 1개(원본 ZIP)입니다.

```json
{
  "id": 10,
  "originalFilename": "archive.zip",
  "size": 12345,
  "contentType": "application/zip",
  "attachmentKind": "DOWNLOAD",
  "inlineKey": null,
  "downloadUrl": "/api/v1/posts/1/attachments/10",
  "contentUrl": null
}
```

`attachmentKind`는 `DOWNLOAD`(일반 첨부) 또는 `INLINE_IMAGE`(본문 이미지)입니다. `inlineKey`는 본문 이미지의 문서 참조 UUID이고 `DOWNLOAD`는 `null`입니다. `contentUrl`은 본문 표시 전용 URL이고 `DOWNLOAD`는 `null`입니다.

```json
{
  "id": 11,
  "originalFilename": "inline-image-1758412800000-b1e09b73.png",
  "size": 20480,
  "contentType": "image/png",
  "attachmentKind": "INLINE_IMAGE",
  "inlineKey": "b1e09b73-1111-4444-8888-123456789abc",
  "downloadUrl": "/api/v1/posts/1/attachments/11",
  "contentUrl": "/api/v1/posts/1/attachments/11/content"
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
  "authorUserId": 2,
  "createdAt": "2026-05-31T00:00:00Z",
  "updatedAt": "2026-05-31T00:00:00Z"
}
```

### rich 본문 형식과 inline 이미지 (생성·수정 공통)

게시글 생성·수정 multipart에서 본문 형식을 지정하고, 편집기에 붙여넣거나 파일 선택으로 추가한 본문 이미지를 같은 요청으로 전송할 수 있습니다. 기존 plain 요청 필드는 그대로 유지됩니다.

| 필드 | 형식 | 규칙 |
| --- | --- | --- |
| `bodyFormat` | 문자열 | `PLAIN_TEXT`(기본) 또는 `TIPTAP_JSON`. 누락하면 `PLAIN_TEXT` |
| `bodyBase64` | Base64 UTF-8 | `PLAIN_TEXT` 전용 본문. `TIPTAP_JSON`과 함께 보내면 거부 |
| `bodyDocumentBase64` | Base64 UTF-8 JSON | `TIPTAP_JSON`에서 필수인 Tiptap 문서 |
| `inlineImageManifestBase64` | Base64 UTF-8 JSON | 신규 inline 이미지가 있으면 필수인 manifest 배열 |
| `inlineImages` | 반복 multipart file | manifest `fileIndex`와 연결되는 이미지 파일. 같은 이름으로 순서대로 전송 |

manifest 형식(Base64 인코딩 전 JSON):

```json
[
  {
    "imageKey": "b1e09b73-1111-4444-8888-123456789abc",
    "fileIndex": 0
  }
]
```

검증 규칙:

- `bodyBase64`와 `bodyDocumentBase64`는 한 요청에 함께 보낼 수 없습니다. `PLAIN_TEXT`에서 `bodyDocumentBase64`만 보내거나 `TIPTAP_JSON`에서 `bodyBase64`를 보내도 거부되며, 모두 400 `INVALID_RICH_DOCUMENT`입니다.
- `TIPTAP_JSON`은 `bodyDocumentBase64`가 필수입니다. 문서는 `doc` 루트와 허용 node·mark·attribute만 가질 수 있고, Base64 decode 5MiB·추출 평문 1,000,000자·node 20,000개·깊이 20 한도를 통과해야 합니다.
- manifest와 `inlineImages`는 개수가 정확히 같아야 합니다. 한쪽만 보내면 400 `INVALID_ATTACHMENT_REQUEST`입니다.
- `fileIndex`는 `0..n-1`을 중복 없이 정확히 한 번씩 사용해야 합니다.
- manifest `imageKey`는 UUID여야 하고 중복될 수 없습니다.
- 생성 시 문서가 참조하는 `imageKey` 집합과 manifest `imageKey` 집합이 정확히 같아야 합니다.
- 수정 시 문서의 `imageKey`는 이 글에 유지되는 기존 inline 이미지 키이거나 이번 요청의 신규 manifest 키여야 합니다. 다른 글의 키나 존재하지 않는 키는 거부됩니다. 신규 업로드에 기존 키를 재사용하거나 manifest 키를 문서에서 참조하지 않아도 거부됩니다.
- 수정에서 새 문서가 참조하지 않는 기존 inline 이미지는 서버가 삭제 대상으로 계산해 자동 삭제합니다.
- inline 이미지는 PNG/JPEG만 허용합니다. 서버가 `ImageIO`로 실제 바이트의 형식과 dimensions를 검증하고, 파일당 기본 10MB(`APP_ATTACHMENTS_INLINE_IMAGES_MAX_FILE_SIZE`), 너비·높이 각 8192px, 총 25,000,000px 한도를 적용합니다. 검증된 형식이 저장 `contentType`이 됩니다.
- 일반 첨부와 inline 이미지의 합계는 게시글당 최대 5개입니다. 개수 검증은 파일 저장·DB 변경 전에 수행됩니다.
- 위반 오류는 400 `INVALID_RICH_DOCUMENT`(문서·본문 필드), 400 `INVALID_ATTACHMENT_REQUEST`(manifest·파일·문서 key 불일치, 개수 초과), 413 `ATTACHMENT_TOO_LARGE`(파일 크기 초과)입니다.

### `POST /api/v1/posts`

인증: 필요

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하. NUL 문자가 있으면 400 `INVALID_REQUEST` |
| `bodyBase64` | 아니오 | `PLAIN_TEXT` 본문. UTF-8 body를 Base64로 인코딩한 값. 누락 또는 빈 값이면 빈 본문으로 저장. 디코딩한 본문에 NUL 문자가 있으면 400 `INVALID_ENCODED_BODY` |
| `bodyFormat` | 아니오 | `PLAIN_TEXT`(기본) 또는 `TIPTAP_JSON` |
| `bodyDocumentBase64` | 아니오 | `TIPTAP_JSON`일 때 필수인 Base64 문서 |
| `inlineImageManifestBase64` | 아니오 | 신규 inline 이미지가 있을 때 필수인 manifest(위 "rich 본문 형식과 inline 이미지" 참조) |
| `inlineImages` | 아니오 | 본문 이미지 파일. 같은 이름으로 여러 개 전송 |
| `mode` | 아니오 | 기본 `NORMAL`. 수동 `FILE_CONVERSION_REQUEST` 생성은 거부 |
| `attachments` | 아니오 | 첨부파일. 같은 이름 `attachments`로 여러 개 전송 가능(일반 첨부 파일당 100MB, 본문 이미지 포함 합계 최대 5개) |

응답: 게시글 상세(`authorUserId`는 계정 ID, `authorUsername`은 작성 시점의 표시 이름), HTTP 201

일반 첨부의 파일명과 Content-Type은 클라이언트 값을 그대로 믿지 않고 저장 전에 정리합니다(생성·수정 공통).

- 파일명: `/`·`\` 앞의 경로와 제어 문자(NUL 포함)를 지우고 255자로 자릅니다. 자를 때는 짧은 영문·숫자 확장자를 남깁니다. 남는 이름이 없으면 `attachment`입니다.
- Content-Type: 해석할 수 없거나 wildcard(`*/*`, `text/*`)이거나 255자를 넘으면 `application/octet-stream`으로 저장합니다. 없으면 없는 대로 둡니다.

### `PUT /api/v1/posts/{id}`

인증: 필요

권한: 작성자 본인 또는 `ADMIN`. `authorUserId`가 없는 레거시 글은 `ADMIN`만 수정 가능. 그 외는 `403 FORBIDDEN`.

Content-Type: `multipart/form-data`

필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `title` | 예 | 200자 이하. NUL 문자가 있으면 400 `INVALID_REQUEST` |
| `bodyBase64` | 아니오 | `PLAIN_TEXT` 본문. UTF-8 body를 Base64로 인코딩한 값. 누락 또는 빈 값이면 빈 본문으로 저장. 디코딩한 본문에 NUL 문자가 있으면 400 `INVALID_ENCODED_BODY` |
| `bodyFormat` | 아니오 | `PLAIN_TEXT`(기본) 또는 `TIPTAP_JSON`. 기존 rich 글은 `TIPTAP_JSON`이 필수 |
| `bodyDocumentBase64` | 아니오 | `TIPTAP_JSON`일 때 필수인 Base64 문서 |
| `inlineImageManifestBase64` | 아니오 | 이번 수정에서 추가하는 신규 inline 이미지 manifest |
| `inlineImages` | 아니오 | 이번 수정에서 추가하는 신규 본문 이미지 파일 |
| `mode` | 아니오 | 기본 `NORMAL` |
| `attachments` | 아니오 | 추가할 새 일반 첨부파일. 같은 이름으로 여러 개 전송 가능 |
| `removeAttachmentIds` | 아니오 | 삭제할 기존 `DOWNLOAD` 첨부 id. 여러 개 전송 가능 |

응답: 게시글 상세, HTTP 200

제약:

- 새 `attachments` 추가와 `removeAttachmentIds` 삭제는 한 요청에서 함께 보낼 수 있습니다(일부 삭제 + 일부 추가).
- 삭제·추가 반영 후 게시글의 총 첨부파일 수가 5개를 넘으면 거부됩니다(`INVALID_ATTACHMENT_REQUEST`). 이때 일반 첨부와 inline 이미지의 합계를 계산합니다.
- `removeAttachmentIds`에 해당 게시글의 첨부가 아닌 id가 있으면 거부됩니다(`INVALID_ATTACHMENT_REQUEST`).
- `removeAttachmentIds`는 `DOWNLOAD` 첨부 전용입니다. `INLINE_IMAGE` id를 넣으면 400 `INVALID_ATTACHMENT_REQUEST`이며, inline 이미지 삭제는 문서에서 참조를 제거해 서버가 자동 계산하게 합니다.
- 기존 `TIPTAP_JSON` 글의 수정 요청에서 `bodyFormat`이 누락되거나 `PLAIN_TEXT`이면 저장하지 않고 409 `RICH_TEXT_CLIENT_REQUIRED`를 반환합니다. 구형 plain 클라이언트가 rich 문서와 inline 이미지를 유실시키는 것을 막습니다.
- `PLAIN_TEXT` 글을 `TIPTAP_JSON`으로 전환하는 수정은 허용되지만, rich 글을 다시 plain으로 되돌리는 요청은 제공하지 않습니다.
- rich 본문·inline 이미지 필드의 manifest·key 검증은 위 "rich 본문 형식과 inline 이미지" 절을 따릅니다.
- `mode=FILE_CONVERSION_REQUEST`는 생성과 수정 모두에서 거부됩니다. 파일 변환 게시글은 업로드 세션 finalize로만 만들어집니다.
- `FILE_CONVERSION_REQUEST` 게시글에 첨부파일이 있으면 수정할 수 없습니다.

### `DELETE /api/v1/posts/{id}`

인증: 필요

권한: 작성자 본인 또는 `ADMIN`. `authorUserId`가 없는 레거시 글은 `ADMIN`만 삭제 가능. 그 외는 `403 FORBIDDEN`.

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

디코딩한 본문에 NUL 문자가 있으면 400 `INVALID_ENCODED_BODY`입니다(수정도 같음).

### `PUT /api/v1/posts/replies/{replyId}`

인증: 필요

권한: 댓글 작성자 본인 또는 `ADMIN`. `authorUserId`가 없는 레거시 일반 댓글은 `ADMIN`만 수정 가능. 그 외는 `403 FORBIDDEN`.

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

권한: 댓글 작성자 본인 또는 `ADMIN`. `authorUserId`가 없는 레거시 일반 댓글은 `ADMIN`만 삭제 가능. 그 외는 `403 FORBIDDEN`.

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

`attachmentId`는 상세 응답 `attachments[].id`(또는 `downloadUrl`)에서 얻습니다. 해당 첨부가 그 게시글의 것이 아니면 404입니다. `INLINE_IMAGE` 첨부도 이 URL로 내려받을 수 있고 항상 `Content-Disposition: attachment`입니다.

응답:

- 파일 stream
- `Content-Disposition: attachment`
- `Content-Type`은 저장된 content type이 있으면 사용하고, 없거나 응답 헤더로 쓸 수 없는 값(예전에 검증 없이 저장된 값 포함)이면 `application/octet-stream`

### `GET /api/v1/posts/{id}/attachments/{attachmentId}/content`

인증: 필요 없음

본문에 표시되는 inline 이미지 전용 URL입니다. `attachmentId`는 상세 응답 `attachments[].contentUrl`에서 얻습니다.

응답:

- 검증된 이미지 파일 stream
- `Content-Type`: `image/png` 또는 `image/jpeg`. 저장 시 서버가 실제 바이트를 검증해 기록한 값만 사용합니다.
- `Content-Disposition: inline`
- `X-Content-Type-Options: nosniff`
- `Cache-Control: public, max-age=31536000, immutable`

제약:

- 해당 게시글의 `INLINE_IMAGE` 첨부만 반환합니다. 존재하지 않는 글·첨부, 다른 글의 첨부, `DOWNLOAD` 첨부, 저장 content type이 PNG/JPEG가 아닌 첨부는 모두 404 `NOT_FOUND`입니다.
- 첨부 ID가 바뀌지 않으므로 응답을 1년간 immutable로 캐시합니다.

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

해당 세션의 암호화된 status를 반환합니다. 세션의 `created_by_user_id`와 JWT의 계정 ID가 일치해야 접근할 수 있습니다.

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

| 코드 | HTTP | 의미 |
| --- | --- | --- |
| `INVALID_CREDENTIALS` | 401 | 인증 실패, 토큰 누락/만료, 삭제된 계정. `/api/v1/auth/me`도 같은 공통 오류 JSON을 반환 |
| `TOO_MANY_LOGIN_ATTEMPTS` | 429 | 같은 클라이언트 IP의 로그인 시도 한도 초과. `Retry-After` 헤더에 남은 초 |
| `FORBIDDEN` | 403 | 권한 없음. 사용자 관리 API를 USER가 호출한 경우, 남의 게시글/댓글을 수정/삭제하려는 경우(작성자 본인/ADMIN 아님), 레거시(작성자 없음) 글/댓글을 USER가 수정/삭제하려는 경우 |
| `DUPLICATE_USERNAME` | 409 | 사용자 추가 시 username 중복 |
| `LAST_ADMIN_PROTECTED` | 409 | 마지막 남은 ADMIN 삭제/강등 불가 |
| `SELF_DELETE_NOT_ALLOWED` | 409 | 자기 자신의 계정 삭제 불가 |
| `INVALID_REQUEST` | 400 | validation 또는 JSON parsing 실패, 형식이 깨진 multipart 요청, 필수 요청 파라미터 누락, 제목·검색어의 NUL 문자, DB가 저장할 수 없는 값(SQLState 22xxx: 길이 초과·NUL 등) |
| `METHOD_NOT_ALLOWED` | 405 | 해당 경로가 지원하지 않는 HTTP 메서드. `Allow` 헤더로 허용 메서드를 알려줌 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 Content-Type (예: 게시글 생성·수정에 multipart가 아닌 JSON) |
| `NOT_ACCEPTABLE` | 406 | 요청한 `Accept` 형식으로 응답할 수 없음 |
| `INVALID_ENCODED_BODY` | 400 | bodyBase64 decode 실패, 디코딩한 본문의 NUL 문자 |
| `INVALID_RICH_DOCUMENT` | 400 | rich 문서 검증 실패. `bodyDocumentBase64` decode/JSON/schema/한도 위반, `bodyBase64`와 `bodyDocumentBase64` 동시 전송 |
| `FILE_CONVERSION_LOCKED` | 403 | 파일 변환 게시글 수정 불가 |
| `AI_REPLY_LOCKED` | 403 | AI 답변 수정/삭제 불가 |
| `AI_REPLY_NOT_ALLOWED` | 400 | 해당 게시글에 AI 답변 생성 불가 |
| `INVALID_AI_PROVIDER` | 400 | provider 값 오류 |
| `INVALID_ATTACHMENT_REQUEST` | 400 | 첨부파일 요청 조합 오류. 일반 첨부 + inline 이미지 합계 5개 초과, manifest·파일·문서 key 불일치, `fileIndex`/`imageKey` 중복, `removeAttachmentIds`에 다른 글 id나 `INLINE_IMAGE` id 포함 |
| `INVALID_FILE_CONVERSION_REQUEST` | 400 | 수동 파일 변환 게시글 생성/수정 요청 오류 |
| `RICH_TEXT_CLIENT_REQUIRED` | 409 | 기존 rich 글을 `bodyFormat` 누락 또는 `PLAIN_TEXT` 요청으로 수정 시도 |
| `AI_PROVIDER_NOT_CONFIGURED` | 503 | provider API key 누락 |
| `AI_REPLY_GENERATION_FAILED` | 502 | 외부 AI API 호출 실패 |
| `AI_REPLY_DISABLED` | 410 | AI 답변 기능 종료. 신규 생성 요청 거부 |
| `ATTACHMENT_TOO_LARGE` | 413 | 일반 첨부파일, inline 이미지 또는 최종 생성 첨부파일 크기 초과. multipart 텍스트 필드(본문 등) 합계가 8MB를 넘어도 같은 코드 |
| `ATTACHMENT_STORAGE_ERROR` | 500 | 파일 저장/읽기/삭제 실패 |
| `INVALID_UPLOAD_SESSION_REQUEST` | 400 | 업로드 세션 요청 오류. 청크 크기/번호/해시 불일치 포함 |
| `UPLOAD_SESSION_STATE_ERROR` | 409 | 만료, 완료, finalizing 상태 오류 |
| `CONFLICT` | 409 | DB 무결성(unique·FK 등)·동시성 충돌. 응답 메시지는 상세 원인을 노출하지 않음 |
| `NOT_FOUND` | 404 | 리소스 없음. 다른 글의 첨부, `DOWNLOAD` 첨부의 content 요청 포함 |
| `INTERNAL_ERROR` | 500 | 예상하지 못한 서버 오류. 응답 메시지는 고정 문구(`unexpected server error`)이고 원인은 백엔드 로그(`GlobalExceptionHandler`)에 남음 |
