# 설정과 환경 변수

공통 실행 설정은 루트 `.env` 또는 EC2의 `/home/ubuntu/llm/.env`에 둡니다. `.env.example`은 새 환경을 만들 때의 형식 예시이며 secret 값은 실제 운영값으로 교체해야 합니다. 루트 `.env`는 Git 추적 대상이 아닌 환경별 파일이라, 현재 워크스페이스에서는 운영과 비슷한 DB/CORS 값을 담고 있을 수 있습니다.

## Compose 이미지와 포트

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `LLM_BACK_IMAGE` | `llm-back:1.0` | 백엔드 런타임 이미지. Hub 없음. Windows에서 빌드 후 tar로 EC2 `docker load` |
| `LLM_FRONT_IMAGE` | `llm-front:1.0` | 프론트 런타임 이미지. Hub 없음. Windows에서 빌드 후 tar로 EC2 `docker load` |
| `LLM_FRONT_PORT` | `8083` | 호스트에 공개할 front 포트 |
| `LLM_ENV_FILE` | `./.env` | back 컨테이너 `env_file` 경로. EC2 배포 명령에서 `/home/ubuntu/llm/.env`로 export |
| `COMPOSE_PROJECT_NAME` | `ubuntu` 운영 기준 | Compose project name |

## Frontend

| 변수 | 설명 |
| --- | --- |
| `NUXT_PUBLIC_API_BASE` | 프론트 빌드 시 API base URL. 비어 있으면 상대 경로 `/api/...`를 사용. **Windows에서 `nuxi generate` 할 때 번들로 굳어져 런타임 변경이 불가**하므로, 값을 바꾸려면 정적 산출물을 다시 만들고 front 이미지를 다시 빌드·load해야 합니다 |
| `LLM_API_BASE_URL` | `upload_zip_post.py`가 사용할 API base URL. 없으면 도구가 `NUXT_PUBLIC_API_BASE`를 fallback으로 사용할 수 있음 |

Nuxt dev server는 `front/nuxt.config.ts`의 `nitro.devProxy`로 `/api`를 `http://localhost:8082`에 proxy합니다.

## Backend CORS

| 변수 | 설명 |
| --- | --- |
| `APP_CORS_ALLOWED_ORIGINS` | 쉼표로 구분된 허용 origin 목록 |

운영 EC2 확인값(2026-08-27)은 `http://43.202.113.123:8083`, `https://yangyag.duckdns.org`입니다. `http://localhost:8083`은 운영 CORS에 없습니다.

## Database

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `APP_DB_HOST` | `host.docker.internal` 로컬, `yangyag-postgres` 운영 | PostgreSQL host |
| `APP_DB_PORT` | `5432` | PostgreSQL port |
| `APP_DB_NAME` | `yangyag` 로컬 예시, `llm` 운영 확인값 | database name |
| `APP_DB_USER` | 환경별 값 | database user |
| `APP_DB_PASSWORD` | secret | database password |
| `APP_DB_SCHEMA` | `public` 로컬, `llm` 운영 확인값 | Flyway/JPA schema |

이 표의 로컬 값은 `.env.example` 기준 예시입니다. 운영 EC2는 `APP_DB_HOST=yangyag-postgres`, `APP_DB_NAME=llm`, `APP_DB_SCHEMA=llm`입니다. 실제 실행 기준은 항상 대상 환경의 `.env`입니다.

백엔드 datasource URL:

```properties
jdbc:postgresql://${APP_DB_HOST}:${APP_DB_PORT}/${APP_DB_NAME}?currentSchema=${APP_DB_SCHEMA}
```

## Attachment storage

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `APP_ATTACHMENTS_ROOT_PATH` | `.env.example`/Compose 예시: `/var/lib/llm/attachments` | 첨부파일 저장 루트 |
| `APP_ATTACHMENTS_MAX_FILE_SIZE` | `100MB` | 일반 multipart 첨부파일 1개당 최대 크기 |
| `APP_ATTACHMENTS_INLINE_IMAGES_MAX_FILE_SIZE` | `10MB` | 본문에 추가하는 inline 이미지(PNG/JPEG) 1개당 최대 크기 |
| `APP_ATTACHMENTS_MAX_REQUEST_SIZE` | `500MB` | multipart 요청 전체 최대 크기(첨부 여러 개 합산) |
| `APP_ATTACHMENTS_MAX_COUNT` | `5` | 게시글당 첨부파일 최대 개수(일반 첨부 + 본문 inline 이미지 합계) |
| `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE` | `2GB` | 업로드 세션 finalize 결과 파일 최대 크기 |

`APP_ATTACHMENTS_ROOT_PATH`가 없으면 백엔드 fallback은 `${java.io.tmpdir}/llm-attachments`입니다.

일반 게시글은 한 요청에 첨부파일을 여러 개(같은 form 필드명 `attachments`로 반복) 보낼 수 있고, 합산 크기는 `APP_ATTACHMENTS_MAX_REQUEST_SIZE`의 제한을 받습니다. 파일당 상한은 `APP_ATTACHMENTS_MAX_FILE_SIZE`, 개수 상한은 `APP_ATTACHMENTS_MAX_COUNT`입니다.

본문에 추가하는 inline 이미지는 일반 첨부와 같은 개수 한도(`APP_ATTACHMENTS_MAX_COUNT`)를 공유하고, 파일당 크기는 `APP_ATTACHMENTS_INLINE_IMAGES_MAX_FILE_SIZE`(기본 `10MB`)를 따릅니다. 이미지 최대 너비·높이(각각 8192px)와 최대 총 픽셀 수(25,000,000)는 `back/src/main/java/com/llm/app/board/service/InlineImageValidator.java`의 코드 상수(`MAX_WIDTH`/`MAX_HEIGHT`/`MAX_PIXELS`)로 고정되어 있어 환경 변수나 프로퍼티로 조정할 수 없습니다. 한도를 넘는 이미지는 저장 전에 거부됩니다. 브라우저에서 파일을 고르는 단계의 실효 한도는 `front/composables/useInlineImageDraft.ts`에 하드코딩된 `MAX_INLINE_IMAGE_FILE_SIZE`(10MB)이므로, `APP_ATTACHMENTS_INLINE_IMAGES_MAX_FILE_SIZE`를 바꿀 때는 이 값도 함께 맞춰야 합니다.

`APP_ATTACHMENTS_MAX_REQUEST_SIZE`와 front `nginx.conf`의 `client_max_body_size`(현재 `500M`)는 함께 맞춰야 합니다. 둘 중 작은 값이 실효 상한이며, 8083(front proxy) 경유 요청은 nginx 한도를 먼저 거칩니다. nginx 값은 정적 설정이라 키우려면 front 이미지를 다시 빌드해야 합니다.

multipart 요청의 텍스트 필드(파일 제외) 합계는 Tomcat `maxPostSize`가 따로 제한합니다. `application.properties`의 `server.tomcat.max-http-form-post-size=8MB`로 고정되어 있으며 환경 변수는 없습니다. rich 문서 최대 5MiB(Base64 약 7MB)와 plain 본문 100만 자(Base64 최대 약 4MB)가 들어가도록 정한 값입니다. Tomcat 기본값 2MB에서는 한글 약 50만 자 이상의 본문이 413 `ATTACHMENT_TOO_LARGE`로 거부됐습니다(2026-09-25 수정). 본문 codec 한도를 키울 때는 이 값도 함께 맞춥니다.

## Upload sessions

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `APP_UPLOAD_SESSIONS_ROOT_PATH` | `.env.example`/Compose 예시: `/var/lib/llm/upload-sessions` | 청크 임시 저장 루트 |
| `APP_UPLOAD_SESSIONS_EXPIRATION_MS` | `86400000` | 세션 만료 시간 |
| `APP_UPLOAD_SESSIONS_CLEANUP_FIXED_DELAY_MS` | `3600000` | 만료 세션 정리 주기 |
| `APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE` | `8MB` | 청크 1개의 decode 후 최대 크기. `.env.example`과 백엔드 default 모두 `8MB`. 암호화 JSON 필드 한도 때문에 실효 상한은 11,249,976바이트(Base64 14,999,968자)이며, 더 큰 값을 설정하면 기동 시 경고를 남기고 이 값으로 낮춘다 |
| `APP_UPLOAD_SESSIONS_SECRET` | secret | 백엔드 AES-GCM wire codec secret |
| `LLM_UPLOAD_SESSIONS_SECRET` | secret | 업로드 스크립트 전용 override. 없으면 `APP_UPLOAD_SESSIONS_SECRET` 사용 |
| `LLM_UPLOAD_CHUNK_SIZE_BASE64_CHARS` | `1398104` | 업로드 스크립트 기본 base64 청크 길이 |

`APP_UPLOAD_SESSIONS_SECRET`와 스크립트가 사용하는 secret은 반드시 같아야 합니다.

`APP_UPLOAD_SESSIONS_ROOT_PATH`가 없으면 백엔드 fallback은 `${java.io.tmpdir}/llm-upload-sessions`입니다.

## Auth

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `APP_JWT_SECRET` | secret | JWT HS256 서명 secret. 운영 필수 |
| `APP_JWT_EXPIRATION_MS` | `3600000` | JWT 만료 시간. 프론트의 유휴 자동 로그아웃(1시간)은 이 값과 무관한 `front/composables/useIdleTimeout.ts`의 하드코딩 상수 `IDLE_TIMEOUT_MS`로, 환경 변수로 조정되지 않습니다(양쪽 변경 시 함께 맞춰야 함) |
| `APP_AUTH_LOGIN_MAX_FAILURES` | `10` | 클라이언트 IP 하나가 한 구간에서 할 수 있는 로그인 시도 수. 넘으면 429 `TOO_MANY_LOGIN_ATTEMPTS`. 성공하면 그 IP 기록 초기화 |
| `APP_AUTH_LOGIN_WINDOW` | `15m` | 로그인 시도 수를 세는 구간(Spring Duration 형식) |
| `LLM_JWT_TOKEN` | 선택 | 업로드 스크립트가 직접 사용할 JWT |
| `LLM_USERNAME` | 선택 | 업로드 스크립트 로그인 계정 |
| `LLM_PASSWORD` | 선택 | 업로드 스크립트 로그인 비밀번호 |

백엔드 코드에는 개발 fallback secret이 있지만 운영에서는 사용하지 않습니다.

로그인 시도 제한은 클라이언트 IP 단위이고 백엔드 메모리에만 기록되므로 재시작하면 초기화됩니다. 계정 단위로 잠그지 않는 이유는 남이 일부러 틀려서 관리자 로그인을 막을 수 있기 때문입니다. 클라이언트 IP는 `application.properties`의 `server.forward-headers-strategy=native`(Tomcat RemoteIpValve)가 `X-Forwarded-For`를 오른쪽부터 읽어 내부망 주소(10/8, 172.16/12, 192.168/16, 127/8 등)를 건너뛴 첫 주소로 정합니다. 운영 경로(호스트 nginx → llm-front nginx → back)에서는 두 nginx가 모두 `$proxy_add_x_forwarded_for`를 붙이므로 클라이언트가 보낸 위조 값은 앞쪽에 남아 무시됩니다. 앞단 프록시가 공인 IP 대역에 있거나 `X-Forwarded-For`를 붙이지 않게 바뀌면 모든 요청이 같은 IP로 세어져 한도가 사이트 전체에 걸리므로 이 설정을 함께 확인해야 합니다.

## AI providers

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `OPENAI_API_KEY` | secret | GPT provider API key |
| `OPENAI_MODEL` | 환경별 값 | GPT provider model |
| `OPENAI_API_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible API base |
| `ANTHROPIC_API_KEY` | secret | Claude provider API key |
| `ANTHROPIC_MODEL` | 환경별 값 | Anthropic model |
| `ANTHROPIC_API_BASE_URL` | `https://api.anthropic.com/v1` | Anthropic API base |
| `XAI_API_KEY` | secret | Grok provider API key |
| `XAI_MODEL` | 환경별 값 | xAI model |
| `XAI_API_BASE_URL` | `https://api.x.ai/v1` | xAI API base |

로컬 `.env.example`과 현재 로컬 `.env` 예시값(2026-09-25 확인, EC2 값과 같음):

- `OPENAI_MODEL=gpt-5.5`
- `ANTHROPIC_MODEL=claude-opus-4-7`
- `XAI_MODEL=grok-4.3`

AI 답변은 2026-09-03에 종료되어 이 값들은 새 provider 호출에 쓰이지 않습니다(docs/09).

2026-05-31 KST에 SSH로 확인한 EC2 `/home/ubuntu/llm/.env` 값:

- `OPENAI_MODEL=gpt-5.5`
- `ANTHROPIC_MODEL=claude-opus-4-7`
- `XAI_MODEL=grok-4.3`

모델 값은 환경별 `.env`가 우선합니다. 로컬 예시와 EC2 운영값이 다를 수 있으므로, 배포 전에는 대상 환경의 `.env`를 기준으로 확인합니다.

## Secret 관리 기준

- secret 값은 문서에 쓰지 않습니다.
- `.env.example`에는 실제 값 대신 placeholder만 둡니다.
- 운영 `.env` 권한은 최소한으로 제한합니다.
- 배포 전 `grep -E 'SECRET|PASSWORD|API_KEY|TOKEN'` 결과를 화면 공유나 로그에 남기지 않습니다.
