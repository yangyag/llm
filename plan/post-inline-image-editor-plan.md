# 게시글 본문 이미지 붙여넣기·영구 저장 구현 계획서

- 상태: 구현 진행 — Phase 0~1 완료, Phase 2 미착수
- 작성일: 2026-09-20
- 대상: `front/` Nuxt 3·Vue 3 게시글 작성/수정/조회, `back/` Spring Boot 게시판·첨부 모듈, Flyway
- 목표: 게시글 작성·수정 중 클립보드 이미지를 즉시 본문에 표시하고, 게시글 저장 후에도 텍스트·이미지 순서와 위치를 유지한다.
- 저장 원칙: 이미지 바이트는 PostgreSQL에 저장하지 않고 기존 첨부파일 volume을 재사용한다. DB에는 본문 문서와 이미지-첨부 연결 metadata만 저장한다.
- 실행 원칙: Phase 단위로 구현·검증·기록한다. 작업 세션 사용량이 낮으면 다음 Phase를 시작하지 않고, 확인 수단이 없어 `알 수 없음`이면 Phase 내부를 더 작은 체크포인트로 나눠 보수적으로 진행한다.

이 문서는 구현 중 갱신하는 실행 계획서다. 완료 표시는 실제 코드와 검증 결과가 일치할 때만 변경한다. 구현 도중 설계 변경이 필요하면 먼저 이 문서의 결정·계약·Phase 범위를 수정하고, 변경 이유를 재개 기록에 남긴다.

## 1. 최종 사용자 동작

### 게시글 작성

1. 사용자가 본문 편집기에 텍스트를 작성한다.
2. 화면 캡처 또는 로컬 애플리케이션에서 복사한 PNG/JPEG 이미지를 본문 위치에 붙여넣는다.
3. 브라우저가 클립보드 이미지 `File`을 메모리에 보관하고 `blob:` URL로 즉시 본문에 표시한다.
4. 이미지마다 브라우저에서 UUID `imageKey`를 생성한다.
5. 등록 전에는 서버 파일이나 DB 행을 만들지 않는다.
6. 등록 시 본문 문서 JSON, 이미지 manifest, 이미지 파일, 일반 첨부파일을 하나의 multipart 요청으로 보낸다.
7. 서버가 게시글·본문 문서·첨부 metadata를 같은 DB 트랜잭션에 반영하고 실제 이미지는 기존 첨부 volume에 저장한다.
8. 저장 후 상세 화면에서도 본문의 동일한 위치에 이미지가 표시된다.

### 게시글 수정

1. 기존 rich 본문은 저장된 문서 JSON과 inline attachment metadata로 복원한다.
2. 기존 이미지는 영구 `imageKey`로 표시한다.
3. 새로 붙여넣은 이미지는 작성 화면과 동일하게 임시 `blob:` URL로 표시한다.
4. 본문에서 기존 이미지를 제거하면 저장 시 해당 inline attachment metadata를 삭제하고, 실제 파일은 기존 커밋 후 삭제·재시도 경로로 정리한다.
5. 일반 첨부파일 삭제는 기존 `removeAttachmentIds` 계약을 유지한다.

### 실패·취소

- 등록·수정 요청이 실패하면 브라우저 메모리의 임시 이미지와 편집 내용은 유지하여 재시도할 수 있게 한다.
- pending 이미지 node를 본문에서 삭제해도 undo가 해당 이미지를 복원할 수 있도록 파일과 `blob:` URL은 편집 세션 종료까지 registry에 유지한다. 제출 payload에는 현재 canonical 문서가 참조하는 key만 포함한다.
- 저장 성공, 폼 reset, 편집 화면 정상 이탈, 컴포넌트 unmount에서 registry의 모든 `blob:` URL을 정확히 한 번 `URL.revokeObjectURL`로 해제한다.
- 반복 paste/delete로 브라우저 메모리가 무한히 증가하지 않도록 pending registry는 최대 20개·총 100MB로 제한한다. 초과 시 새 paste를 거부하고 현재 글 저장 또는 편집 취소 후 재시도를 안내한다.
- 브라우저 새로고침·탭 종료 후 초안 복구는 이번 범위에서 제외한다. 필요 시 IndexedDB 초안 저장을 후속 Phase로 별도 설계한다.

## 2. 현재 구조와 변경 필요점

현재 게시글 본문은 일반 `textarea`의 평문이고, 요청에서는 UTF-8 본문을 Base64로 변환한 `bodyBase64`와 일반 첨부 `attachments`를 같은 `FormData`로 전송한다. 상세 화면은 `PostBodyReader`가 문자열을 Vue 텍스트 보간으로 렌더링한다. 첨부파일은 실제 바이트를 volume에 저장하고 DB에는 경로·파일명·content type·크기 metadata를 저장한다.

기존 구조에서 재사용할 부분:

- multipart 게시글 생성·수정 요청
- `AttachmentStorageService`의 UUID 저장명과 경로 검증
- `AttachmentFileLifecycle`의 신규 파일 롤백 정리
- DB 커밋 후 파일 삭제와 영속 삭제 대기열
- 게시글 작성자·ADMIN 수정 권한
- 일반 첨부 최대 개수와 요청 크기 검증
- 공개 게시글 상세 및 공개 첨부 조회 경로

새로 필요한 부분:

- Nuxt/Vue 3용 Tiptap 편집기
- 브라우저 임시 이미지 registry와 `blob:` URL 생명주기
- 평문과 rich JSON을 구분하는 본문 format
- Tiptap JSON 검증·정규화·평문 추출
- rich 문서의 `imageKey`와 inline attachment metadata 연결
- inline 이미지 파일 형식·크기·해상도 검증
- 다운로드와 분리된 inline 이미지 content endpoint
- 생성·수정 시 본문 참조와 파일 집합의 원자적 검증

## 3. 확정 설계 결정

### 3.1 편집기

- Tiptap 오픈소스 코어를 사용한다.
- 라이선스는 MIT인 패키지만 사용하고 Tiptap Platform·Cloud·유료 extension은 사용하지 않는다.
- 직접 의존성은 `@tiptap/core`, `@tiptap/vue-3`, `@tiptap/pm`, `@tiptap/starter-kit`으로 제한한다. custom node가 `@tiptap/core`를 직접 import하므로 transitive dependency에 의존하지 않고 명시적으로 설치한다.
- Phase 0에서 네 패키지를 모두 `3.31.3`으로 확정했다. 2026-09-04 게시된 MIT 버전으로 작성일 기준 7일 유예 조건을 충족한다. Phase 4에서 package manager로 exact version을 설치하고 lockfile을 함께 갱신하며 `latest`, `*`, 비한정 범위를 사용하지 않는다.
- Nuxt SSR/hydration 충돌을 피하도록 클라이언트 전용 컴포넌트와 `immediatelyRender: false`를 사용한다.
- 초기 기능 범위는 기존 평문 작성 경험, 기본 문단·줄바꿈과 inline 이미지다. `StarterKit`의 link extension은 비활성화하고, 복잡한 표·링크 toolbar·협업 편집·외부 embed는 넣지 않는다.

### 3.2 본문 저장 형식

`posts.body`를 rich JSON으로 덮어쓰지 않는다.

- `posts.body`: 검색·본문 복사·레거시 화면 호환을 위한 서버 추출 평문
- `posts.body_format`: `PLAIN_TEXT` 또는 `TIPTAP_JSON`
- `posts.body_document`: 검증·정규화된 Tiptap JSON 문자열, plain 글은 `null`

신규 rich 글은 서버가 `body_document`에서 평문을 추출해 `body`에 저장한다. 이미지 노드는 내부 UUID를 평문에 노출하지 않고, alt가 있으면 `[이미지: alt]`, 없으면 `[이미지]`로 변환한다.

기존 글은 `PLAIN_TEXT`/`body_document=null`로 유지한다. 기존 글을 수정 화면에서 저장하면 그 글만 `TIPTAP_JSON`으로 전환한다. 전체 기존 데이터 일괄 변환은 하지 않는다.

### 3.3 canonical 이미지 노드

영구 저장하는 문서의 이미지 노드는 URL이나 `blob:` URL을 저장하지 않는다.

```json
{
  "type": "inlineAttachmentImage",
  "attrs": {
    "imageKey": "b1e09b73-1111-4444-8888-123456789abc",
    "alt": "붙여넣은 이미지"
  }
}
```

- `imageKey`는 클라이언트가 UUID v4로 생성한다. `crypto.randomUUID()`를 우선 사용하고, 사용할 수 없는 origin에서는 `crypto.getRandomValues()` 기반 UUID v4 fallback을 사용한다. `Math.random()` fallback은 사용하지 않는다.
- canonical 문서에는 `src`, 로컬 경로, Base64 data URL을 허용하지 않는다.
- 편집기 runtime은 `imageKey`를 임시 `blob:` URL 또는 서버 `contentUrl`로 해석하는 custom Vue NodeView를 사용한다.
- canonical serializer는 runtime 전용 속성을 제거하고 `imageKey`와 정규화된 `alt`만 남긴다.

### 3.4 첨부 metadata

기존 `post_attachments`에 다음 필드를 추가한다.

- `attachment_kind`: `DOWNLOAD` 또는 `INLINE_IMAGE`, 기존 행 기본값 `DOWNLOAD`
- `inline_key`: UUID nullable

불변 조건:

- `DOWNLOAD`이면 `inline_key`는 `null`
- `INLINE_IMAGE`이면 `inline_key`는 필수
- `(post_id, inline_key)`는 `inline_key is not null`인 행에서 unique
- `FILE_CONVERSION_REQUEST`가 생성하는 ZIP은 항상 `DOWNLOAD`

일반 첨부와 inline 이미지는 같은 실제 파일 저장소와 삭제 생명주기를 사용한다.

### 3.5 HTTP 계약

기존 plain 요청은 그대로 유지한다.

Rich 생성·수정 multipart 추가 필드:

| 필드 | 형식 | 규칙 |
| --- | --- | --- |
| `bodyFormat` | 문자열 | rich 요청은 `TIPTAP_JSON`; 누락 시 기존 `PLAIN_TEXT` |
| `bodyDocumentBase64` | Base64 UTF-8 JSON | `TIPTAP_JSON`에서 필수 |
| `inlineImageManifestBase64` | Base64 UTF-8 JSON | 신규 inline 이미지가 있을 때 필수 |
| `inlineImages` | 반복 multipart file | manifest의 `fileIndex`와 연결 |

Manifest 형식:

```json
[
  {
    "imageKey": "b1e09b73-1111-4444-8888-123456789abc",
    "fileIndex": 0
  }
]
```

검증 규칙:

- manifest 길이와 신규 파일 수가 정확히 같아야 한다.
- `fileIndex`는 `0..n-1`을 중복 없이 정확히 한 번씩 사용한다.
- manifest의 `imageKey`는 중복될 수 없다.
- 생성 시 문서가 참조한 모든 `imageKey`와 manifest 집합이 같아야 한다.
- 수정 시 문서의 `imageKey`는 해당 글의 유지되는 기존 inline 이미지 또는 이번 요청의 신규 manifest에 있어야 한다.
- 다른 글의 키나 존재하지 않는 키는 거부한다.
- 기존 inline 이미지 중 새 문서가 참조하지 않는 항목은 서버가 삭제 대상으로 계산한다.
- `removeAttachmentIds`는 `DOWNLOAD` 첨부에만 사용한다. inline 이미지 ID를 넣으면 `INVALID_ATTACHMENT_REQUEST`로 거부한다.
- rich 요청에서 `bodyBase64`를 동시에 보내지 않는다. 두 본문 필드가 동시에 오면 모호한 요청으로 거부한다.
- 기존 `TIPTAP_JSON` 글의 수정 요청에서 `bodyFormat`이 누락되거나 `PLAIN_TEXT`이면 저장하지 않고 409 `RICH_TEXT_CLIENT_REQUIRED`로 거부한다. 구형 front가 추출 평문만 저장해 rich 문서와 inline 이미지를 유실시키는 것을 막는다.
- `PLAIN_TEXT` 글은 새 editor에서 `TIPTAP_JSON`으로 전환할 수 있지만, 이번 범위에서는 rich 글을 plain으로 되돌리는 API를 제공하지 않는다.

상세 응답은 기존 `body`를 유지하고 다음을 추가한다.

```json
{
  "body": "서버가 추출한 평문",
  "bodyFormat": "TIPTAP_JSON",
  "bodyDocument": {
    "type": "doc",
    "content": []
  },
  "attachments": [
    {
      "id": 10,
      "attachmentKind": "INLINE_IMAGE",
      "inlineKey": "b1e09b73-1111-4444-8888-123456789abc",
      "downloadUrl": "/api/v1/posts/1/attachments/10",
      "contentUrl": "/api/v1/posts/1/attachments/10/content"
    }
  ]
}
```

기존 클라이언트는 추가 응답 필드를 무시하고 `body` 평문을 계속 표시할 수 있다.

### 3.6 inline content endpoint

```text
GET /api/v1/posts/{postId}/attachments/{attachmentId}/content
```

- 인증: 기존 게시글 상세·첨부와 동일하게 공개
- 대상: 해당 글에 속한 `INLINE_IMAGE`만 허용
- 응답: 검증된 PNG/JPEG 바이트
- `Content-Disposition: inline`
- `Content-Type`: 서버가 검증해 저장한 값만 사용
- `X-Content-Type-Options: nosniff`
- URL의 attachment ID가 불변이므로 `Cache-Control: public, max-age=31536000, immutable`
- 일반 `DOWNLOAD` 첨부 요청 또는 글-첨부 불일치는 404
- 기존 download endpoint와 `Content-Disposition: attachment` 계약은 변경하지 않는다.

### 3.7 검색·복사·레거시 호환

- 목록 검색은 계속 `posts.body` 평문을 사용한다.
- 본문 복사는 rich 문서 JSON이 아니라 상세 응답의 추출 평문 `body`를 사용한다.
- plain 글 조회는 현재 `PostBodyReader` 동작을 유지한다.
- rich 글 조회만 문서 reader를 사용한다.
- 새 backend와 기존 front 조합에서는 plain `body`와 일반 첨부 목록으로 기능이 저하되지만 게시글을 읽을 수 있어야 한다.
- 새 front 배포는 backend 배포와 Flyway 적용 이후에 수행한다.

## 4. 기능 한도와 보안 경계

### 4.1 초기 릴리스 한도

| 항목 | 초기 한도 | 비고 |
| --- | --- | --- |
| 일반 첨부 + inline 이미지 합계 | 게시글당 5개 | 현재 `APP_ATTACHMENTS_MAX_COUNT` 계약 유지 |
| 일반 첨부 파일 크기 | 기존 기본 100MB | 변경 없음 |
| inline 이미지 파일 크기 | 파일당 10MB | 별도 backend 설정 추가 |
| 본문에서 활성화된 inline 이미지 | 최대 5개 | 일반 첨부와 합산한 총 5개 한도가 우선 |
| 브라우저 pending registry | 최대 20개·총 100MB | 삭제 후 undo용 임시 파일 포함, 세션 종료 시 전부 해제 |
| inline 이미지 형식 | PNG, JPEG | SVG/GIF/WebP/외부 URL 제외 |
| 이미지 최대 너비·높이 | 각 8192px | header metadata 검사 후 거부 |
| 이미지 최대 총 픽셀 | 25,000,000px | 압축 폭탄·메모리 사용 완화 |
| 추출 평문 길이 | 1,000,000자 | 기존 `BoardContentCodec` 계약 유지 |
| decoded 문서 JSON | 5MiB | Base64 decode 후 바이트 기준 |
| 문서 노드 수 | 20,000개 | 비정상 구조 방지 |
| 문서 중첩 깊이 | 20 | 재귀·스택 남용 방지 |

inline 이미지 설정 이름:

- `APP_ATTACHMENTS_INLINE_IMAGES_MAX_FILE_SIZE` 기본 `10MB`
- 너비·높이·총 픽셀 기본값은 코드 상수로 고정하고 후속 운영 요구가 있을 때만 설정화한다.

현재 총 첨부 5개 제한을 이번 작업에서 늘리지 않는다. 한도 확대는 저장량·UI·요청 크기·운영 백업 영향을 별도 검토한 뒤 수행한다.

### 4.2 파일 검증

- 브라우저가 보낸 MIME과 확장자를 신뢰하지 않는다.
- 서버가 `ImageIO` reader로 실제 PNG/JPEG 형식과 dimensions를 확인한다.
- 검증된 형식으로 `content_type`을 덮어쓴다.
- reader가 없거나 metadata를 읽을 수 없거나 dimensions·픽셀·크기 한도를 넘으면 저장 전에 거부한다.
- SVG, HTML, data URL, 외부 HTTP 이미지 URL은 canonical 문서에서 거부한다.
- 파일명은 표시용으로만 사용하며 저장명은 기존 UUID 방식을 유지한다.
- inline 이미지 전체 요청도 기존 multipart request limit 안에 있어야 한다.

### 4.3 문서 검증

서버는 클라이언트 JSON을 그대로 저장하지 않는다.

- 허용 node·mark·attribute whitelist
- 알 수 없는 node·mark·attribute 거부
- `imageKey` UUID 형식·중복·참조 집합 검증
- link를 지원하게 되면 `http`/`https`만 허용하며 이번 초기 범위에서는 link toolbar를 제공하지 않는다.
- `src`, `style`, event handler, raw HTML node 거부
- JSON 정규화 후 저장
- 평문은 서버가 정규화된 문서에서 추출

## 5. 작업 세션 사용량 한도 운영 규칙

이 절의 “한도”는 이미지 기능 한도가 아니라 구현 세션의 사용량·컨텍스트·도구 실행 한도를 뜻한다.

1. 각 작업 세션 시작 시 사용 가능한 UI/CLI 사용량 표시가 있으면 확인하고 재개 기록에 `충분 / 낮음 / 알 수 없음`으로 적는다. 확인 수단이 없으면 수치를 추정하거나 충분하다고 가장하지 않고 `알 수 없음`으로 기록한다.
2. 표시된 사용량이 `낮음`이면 새 Phase를 시작하지 않는다. 현재 변경과 검증 결과를 정리하고 재개 기록만 갱신한다.
3. 사용량이 `충분`이어도 하나의 Phase를 코드 변경, 필수 좁은 테스트, diff 검토, 재개 기록 갱신까지 마칠 여유가 없으면 시작하지 않는다.
4. 사용량이 `알 수 없음`이면 Phase 전체를 한 번에 시작하지 않는다. `선행 테스트 → 최소 backend 또는 frontend 구현 → 좁은 검증 → 기록` 체크포인트로 나누며, 각 체크포인트가 독립적으로 빌드·테스트 가능한 상태에서만 다음으로 이동한다. 체크포인트 도중에는 다른 Phase 작업을 섞지 않는다.
5. 사용량 경고가 나타나거나 남은 한도가 낮아지면 새 dependency 설치, migration, 광범위한 refactor, 전체 테스트를 시작하지 않는다. 현재 변경을 안전한 상태로 정리하고 실제 통과·실패 결과를 기록한다.
6. Phase 도중 한도가 낮아지면 기능을 억지로 완료 처리하지 않는다. 테스트 실패나 미완료 코드를 그대로 기록하고 해당 Phase 상태를 `중단`으로 유지한다.
7. 각 Phase는 독립적인 안전 중단점이 있어야 한다. 다음 Phase의 코드를 미리 섞지 않는다.
8. 좁은 테스트를 먼저 실행하고, 전체 `clean test`·front build·Docker 통합은 지정 Phase에서만 실행한다. 이미 통과한 전체 게이트를 매 Phase마다 중복 실행하지 않는다.
9. 실행 중인 Gradle·npm·Docker 프로세스가 있으면 재개 시 먼저 상태를 확인하고 같은 작업을 중복 시작하지 않는다.
10. Phase의 체크포인트 또는 종료 직후 이 문서의 상태표와 재개 기록을 갱신한다. 문서 갱신을 다음 세션으로 미루지 않는다.
11. 커밋은 사용자가 요청한 경우에만 Phase 경계에서 수행한다. 커밋 메시지는 한글이며, 사용자 기존 변경을 섞지 않는다.
12. secret, JWT, 비밀번호, `.env` 값, 전체 운영 로그를 한도·재개 기록에 넣지 않는다.

## 6. Phase 현황

| Phase | 목표 | 상태 | 안전 중단점 |
| --- | --- | --- | --- |
| 0 | 기준선·계약·한도 확정 | 완료 | 기준선·의존성·API/DB/한도 계약과 환경 제약 기록 완료 |
| 1 | Flyway·엔티티·응답 metadata 확장 | 완료 | V19·JPA·응답 metadata와 PostgreSQL 제약 검증 완료 |
| 2 | rich 문서 codec·검증·평문 추출 | 미착수 | 이미지 없이 rich 문서 생성·수정 API 통과 |
| 3 | inline 이미지 저장·조회 backend | 미착수 | MockMvc로 생성·content 조회·롤백 검증 통과 |
| 4 | Tiptap editor·reader 기반 도입 | 미착수 | 이미지 없이 plain/rich 작성·수정·조회 가능 |
| 5 | 이미지 붙여넣기 임시 보관·글 생성 | 미착수 | 새 글에서 붙여넣기→저장→조회 통과 |
| 6 | 수정·삭제·실패 재시도 완성 | 미착수 | 기존/신규 이미지 혼합 수정과 정리 통과 |
| 7 | 한도·보안·접근성 hardening | 미착수 | 경계값·악성 입력·키보드/모바일 점검 통과 |
| 8 | 전체 회귀·PostgreSQL·통합 smoke·문서화 | 미착수 | 최종 게이트와 배포 순서 기록 완료 |

상태 값은 `미착수 / 진행 중 / 중단 / 차단 / 완료`만 사용한다.

## 7. Phase별 실행 계획

### Phase 0 — 기준선·계약·한도 확정

**상태: 완료 — 2026-09-20**

#### 진입 전 한도 게이트

- 사용량 상태를 재개 기록에 남긴다.
- 전체 baseline 명령을 실행하고 결과를 기록할 여유가 없으면 시작하지 않는다.

#### 작업

- `git status --short`로 기존 작업 범위를 확인한다.
- 현재 backend·front 테스트 기준선을 기록한다.
- Tiptap 공식 Vue 3/Nuxt 지원, MIT 라이선스, 설치 후보 버전의 게시일을 확인한다.
- 이 문서의 본문 format, manifest, attachment kind, 총 5개 한도, PNG/JPEG 10MB 한도를 구현 계약으로 확정한다.
- 현재 API 테스트에서 추가해야 할 회귀 항목 목록을 확정한다.
- 운영 DB migration history를 변경하지 않고 로컬 파일 기준 최신 migration이 V18임을 확인한다.

#### 검증

```powershell
# 저장소 루트
git status --short

cd back
.\gradlew.bat clean test

cd ..\front
npm test
npm run typecheck
npm run build
```

PostgreSQL 조건부 테스트가 환경 미제공으로 skipped되면 성공으로 숨기지 않고 건수와 이유를 기록한다.

#### 완료 조건

- baseline 성공·실패·skip 건수가 기록되어 있다.
- package·API·DB 결정이 미확정 상태로 남아 있지 않다.
- 코드·lockfile·migration 변경이 없다.

#### 안전 중단점

코드 변경 없이 계획서의 Phase 0 결과와 재개 기록만 갱신한다.

#### Phase 0 완료 기록 — 2026-09-20

- 사용량 상태는 확인 수단이 없어 `알 수 없음`으로 기록했다. Phase 0은 코드 변경 없는 기준선 점검이므로 저장소·의존성·테스트 확인 체크포인트로 나눠 완료했다.
- 기준 브랜치·커밋은 `main` / `5a151823e6846074e2f29bf4eb0b0fb100e7b01d`다.
- 작업 전후 `git status --short`는 모두 `?? plan/post-inline-image-editor-plan.md`만 표시했다. 구현 소스, `package.json`, lockfile, migration은 변경되지 않았다.
- 로컬 migration 최신 버전은 `V18__create_attachment_file_deletions.sql`로 확인했다. 운영 DB나 Flyway history는 조회·변경하지 않았다.
- Tiptap 오픈소스 코어는 공식 자료에서 MIT, Vue 지원, self-host 가능으로 확인했다. 공식 Nuxt 설치 가이드는 현재 Nuxt 4 예시지만 `@tiptap/vue-3`의 peer 범위는 Vue `^3.0.0`이며 이 저장소는 Vue 3.5.13/Nuxt 3이다. 실제 Nuxt 3 hydration 호환성은 Phase 4의 typecheck·generate·browser smoke에서 최종 검증한다.
- 설치 후보는 `@tiptap/core`, `@tiptap/vue-3`, `@tiptap/pm`, `@tiptap/starter-kit` 모두 exact `3.31.3`으로 확정했다. 네 패키지는 MIT이고 2026-09-04 게시되어 7일 유예 조건을 충족한다.
- 본문 format, manifest, `DOWNLOAD`/`INLINE_IMAGE`, 기존 volume 재사용, 총 첨부 5개, inline 이미지 PNG/JPEG·10MB, pending registry 20개·100MB 계약을 확정했다.

Backend 기준선:

- bare `./gradlew.bat clean test`는 PATH Java 21.0.8, `JAVA_HOME` 미설정 상태에서 Java 25 toolchain을 찾지 못해 실패했다.
- 기존 JDK 25 위치를 명령 범위에서만 지정한 `./gradlew.bat -Porg.gradle.java.installations.paths="C:\jdk\jdk-25.0.4.1+1" clean test`는 성공했다. 저장소·전역 설정은 변경하지 않았다.
- 147건 발견, 143건 통과, 4건 skip, 실패 0, 오류 0이다.
- skip 4건은 `LLM_TEST_POSTGRES_URL` 미설정에 따른 조건부 `PostgresMigrationTest` 1건과 `PostgresUploadFinalizeTest` 3건이다. PostgreSQL 검증 성공으로 간주하지 않는다.
- 비차단 warning은 레거시 AI 필드 deprecation annotation 3건, 제거 예정 `@MockBean` 2건, `JwtProviderTest` unchecked note와 JVM CDS warning이다.

Frontend 기준선:

- `npm test`: 7건 통과, 실패·skip 0.
- `npm run typecheck`: 성공.
- `npm run build`: 성공, client module 225개와 route 5개 prerender.
- 비차단 warning은 Nitro의 `cache-driver.js` external 처리와 npm 새 버전 안내다.

확정한 신규 회귀 테스트 범위:

- plain API additive 호환, `bodyFormat`/`bodyDocument`, attachment kind/key/content URL
- canonical rich JSON 검증·평문 추출·크기/node/depth 경계
- inline manifest와 file/document key 집합 검증
- PNG/JPEG 실제 형식·크기·dimensions·pixel 한도
- inline content endpoint headers·bytes·404 경계
- rich 글에 대한 구형 plain 수정 409 무변경
- 신규 파일 rollback, inline 삭제 commit 후 정리·재시도
- frontend canonical serializer, key resolver, paste registry, URL revoke, pending 20개·100MB와 총 첨부 5개 경계

Phase 0 완료 조건을 모두 충족했다. 다음 작업은 Phase 1의 선행 회귀 테스트 작성과 additive Flyway/JPA/DTO 확장이다.

### Phase 1 — Flyway·엔티티·응답 metadata 확장

**상태: 완료 — 2026-09-20**

#### 진입 전 한도 게이트

- migration, JPA model, mapper, controller 회귀 테스트를 한 번에 완료할 수 있을 때만 시작한다.
- PostgreSQL focused migration 검증 환경을 준비할 수 없으면 구현은 가능하지만 Phase 완료는 `차단`으로 남긴다.

#### 선행 테스트

- 기존 plain 게시글 생성·수정·조회 응답이 additive 필드 이후에도 유지되는 테스트
- 기존 첨부가 `DOWNLOAD`, `inlineKey=null`, `contentUrl=null`로 매핑되는 테스트
- 기존 `FILE_CONVERSION_REQUEST` ZIP이 `DOWNLOAD`로 유지되는 테스트
- migration V1~신규 버전과 Hibernate validate 테스트

#### 구현

- 신규 Flyway migration만 추가한다. V1~V18은 수정하지 않는다.
- `posts.body_format`, `posts.body_document`를 추가한다.
- `post_attachments.attachment_kind`, `post_attachments.inline_key`와 check/partial unique index를 추가한다.
- `PostBodyFormat`, `BoardAttachmentKind` enum과 JPA mapping을 추가한다.
- 상세 DTO·프론트 API 타입에 `bodyFormat`, `bodyDocument`, `attachmentKind`, `inlineKey`, `contentUrl`을 additive로 추가한다.
- 이 Phase에서는 inline file 업로드와 rich document 입력을 아직 활성화하지 않는다.

#### 좁은 검증

```powershell
cd back
.\gradlew.bat test --tests "com.llm.app.board.controller.BoardPostControllerTest"
.\gradlew.bat test --tests "com.llm.app.review.PostgresMigrationTest"
```

PostgreSQL 테스트는 전용 disposable DB와 schema에서만 실행한다.

#### 완료 조건

- 기존 plain 글·첨부 API 회귀가 통과한다.
- 신규 migration과 Hibernate validate가 PostgreSQL에서 통과한다.
- 기존 행의 default/backfill 결과가 명확하다.
- 새 필드는 응답에 존재하지만 기존 UI 동작은 변하지 않는다.

#### 안전 중단점

additive DB/API 변경과 회귀 테스트만 존재하고 rich 요청은 아직 받지 않는다.

#### Phase 1 완료 기록 — 2026-09-20

- 사용량 상태는 확인 수단이 없어 `알 수 없음`으로 유지하고, 선행 테스트 실패 확인 → additive 구현 → focused 검증 → disposable PostgreSQL → 전체 게이트 순서로 체크포인트를 나눴다.
- 선행 controller 테스트는 구현 전 `BoardAttachmentKind`와 신규 getter를 찾을 수 없는 `compileTestJava` 4건으로 실패해 의도한 red 상태를 확인했다.
- 신규 `V19__add_rich_post_and_inline_attachment_metadata.sql`만 추가했고 V1~V18은 수정하지 않았다.
- `posts`에 `body_format`/`body_document`, `post_attachments`에 `attachment_kind`/`inline_key`, enum check, format-document/kind-key check, `(post_id, inline_key)` partial unique index를 추가했다.
- `BoardPost`·`BoardAttachment`는 기존 생성자 signature를 유지하고 각각 `PLAIN_TEXT`/`DOWNLOAD` 기본값을 사용한다. 기존 plain update는 document를 null로 유지한다.
- 게시글 상세·일반 첨부·업로드 finalize 응답에 `bodyFormat`, `bodyDocument`, `attachmentKind`, `inlineKey`, `contentUrl`을 additive로 추가했다.
- Phase 1에는 rich 입력과 inline content endpoint를 활성화하지 않았다. 따라서 기존 응답은 `PLAIN_TEXT`/null 및 `DOWNLOAD`/null/null이다.
- frontend는 API type만 확장했고 runtime/UI 동작은 변경하지 않았다.

검증 결과:

- focused `BoardPostControllerTest` + `UploadSessionControllerTest`: 통과.
- disposable `postgres:18`의 `127.0.0.1:55439`에서 `PostgresMigrationTest` 1건 통과, skip 0.
- V16 fixture 이후 V17·V18·V19 3개 migration 적용, 기존 행 default, 세 DB check/unique 위반의 `SQLException`, Hibernate `ddl-auto=validate`를 확인했다.
- 같은 disposable PostgreSQL URL로 backend 전체 `clean test`: 147건 전부 통과, skip·실패·오류 0. PostgreSQL 조건부 4건도 모두 실행됐다.
- frontend `npm test`: 7/7 통과.
- frontend typecheck와 generate build: 성공, route 5개 prerender.
- 비차단 warning은 Phase 0과 동일한 AI field dep-ann 3건, `@MockBean` removal 2건, unchecked/CDS, Nitro `cache-driver.js` external 처리다.
- disposable `llm-phase1-postgres` 컨테이너와 Gradle daemon을 정리했고 기존 공유 `postgres` 컨테이너는 건드리지 않았다.
- 최종 diff 검토에서 계획 계약과 다른 변경이나 잔여 결함은 발견하지 못했다.

Phase 1 완료 조건을 모두 충족했다. 다음 작업은 Phase 2의 rich 문서 codec 선행 테스트와 입력 계약 구현이다.

### Phase 2 — rich 문서 codec·검증·평문 추출

**상태: 미착수**

#### 진입 전 한도 게이트

- codec 구현과 악성/경계 입력 테스트를 같은 Phase에서 완료할 수 있어야 한다.
- frontend dependency 설치는 시작하지 않는다.

#### 선행 테스트

- 허용된 Tiptap JSON을 정규화하고 평문을 추출한다.
- 빈 rich 문서를 빈 `body`로 저장한다.
- 문단·줄바꿈·목록·이미지 alt의 평문 변환 규칙을 검증한다.
- unknown node/mark/attribute, raw HTML, `src`, 잘못된 UUID를 거부한다.
- decoded JSON 5MiB, 평문 1,000,000자, 노드 20,000개, 깊이 20의 바로 아래·동일·초과 경계를 검증한다.
- plain 요청과 rich 요청 필드 충돌을 거부한다.
- 기존 rich 글을 `bodyFormat` 누락 또는 `PLAIN_TEXT` 요청으로 수정하면 409 `RICH_TEXT_CLIENT_REQUIRED`이고 DB·파일이 변하지 않는다.
- 레거시 plain 글 수정이 기존 계약으로 계속 동작한다.

#### 구현

- `BoardRichDocumentCodec`을 추가해 Base64 decode, JSON parse, schema validate, canonical serialize, plain text extract를 담당하게 한다.
- `CreateBoardPostRequest`·`UpdateBoardPostRequest`에 rich 문서 입력 필드를 추가한다.
- `BoardService`가 `bodyFormat`에 따라 plain codec 또는 rich codec을 사용하게 한다.
- rich 상세 응답의 `bodyDocument`는 검증된 JSON object로 반환한다.
- 이미지 참조 집합 추출 API를 codec에 두되, 파일 저장은 Phase 3에서 연결한다.

#### 좁은 검증

```powershell
cd back
.\gradlew.bat test --tests "*BoardRichDocumentCodecTest"
.\gradlew.bat test --tests "com.llm.app.board.controller.BoardPostControllerTest"
```

#### 완료 조건

- 이미지 파일 없이 rich 문서를 생성·수정·조회할 수 있다.
- `posts.body`는 서버 추출 평문, `body_document`는 canonical JSON이다.
- 검색과 본문 복사가 내부 UUID나 JSON 문법을 노출하지 않는다.
- plain API 회귀가 유지된다.

#### 안전 중단점

backend가 plain/rich 문서를 모두 처리하지만 inline 이미지 파일은 아직 거부한다.

### Phase 3 — inline 이미지 저장·조회 backend

**상태: 미착수**

#### 진입 전 한도 게이트

- 파일 검증, transactional sync, content endpoint, 롤백 테스트까지 완료할 수 있을 때만 시작한다.
- 전체 backend suite는 Phase 끝의 좁은 테스트가 통과한 뒤 한 번만 실행한다.

#### 선행 테스트

- rich 문서 + PNG 1개 생성 성공과 key-metadata 연결
- rich 문서 + JPEG 여러 개의 manifest index 연결
- manifest/file/document 집합 불일치, 중복 key/index, 누락 파일 거부
- MIME 위장, 지원하지 않는 형식, 10MB 초과, dimensions·픽셀 초과 거부
- 일반 첨부와 inline 이미지 합계 5개 경계
- content endpoint의 bytes, content type, inline disposition, nosniff, immutable cache
- 일반 첨부 또는 다른 글 attachment의 content endpoint 404
- 저장 후 DB 실패 시 신규 파일과 metadata 롤백
- 생성 실패 시 게시글·attachment partial commit 없음

#### 구현

- `InlineImageValidator`를 추가한다.
- `inlineImageManifestBase64`와 `inlineImages`를 DTO에 추가한다.
- attachment sync를 일반 첨부와 inline 이미지 집합을 함께 검증한 뒤 변경하도록 확장한다.
- 신규 inline attachment에 `INLINE_IMAGE`와 `inline_key`를 저장한다.
- content endpoint를 추가한다.
- 기존 download endpoint는 변경하지 않는다.
- 오류 코드는 기존 `INVALID_ATTACHMENT_REQUEST`, `ATTACHMENT_TOO_LARGE`, `ATTACHMENT_STORAGE_ERROR`를 우선 재사용하고, 형식 오류를 구분할 필요가 확인될 때만 `INVALID_INLINE_IMAGE`를 추가한다.

#### 좁은 검증

```powershell
cd back
.\gradlew.bat test --tests "com.llm.app.board.controller.BoardPostControllerTest"
.\gradlew.bat test --tests "com.llm.app.review.SecurityAndStorageRegressionTest"
```

#### Phase 게이트

```powershell
cd back
.\gradlew.bat clean test
```

#### 완료 조건

- MockMvc만으로 rich 글 + inline 이미지 생성·조회가 검증된다.
- DB·파일 롤백 및 기존 삭제 대기열 동작이 유지된다.
- 기존 ZIP 업로드와 일반 첨부 회귀가 통과한다.
- backend만 배포해도 기존 front가 plain body를 계속 읽을 수 있다.

#### 안전 중단점

backend 계약과 테스트가 완결되고 frontend는 아직 기존 textarea를 사용한다.

### Phase 4 — Tiptap editor·reader 기반 도입

**상태: 미착수**

#### 진입 전 한도 게이트

- dependency 설치, lockfile 검토, editor·reader·plain fallback, typecheck/build까지 완료할 수 있어야 한다.
- 사용량이 낮으면 package 설치만 하고 중단하지 않는다. 설치와 최소 editor integration을 같은 Phase에서 완료한다.

#### 선행 테스트

기존 Node test 방식으로 DOM 비의존 utility를 먼저 작성한다.

- API `bodyDocument` type guard와 plain fallback
- canonical 문서 serializer가 runtime 속성·`src`를 제거
- attachment `inlineKey -> contentUrl` resolver
- plain 글을 Tiptap 문서로 변환할 때 줄바꿈 보존
- rich 문서에서 API payload 생성

#### 구현

- Tiptap package를 package manager로 설치한다.
- `PostDocumentEditor.client.vue`를 추가하고 `PostForm.vue`·`PostEditPanel.vue`의 textarea를 교체한다.
- `PostDocumentReader.client.vue`를 추가하고 rich 글에서만 사용한다.
- `inlineAttachmentImage` custom node와 Vue NodeView를 추가하되 이 Phase에서는 영구 image resolver placeholder만 연결한다.
- plain 글 수정 시 에디터 초기 문서로 변환하고 저장 시 rich format으로 전환한다.
- `PostBodyReader`의 plain 동작과 파일 변환 글 분기는 유지한다.
- 상세 `AttachmentPanel`과 수정 화면의 기존 첨부 목록은 `DOWNLOAD`만 표시하여 inline 이미지 중복 노출과 잘못된 `removeAttachmentIds` 전송을 막는다. 슬롯 계산은 숨겨진 inline 이미지까지 포함한 전체 attachment 수를 사용한다.
- 작성·수정 submit은 rich 문서 전송까지 연결하되 이미지 paste는 Phase 5에서 활성화한다.

#### 검증

```powershell
cd front
npm test
npm run typecheck
npm run build
```

브라우저 smoke:

- 새 plain text 작성·저장·조회
- 기존 plain 글 조회
- 기존 plain 글 수정 후 rich format 전환
- 공개 상세와 로그인 상세의 동일 렌더
- 한글 IME 입력, 줄바꿈, selection, undo/redo

#### 완료 조건

- 이미지 없이 기존 작성·수정·조회 기능이 Tiptap 기반으로 동작한다.
- legacy plain 글은 그대로 읽히고 수정 저장 시 내용이 유실되지 않는다.
- Nuxt generate와 hydration 오류가 없다.
- 유료 서비스·외부 CDN 의존이 없다.

#### 안전 중단점

editor/reader와 plain/rich 호환은 완성됐지만 붙여넣은 이미지는 아직 받지 않는다.

### Phase 5 — 이미지 붙여넣기 임시 보관·글 생성

**상태: 미착수**

#### 진입 전 한도 게이트

- paste handler, object URL 정리, manifest 생성, create 요청, browser smoke를 함께 완료할 수 있어야 한다.
- backend Phase 3과 frontend Phase 4가 완료되지 않았으면 시작하지 않는다.

#### 선행 테스트

- clipboard item에서 PNG/JPEG `File`만 추출한다.
- 텍스트 paste는 기존 동작을 유지한다.
- 지원하지 않는 이미지 형식은 삽입하지 않고 사용자 오류를 표시한다.
- pending image registry의 key 중복 방지
- `crypto.randomUUID()` 미지원 환경에서 `crypto.getRandomValues()` 기반 UUID v4 fallback이 동작하고 `Math.random()`을 사용하지 않는다.
- document image key 순서와 무관하게 manifest fileIndex가 정확하다.
- pending node 삭제 직후에는 object URL을 유지해 undo 복원이 가능하고, 저장 성공·폼 reset·컴포넌트 unmount에서는 registry URL을 정확히 한 번 해제한다.
- pending registry 20개·100MB 경계와 사용자 오류를 검증한다.
- 일반 첨부와 활성 inline 이미지 합계 5개 초과를 submit 전에 차단한다.

#### 구현

- `useInlineImageDraft` 또는 동등한 composable로 `{ imageKey, file, objectUrl }` registry를 관리한다.
- Tiptap `handlePaste`에서 실제 image file이 있을 때만 이미지 paste를 가로챈다.
- 첫 inline 이미지 선택 시 기존 업로드 환경 확인을 한 번 표시하고, 거부하면 파일과 node를 만들지 않는다.
- clipboard 파일명은 `pasted-image-<timestamp>-<short-id>.png|jpg` 형식으로 생성한다.
- editor node는 `imageKey`만 canonical 상태로 보존하고 NodeView가 pending object URL을 해석한다.
- node에서 제거된 pending entry도 undo를 위해 편집 세션 registry에 유지하되, 현재 canonical 문서가 참조하지 않으면 manifest와 업로드 파일에서 제외한다.
- registry 20개·100MB를 넘는 paste는 파일·node를 만들기 전에 거부한다.
- create 요청에 canonical document, manifest, `inlineImages`, 일반 첨부를 함께 전송한다.
- 성공 응답 후 pending object URL을 해제하고 상세 응답의 영구 content URL로 전환한다.
- 요청 실패 시 editor document와 pending registry를 유지한다.

#### 검증

```powershell
cd front
npm test
npm run typecheck
npm run build
```

브라우저 create smoke:

1. 텍스트 작성
2. PNG 붙여넣기
3. 이미지 아래 텍스트 작성
4. 등록
5. 로그인 상세에서 순서 확인
6. 공개 상세에서 순서 확인
7. 새로고침 후 이미지 유지 확인
8. 다운로드 panel에 inline 이미지가 중복 표시되지 않는지 확인

#### 완료 조건

- 붙여넣기 직후 서버 요청 없이 이미지가 보인다.
- 저장 후 동일 위치에 영구 이미지가 보인다.
- 실패 후 재시도와 취소 시 메모리·object URL 누수가 없다.
- 총 첨부 개수와 파일 크기 오류가 한국어로 표시된다.

#### 안전 중단점

새 글 생성에서 paste→임시 표시→영구 저장이 완결되고 수정 경로는 아직 기존 이미지를 보존만 한다.

### Phase 6 — 수정·삭제·실패 재시도 완성

**상태: 미착수**

#### 진입 전 한도 게이트

- update 집합 계산, 삭제 일관성, 파일 lifecycle 테스트와 front edit smoke를 한 Phase에서 완료할 수 있어야 한다.

#### 선행 테스트

- 기존 inline 이미지만 유지하는 수정
- 기존 이미지 유지 + 신규 이미지 추가
- 기존 이미지 삭제 + 신규 이미지 추가
- 문서에서 제거된 기존 inline attachment의 metadata 삭제와 커밋 후 파일 삭제
- 삭제 transaction rollback 시 기존 metadata·파일 유지
- 신규 파일 저장 후 update 실패 시 신규 파일 정리, 기존 파일 유지
- 다른 글 imageKey 참조 거부
- `removeAttachmentIds`에 inline image ID 전달 시 거부
- 수정 권한 없는 USER의 inline 변경 403 및 무변경
- FILE_CONVERSION_REQUEST 수정 잠금 유지

#### 구현

- update service가 rich 문서 key 집합을 source of truth로 사용해 기존 inline image 유지·삭제를 계산한다.
- 신규 manifest와 existing key 집합을 합친 뒤 문서 참조를 검증한다.
- 일반 첨부 삭제와 inline 이미지 자동 삭제를 하나의 최종 개수·권한·참조 검증 뒤 실행한다.
- frontend edit 초기화 시 기존 `inlineKey -> contentUrl` map을 구성한다.
- 신규 이미지와 기존 이미지를 같은 NodeView에서 해석한다.
- 저장 성공 후 응답으로 editor state와 attachment map을 교체하고 pending URL을 해제한다.
- 저장 실패 시 기존/신규 editor 상태를 유지한다.

#### 검증

```powershell
cd back
.\gradlew.bat test --tests "com.llm.app.board.controller.BoardPostControllerTest"
.\gradlew.bat test --tests "com.llm.app.review.SecurityAndStorageRegressionTest"

cd ..\front
npm test
npm run typecheck
npm run build
```

#### 완료 조건

- 생성·수정·삭제 모든 경로에서 문서 참조와 실제 attachment 집합이 일치한다.
- 권한 오류·DB 실패·파일 삭제 실패에서 부분 반영이 없다.
- 삭제 실패는 기존 영속 대기열에 남아 재시도된다.
- 수정 화면을 반복 진입·취소해도 object URL이나 이전 글 상태가 남지 않는다.

#### 안전 중단점

기능 구현은 완성됐고 다음 Phase는 한도·보안·접근성 경계 강화만 수행한다.

### Phase 7 — 한도·보안·접근성 hardening

**상태: 미착수**

#### 진입 전 한도 게이트

- 새 기능 추가가 아니라 확인된 경계 결함만 수정한다.
- 전체 게이트 여유가 없으면 경계 테스트 결과만 기록하고 Phase를 완료하지 않는다.

#### 작업·검증

- 0/1/5/6개 총 첨부 경계
- inline 이미지 10MB 바로 아래·동일·초과
- 8192px와 25MP 바로 아래·동일·초과
- 가짜 PNG/JPEG, 손상 파일, SVG, HTML, 빈 파일
- 5MiB 문서·1,000,000자·20,000 node·depth 20 경계
- duplicate key, missing key, stale key, 다른 글 key
- 악성 alt, raw HTML, `src`, `javascript:` 속성 거부
- content endpoint의 nosniff·inline·cache headers
- keyboard paste, undo/redo, 이미지 선택·삭제, focus 순서
- 화면 reader의 alt와 이미지 로딩 실패 대체 UI
- 모바일에서는 붙여넣기 지원 여부와 무관하게 기존 파일 선택·일반 글 작성이 깨지지 않는지 확인
- 공개 API이므로 붙여넣기 확인 문구에 이미지가 게시글과 함께 공개됨을 명시

#### 검증

```powershell
cd back
.\gradlew.bat clean test

cd ..\front
npm test
npm run typecheck
npm run build
```

#### 완료 조건

- 기능 한도 표의 모든 경계값 결과가 테스트 또는 smoke 기록에 있다.
- `v-html`, data URL 영구 저장, 외부 image hotlink가 없다.
- server-verified content type만 inline 응답에 사용한다.
- 키보드·한글 IME·기본 모바일 작성 경로에 치명적 회귀가 없다.

#### 안전 중단점

코드·테스트가 최종 통합 전 release candidate 상태다.

### Phase 8 — 전체 회귀·PostgreSQL·통합 smoke·문서화

**상태: 미착수**

#### 진입 전 한도 게이트

- backend 전체 테스트, PostgreSQL focused 테스트, front test/typecheck/build, Docker 통합을 순서대로 실행하고 결과를 기록할 여유가 있을 때만 시작한다.
- Docker/외부 PostgreSQL 환경이 준비되지 않으면 통합 항목을 `차단`으로 남기고 성공으로 표현하지 않는다.

#### 문서 갱신

- `AGENTS.md`: rich body·inline image 핵심 계약과 검증 명령
- `docs/04-architecture.md`: 임시 blob→multipart→volume→content URL 흐름
- `docs/05-configuration.md`와 `.env.example`: inline 이미지 10MB 설정
- `docs/06-database.md`: V19 이후 컬럼·제약·백업 영향
- `docs/07-api-reference.md`: rich multipart, response, content endpoint, 오류
- `docs/10-testing-quality.md`: paste/create/edit/delete smoke
- `docs/14-security.md`: MIME·dimension·public image·nosniff
- `docs/18-integrity-hardening.md`: inline 이미지 rollback·커밋 후 삭제

#### 최종 검증

```powershell
# 작업 범위
git status --short
git diff --check

# Backend
cd back
.\gradlew.bat clean test

# Frontend
cd ..\front
npm test
npm run typecheck
npm run build

# 저장소 루트 통합
cd ..
docker compose up -d --wait
curl.exe -fsS http://127.0.0.1:8083/api/v1/health
```

필수 HTTP smoke:

1. 로그인
2. 텍스트-이미지-텍스트 글 생성
3. 로그인 상세·공개 상세 조회
4. content endpoint headers·bytes 확인
5. 기존 이미지 1개 삭제, 신규 이미지 1개 추가 수정
6. 새로고침 후 순서·이미지 유지 확인
7. 일반 첨부 다운로드 유지 확인
8. FILE_CONVERSION_REQUEST ZIP 조회·다운로드 회귀 확인
9. 삭제 후 metadata와 삭제 대기열·실파일 상태 확인

PostgreSQL 검증:

- 신규 migration 적용
- Hibernate validate
- plain 기존 fixture 조회
- rich 글 생성·수정·삭제
- transaction rollback 시 신규 이미지 파일 정리
- 커밋 후 파일 삭제 실패·재시도

#### 배포 순서

1. 운영 Flyway history와 로컬 V1~신규 migration 파일 대조
2. backend 이미지 배포·migration 적용·health 확인
3. 기존 front로 plain body fallback과 기존 API 확인
4. front 이미지 배포
5. 실제 paste/create/read/edit/delete smoke

#### 롤백 원칙

- migration은 additive이므로 운영에서 컬럼을 즉시 제거하지 않는다.
- 이전 backend/front 이미지를 복구할 수 있게 이미지 식별자를 기록한다.
- 이전 front는 rich 글의 추출 평문과 attachment metadata를 읽을 수 있지만 inline 이미지를 일반 다운로드 카드로 표시할 수 있다.
- 새 backend는 구형 front의 rich 글 평문 수정 요청을 409 `RICH_TEXT_CLIENT_REQUIRED`로 막는다.
- rich 글을 만든 뒤 이전 backend로 롤백하면 구형 backend가 `body`만 수정하고 알지 못하는 `body_format`·`body_document`는 그대로 남겨, 새 backend 복구 후 편집 내용이 가려질 수 있다. 이전 backend 롤백 중에는 게시글 쓰기·수정을 일시 중지하고 새 backend를 우선 복구한다.
- 운영 volume 삭제, `docker compose down -v`, prune, Flyway history 임의 수정은 하지 않는다.

#### 완료 조건

- 전체 backend·front 게이트가 통과한다.
- PostgreSQL 신규 migration과 실제 transaction/file 경계가 검증된다.
- 8083 경유 health와 HTTP smoke가 통과한다.
- test 수·pass/fail/skip, PostgreSQL 버전, migration 범위, smoke 결과가 재개 기록에 남는다.
- secret·토큰·비밀번호·실제 이미지의 민감 내용은 기록하지 않는다.

## 8. 테스트 매트릭스

| 영역 | 필수 시나리오 |
| --- | --- |
| 레거시 본문 | plain 생성·수정·조회·검색·복사, 기존 글 수정 시 rich 전환 |
| rich codec | canonical JSON, 평문 추출, unknown node/attr, 크기·node·depth 경계 |
| 생성 | 이미지 0/1/여러 개, 일반 첨부 혼합, manifest 불일치, 총 5개 경계 |
| 수정 | 유지, 추가, 삭제, 추가+삭제, 다른 글 key, 권한 실패 |
| 파일 검증 | PNG/JPEG 정상, MIME 위장, 손상, SVG/HTML, 크기·dimension·pixel 초과 |
| 조회 | 로그인·공개 상세, inline content, 기존 download, alt, 로딩 실패 |
| 트랜잭션 | 신규 파일 후 실패, metadata 후 실패, 삭제 rollback, 커밋 후 삭제 재시도 |
| frontend 임시 상태 | paste, text paste, undo/redo, 실패 재시도, 취소/reset/unmount revoke |
| 호환성 | 기존 front+새 backend plain fallback, ZIP 업로드·다운로드, AI 종료 stub |
| 운영 | Flyway, Hibernate validate, volume mount, 8083 health, backup 영향 |

테스트는 파일 배치나 내부 메서드 호출 횟수보다 HTTP 응답, canonical 문서, DB metadata, 실제 파일 존재·삭제 상태를 검증한다.

## 9. 예상 변경 파일 영역

### Frontend

- `front/package.json`, `front/package-lock.json`
- `front/components/post/PostForm.vue`
- `front/components/post/PostEditPanel.vue`
- `front/components/post/PostBodyReader.vue`
- 신규 `PostDocumentEditor.client.vue`
- 신규 `PostDocumentReader.client.vue`
- 신규 inline image NodeView·composable·document utility
- `front/stores/postDetail.ts`
- `front/services/api.ts`
- `front/types/api.ts`
- `front/utils/post.ts`
- `front/tests/*.test.cjs`
- 관련 CSS

### Backend

- 신규 Flyway migration(V19 이상, 실제 구현 시 현재 최신 번호 재확인)
- `BoardPost`, `BoardAttachment`와 신규 enum
- create/update/detail/attachment DTO
- `BoardContentCodec` 또는 신규 `BoardRichDocumentCodec`
- `BoardService`, `BoardMapper`, `AttachmentStorageService`
- `BoardPostController`
- inline image validator·manifest DTO
- `BoardPostControllerTest`, codec test, storage/transaction 회귀 테스트
- PostgreSQL migration/focused 테스트
- `application.properties`, `.env.example`

### 문서

Phase 8의 문서 갱신 목록을 따른다. 구현과 무관한 문서는 수정하지 않는다.

## 10. 위험과 대응

| 위험 | 대응 |
| --- | --- |
| `blob:` URL을 영구 저장 | canonical serializer와 backend schema에서 `src` 금지 |
| 본문 JSON이 검색·복사에 노출 | `posts.body`에 서버 추출 평문 유지 |
| 이미지와 문서 참조 불일치 | manifest/existing key/document 집합을 저장 전 원자 검증 |
| inline 이미지 삭제 후 고아 파일 | 문서 key 집합을 source of truth로 사용하고 기존 deletion queue 재사용 |
| 신규 파일 저장 뒤 DB rollback | 기존 `AttachmentFileLifecycle.trackCreated` 재사용·회귀 테스트 |
| MIME 위장·SVG/XSS | PNG/JPEG 실제 형식 검증, raw HTML/src/style/event 금지, nosniff |
| 압축 폭탄·과도한 메모리 | 파일·dimension·pixel·문서/node/depth 한도 선검증 |
| Tiptap SSR/hydration 오류 | client component, `immediatelyRender:false`, generate·browser smoke |
| 한글 IME·selection 회귀 | Phase 4·7 브라우저 smoke |
| pending 이미지 undo 또는 메모리 누수 | 세션 registry 유지, 활성 key만 업로드, 20개·100MB cap, 성공/reset/unmount 일괄 revoke |
| 기존 plain 글 손실 | plain fallback, per-post one-way rich 전환, 회귀 fixture |
| 구형 client가 rich 글을 평문으로 덮음 | 새 backend에서 409 `RICH_TEXT_CLIENT_REQUIRED`; 구형 backend 롤백 중 쓰기 중지 |
| 첨부 5개 한도 UX 혼란 | 일반+inline 합계 표시, paste와 submit 모두 선검증 |
| backend/front 배포 순서 불일치 | additive response, plain body fallback, backend 먼저 배포 |
| DB migration 충돌 | 운영 Flyway history 사전 대조, 기존 V1~V18 수정 금지 |
| 세션 사용량 한도 도달 | Phase 경계, 좁은 테스트 우선, 즉시 재개 기록, 미완료 상태 정직하게 유지 |

## 11. 중단·재개 기록 템플릿

각 Phase 시작·종료·중단 시 아래 블록을 문서 하단에 최신 기록으로 갱신한다.

```text
기록 일시:
사용량 상태: 충분 / 낮음 / 알 수 없음
현재 Phase:
Phase 상태: 미착수 / 진행 중 / 중단 / 차단 / 완료
마지막 완료 Phase:
기준 브랜치/커밋:
작업 전 git status:
이번 변경 파일:
완료한 계약:
통과한 좁은 테스트:
최신 전체 backend 테스트:
최신 front test/typecheck/build:
PostgreSQL focused 결과:
Docker/health/smoke 결과:
실행 중인 프로세스:
알려진 실패·차단:
다음 정확한 작업:
주의할 사용자 기존 변경:
```

기록에는 secret, JWT, 비밀번호, `.env` 값, PEM 내용, 민감한 이미지 내용, 전체 환경변수 덤프를 넣지 않는다.

## 12. 최신 재개 기록 — Phase 1 완료

```text
기록 일시: 2026-09-20
사용량 상태: 알 수 없음
현재 Phase: 2
Phase 상태: 미착수
마지막 완료 Phase: 1
기준 브랜치/커밋: main / 5a151823e6846074e2f29bf4eb0b0fb100e7b01d
작업 전 git status: ?? plan/post-inline-image-editor-plan.md
이번 변경 파일: V19 migration, PostBodyFormat/BoardAttachmentKind, BoardPost/BoardAttachment, board/upload DTO·mapper, 3 backend test files, front/types/api.ts, 계획서
완료한 계약: 기존 row PLAIN_TEXT/DOWNLOAD default, body/attachment metadata additive 응답, format-document와 kind-key check, partial unique inline key; rich 입력·content endpoint는 미활성
통과한 좁은 테스트: BoardPostControllerTest + UploadSessionControllerTest; PostgresMigrationTest 1/1
최신 전체 backend 테스트: disposable PostgreSQL 18 URL을 지정한 clean test 147/147 passed, 0 skipped, 0 failures, 0 errors
최신 front test/typecheck/build: npm test 7/7, typecheck 성공, generate 성공(5 routes)
PostgreSQL focused 결과: V16 fixture→V17/V18/V19 3 migrations, legacy defaults, 세 constraint/unique 위반, Hibernate validate 통과
Docker/health/smoke 결과: phase1 전용 postgres:18 container만 사용 후 제거; 애플리케이션 compose/8083 smoke는 실행하지 않음
실행 중인 프로세스: 없음; phase1 container 제거 및 Gradle daemon 종료 확인
알려진 실패·차단: bare Gradle은 PATH Java 21로 실패하므로 Windows 명령에 -Porg.gradle.java.installations.paths=C:\jdk\jdk-25.0.4.1+1 필요. Phase 2 blocker 없음
다음 정확한 작업: Phase 2 rich document codec의 선행 실패 테스트 작성 후 Base64 decode·schema validation·canonical JSON·plain text extraction과 rich create/update 계약 구현
주의할 사용자 기존 변경: Phase 1 시작 전 존재한 요청 계획서는 untracked였으며 유지; 그 외 사용자 기존 source 변경 없음
```

## 13. 최종 완료 기준

- [ ] 붙여넣은 PNG/JPEG가 등록 전 editor 안에 즉시 표시된다.
- [ ] 등록 전 이미지가 서버나 DB에 고아 데이터로 생성되지 않는다.
- [ ] pending 이미지는 undo 복원이 가능하고 20개·100MB 임시 한도와 URL 해제가 검증된다.
- [ ] 저장 후 로그인·공개 상세에서 텍스트·이미지 순서가 유지된다.
- [ ] 수정에서 기존 이미지 유지·삭제와 신규 이미지 추가가 동작한다.
- [ ] 구형 plain 수정 요청이 rich 글을 변경하지 못하고 409로 거부된다.
- [ ] 일반 첨부와 inline 이미지가 기존 총 5개 제한을 일관되게 적용한다.
- [ ] 이미지 bytes는 기존 attachment volume에 있고 DB에는 metadata와 canonical 문서만 저장된다.
- [ ] canonical 문서에 `blob:`, data URL, 외부 URL, raw HTML이 저장되지 않는다.
- [ ] `posts.body` 평문으로 검색·복사·기존 front fallback이 유지된다.
- [ ] PNG/JPEG 형식, 10MB, dimensions, pixel 한도를 server가 검증한다.
- [ ] inline content endpoint가 verified content type, inline disposition, nosniff를 반환한다.
- [ ] 신규 파일 rollback 정리와 커밋 후 삭제 재시도가 검증된다.
- [ ] plain 기존 게시글과 FILE_CONVERSION_REQUEST ZIP 회귀가 없다.
- [ ] backend 전체 테스트와 PostgreSQL focused migration/transaction 검증이 통과한다.
- [ ] frontend test, typecheck, generate build가 통과한다.
- [ ] 8083 경유 health와 create/read/edit/delete HTTP smoke가 통과한다.
- [ ] API·DB·설정·보안·테스트·운영 문서가 실제 구현과 일치한다.
- [ ] 각 Phase 상태와 사용량 한도·중단·재개 기록이 최신이다.

## 14. 참고 자료

- [Tiptap 오픈소스 코어와 라이선스](https://tiptap.dev/open-source-to-platform)
- [Tiptap Nuxt 설치 가이드](https://tiptap.dev/docs/editor/getting-started/install/nuxt)
- [Tiptap Image extension](https://tiptap.dev/docs/editor/extensions/nodes/image)
- [현재 API 레퍼런스](../docs/07-api-reference.md)
- [현재 아키텍처](../docs/04-architecture.md)
- [테스트·품질 게이트](../docs/10-testing-quality.md)
- [보안 기준](../docs/14-security.md)
- [첨부 일관성](../docs/18-integrity-hardening.md)
