# 설정과 환경 변수

공통 실행 설정은 루트 `.env` 또는 EC2의 `/home/ubuntu/llm/.env`에 둡니다. `.env.example`은 새 환경을 만들 때의 형식 예시이며 secret 값은 실제 운영값으로 교체해야 합니다. 루트 `.env`는 Git 추적 대상이 아닌 환경별 파일이라, 현재 워크스페이스에서는 운영과 비슷한 DB/CORS 값을 담고 있을 수 있습니다.

## Compose 이미지와 포트

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `LLM_BACK_IMAGE` | `yangyag2/llm-back:latest` | 백엔드 런타임 이미지 |
| `LLM_FRONT_IMAGE` | `yangyag2/llm-front:latest` | 프론트 런타임 이미지 |
| `LLM_FRONT_PORT` | `8083` | 호스트에 공개할 front 포트 |
| `LLM_ENV_FILE` | `./.env` | back 컨테이너 `env_file` 경로. EC2 배포 명령에서 `/home/ubuntu/llm/.env`로 export |
| `COMPOSE_PROJECT_NAME` | `ubuntu` 운영 기준 | Compose project name |

## Frontend

| 변수 | 설명 |
| --- | --- |
| `NUXT_PUBLIC_API_BASE` | 프론트 빌드 시 API base URL. 비어 있으면 상대 경로 `/api/...`를 사용. **빌드 타임에 번들로 굳어져 런타임 변경이 불가**하므로, 값을 바꾸려면 front 이미지를 재빌드해야 합니다 |
| `LLM_API_BASE_URL` | `upload_zip_post.py`가 사용할 API base URL. 없으면 도구가 `NUXT_PUBLIC_API_BASE`를 fallback으로 사용할 수 있음 |

Nuxt dev server는 `front/nuxt.config.ts`의 `nitro.devProxy`로 `/api`를 `http://localhost:8082`에 proxy합니다.

## Backend CORS

| 변수 | 설명 |
| --- | --- |
| `APP_CORS_ALLOWED_ORIGINS` | 쉼표로 구분된 허용 origin 목록 |

운영 EC2 확인값에는 `http://43.202.113.123:8083`, `http://localhost:8083`, `https://yangyag.duckdns.org`가 포함되어 있었습니다.

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
| `APP_ATTACHMENTS_MAX_REQUEST_SIZE` | `500MB` | multipart 요청 전체 최대 크기(첨부 여러 개 합산) |
| `APP_ATTACHMENTS_MAX_COUNT` | `5` | 일반 게시글당 첨부파일 최대 개수 |
| `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE` | `2GB` | 업로드 세션 finalize 결과 파일 최대 크기 |

`APP_ATTACHMENTS_ROOT_PATH`가 없으면 백엔드 fallback은 `${java.io.tmpdir}/llm-attachments`입니다.

일반 게시글은 한 요청에 첨부파일을 여러 개(같은 form 필드명 `attachments`로 반복) 보낼 수 있고, 합산 크기는 `APP_ATTACHMENTS_MAX_REQUEST_SIZE`의 제한을 받습니다. 파일당 상한은 `APP_ATTACHMENTS_MAX_FILE_SIZE`, 개수 상한은 `APP_ATTACHMENTS_MAX_COUNT`입니다.

`APP_ATTACHMENTS_MAX_REQUEST_SIZE`와 front `nginx.conf`의 `client_max_body_size`(현재 `500M`)는 함께 맞춰야 합니다. 둘 중 작은 값이 실효 상한이며, 8083(front proxy) 경유 요청은 nginx 한도를 먼저 거칩니다. nginx 값은 정적 설정이라 키우려면 front 이미지를 다시 빌드해야 합니다.

## Upload sessions

| 변수 | 기본/예시 | 설명 |
| --- | --- | --- |
| `APP_UPLOAD_SESSIONS_ROOT_PATH` | `.env.example`/Compose 예시: `/var/lib/llm/upload-sessions` | 청크 임시 저장 루트 |
| `APP_UPLOAD_SESSIONS_EXPIRATION_MS` | `86400000` | 세션 만료 시간 |
| `APP_UPLOAD_SESSIONS_CLEANUP_FIXED_DELAY_MS` | `3600000` | 만료 세션 정리 주기 |
| `APP_UPLOAD_SESSIONS_MAX_DECODED_CHUNK_SIZE` | `100MB` | 청크 1개의 decode 후 최대 크기. `.env.example`에 포함되며 백엔드 default도 `100MB` |
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
| `LLM_JWT_TOKEN` | 선택 | 업로드 스크립트가 직접 사용할 JWT |
| `LLM_USERNAME` | 선택 | 업로드 스크립트 로그인 계정 |
| `LLM_PASSWORD` | 선택 | 업로드 스크립트 로그인 비밀번호 |

백엔드 코드에는 개발 fallback secret이 있지만 운영에서는 사용하지 않습니다.

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

로컬 `.env.example`과 현재 로컬 `.env` 예시값:

- `OPENAI_MODEL=gpt-5.4`
- `ANTHROPIC_MODEL=claude-sonnet-4-6`
- `XAI_MODEL=grok-4.20-0309-reasoning`

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
