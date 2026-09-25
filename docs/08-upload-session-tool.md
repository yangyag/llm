# ZIP 청크 업로드 도구

단일 ZIP 업로드 도구는 `front/public/upload_zip_post.zip`으로 배포됩니다. ZIP 내부에는 `upload_zip_post.py`가 들어 있습니다.

## 현재 저장소 상태

```bash
unzip -l front/public/upload_zip_post.zip
```

확인 결과:

```text
upload_zip_post.py
```

루트의 `upload_zip_post.py` 원본은 `.gitignore`에 제외되어 있고 현재 저장소 파일 목록에는 없습니다. 운영자는 배포된 ZIP을 내려받아 스크립트를 추출해 사용해야 합니다.

## 도구 역할

1. 현재 디렉터리에서 업로드할 `.zip` 파일 1개를 선택합니다.
2. ZIP 전체 SHA-256과 파일 크기를 계산합니다.
3. ZIP 바이트를 base64로 인코딩합니다.
4. base64 문자열을 지정 크기 청크로 나눕니다.
5. 업로드 세션 생성, chunk 업로드, finalize API를 호출합니다.
6. 중단되면 sidecar 파일로 이어올릴 수 있습니다.

## 필수 환경 변수

| 변수 | 설명 |
| --- | --- |
| `LLM_API_BASE_URL` | API base URL. 없으면 `NUXT_PUBLIC_API_BASE` 사용 가능 |
| `LLM_JWT_TOKEN` | 이미 발급받은 JWT. 있으면 로그인 생략 |
| `LLM_USERNAME` | JWT가 없을 때 로그인 username |
| `LLM_PASSWORD` | JWT가 없을 때 로그인 password |
| `LLM_UPLOAD_SESSIONS_SECRET` | 스크립트 전용 업로드 암호화 secret |
| `APP_UPLOAD_SESSIONS_SECRET` | `LLM_UPLOAD_SESSIONS_SECRET`이 없을 때 fallback |
| `LLM_UPLOAD_CHUNK_SIZE_BASE64_CHARS` | base64 청크 길이 |

백엔드의 `APP_UPLOAD_SESSIONS_SECRET`와 스크립트가 사용하는 secret은 같아야 합니다.

## 실행 예시

```bash
unzip front/public/upload_zip_post.zip -d /tmp/llm-upload-tool
cd /tmp/llm-upload-tool
LLM_API_BASE_URL=http://localhost:8083 \
LLM_USERNAME=<username> \
LLM_PASSWORD=<password> \
APP_UPLOAD_SESSIONS_SECRET='...' \
python3 upload_zip_post.py
```

운영 도메인 기준:

```bash
LLM_API_BASE_URL=https://yangyag.duckdns.org \
LLM_JWT_TOKEN='<jwt>' \
LLM_UPLOAD_SESSIONS_SECRET='...' \
python3 upload_zip_post.py
```

큰 ZIP은 finalize 응답이 늦을 수 있습니다. 서버가 청크 합치기·SHA-256 계산·첨부 복사를 모두 끝낸 뒤에야 응답하기 때문입니다. 스크립트의 HTTP 대기 시간은 기본 30초이고(`--timeout`, 인증·청크·finalize 공통), 운영 경로에는 EC2 호스트 nginx(기본 60초)와 llm-front nginx(`proxy_read_timeout 600s`)의 대기 시간도 따로 있습니다. 먼저 끝나는 쪽이 실패를 알려도 백엔드는 게시글을 계속 만들므로, 실패로 끝나면 다시 실행하기 전에 게시판에 결과 글이 생겼는지 확인합니다. 수백 MB 이상이면 `--timeout 600`처럼 늘려서 실행합니다(호스트 nginx 60초가 여전히 먼저 끊을 수 있음).

## 청크 크기

기본값은 `.env.example` 기준입니다.

```env
LLM_UPLOAD_CHUNK_SIZE_BASE64_CHARS=1398104
```

서버는 다음 제약을 검증합니다.

- `chunkSizeBase64Chars`는 4의 배수여야 합니다.
- `totalChunks`는 `fileSizeBytes`와 `chunkSizeBase64Chars`로 계산한 값과 같아야 합니다.
- decode 후 chunk 크기는 `APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE`(기본 `8MB`) 이하이어야 합니다. 세션 생성 단계에서 검사하므로 너무 큰 청크 크기는 청크를 올리기 전에 400 `INVALID_UPLOAD_SESSION_REQUEST`로 거부됩니다.
- 이 설정과 무관한 전송 상한이 있습니다. 청크는 암호화된 JSON 문자열 필드(`A11`) 하나로 전송되고 Jackson 기본 문자열 길이 한도는 2천만 자이므로, decode 후 11,249,976바이트(`--chunk-size-base64-chars` 14,999,968)가 최대입니다. 설정값이 이보다 크면 백엔드가 기동 시 경고를 남기고 이 값으로 낮춥니다. 2026-09-25 이전에는 이 상한을 넘는 청크 크기로도 세션이 만들어진 뒤 모든 청크 업로드가 400으로 실패했습니다.
- 최종 파일 크기는 `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE` 이하이어야 합니다.

## 암호화 wire format

업로드 세션 생성, 상태 조회, chunk 업로드 요청/상태 응답 JSON은 `A1`, `A2` 같은 alias 필드만 사용합니다. 각 값은 AES-GCM 암호문을 base64url로 인코딩한 문자열입니다.

암호문 payload:

```text
version 1 byte + nonce 12 bytes + ciphertext/tag
```

AAD는 alias 이름입니다. 예를 들어 `A1` 값을 다른 alias로 옮기면 복호화가 실패합니다.

`finalize` 응답은 alias 암호문이 아니라 `id`, `title`, `body`, `mode`, `conversionReady`, 작성자·시각, `attachments`(각 `downloadUrl` 포함), `replies`를 담는 기존 게시글 상세와 호환되는 board-detail-shaped 일반 JSON 응답입니다. 이는 upload가 board의 공개 생성 결과를 외부 상세 형태로 매핑한 결과이며, 내부 Java DTO 클래스명은 API/wire 계약이 아닙니다.

## 재개 동작

업로드 중간에 끊기면 `<archive>.llm-upload-session.json` sidecar를 사용해 누락 chunk만 이어올릴 수 있습니다. 현재 청크 모델과 호환되지 않는 예전 byte-size sidecar는 새 세션으로 다시 만들어야 합니다.

## 결과 게시글

finalize 성공 시 서버는 다음을 생성합니다.

- `posts.mode=FILE_CONVERSION_REQUEST`
- 제목: 업로드 ZIP 기반 자동 제목. 200자 이내로 제한하며 UTF-16 surrogate pair를 나누지 않습니다. 원본 파일명은 첨부 메타데이터에 보존합니다.
- 본문: 원본 파일명, 크기, chunk 수, SHA-256 정보
- 첨부파일: 원본 ZIP

이 게시글은 첨부파일이 있으면 수정할 수 없습니다. AI 답변 생성도 허용되지 않습니다.

## 운영 점검

업로드 실패 시 확인 순서:

1. JWT가 유효한지 확인합니다.
2. 스크립트 secret과 백엔드 `APP_UPLOAD_SESSIONS_SECRET`가 같은지 확인합니다.
3. `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE`보다 큰 ZIP인지 확인합니다.
4. `APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE`보다 큰 chunk를 보내는지 확인합니다.
5. `docker logs --tail 200 llm-back`에서 `INVALID_UPLOAD_SESSION_REQUEST`, `UPLOAD_SESSION_STATE_ERROR`, `ATTACHMENT_STORAGE_ERROR`를 확인합니다.
6. 세션이 만료된 경우 새 세션으로 다시 업로드합니다.
