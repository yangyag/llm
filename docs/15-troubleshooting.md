# 문제 해결

## Docker Compose가 `auto_default`를 찾지 못함

증상:

```text
network auto_default declared as external, but could not be found
```

확인:

```bash
docker network ls | grep auto_default
```

대응:

- EC2에서는 `yangyag-postgres`가 붙은 기존 `auto_default` 네트워크가 있어야 합니다.
- 로컬에서 DB를 다른 방식으로 쓰는 경우 compose network 구성을 조정하거나 외부 네트워크를 생성합니다.

```bash
docker network create auto_default
```

## 백엔드 health가 호스트 8080에서 실패

EC2와 기본 compose에서는 `back`이 `expose: 8080`만 사용합니다. 호스트에 직접 publish되지 않습니다.

정상 확인:

```bash
curl -fsS http://127.0.0.1:8083/api/v1/health
```

컨테이너 health:

```bash
docker inspect --format '{{.Name}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' llm-back
```

## 프론트 dev server에서 API 호출 실패

`front/nuxt.config.ts`의 `nitro.devProxy`는 `/api` 요청을 `http://localhost:8082`로 보냅니다.

대응:

```bash
cd back
APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun
```

또는 Nitro dev proxy target을 백엔드 실제 포트로 수정합니다.

## DB 연결 실패

증상:

- `Connection refused`
- `UnknownHostException: yangyag-postgres`
- Flyway migration 실패
- `FATAL: password authentication failed for user "<db-user>"` (백엔드 로그, Flyway 단계)

확인:

```bash
docker ps | grep postgres
docker network inspect auto_default
grep -E '^(APP_DB_HOST|APP_DB_PORT|APP_DB_NAME|APP_DB_SCHEMA|APP_DB_USER)=' .env
```

대응:

- 운영: `APP_DB_HOST=yangyag-postgres`, `APP_DB_NAME=llm`, `APP_DB_SCHEMA=llm`
- 로컬: 실제 PostgreSQL 위치와 DB/schema가 있는지 확인
- 비밀번호 실패 시 DB 컨테이너 안에서 해당 롤 비밀번호를 재설정하고 `.env`의 `APP_DB_PASSWORD`와 맞춥니다(값 자체는 문서·로그에 기록 금지).
- schema는 Flyway `create-schemas=true`로 생성 가능하지만 DB 자체와 권한은 먼저 있어야 합니다.

## 이미지 내장(baked) DB를 로컬에서 쓸 때 권한/owner 문제

`yangyag2/postgres` 같은 이미지 내장 DB는 데이터 디렉터리(PGDATA)가 이미 들어 있어 컨테이너 시작 시 DB/롤/비밀번호 초기화가 건너뛰어집니다(`Skipping initialization`). 포함된 데이터가 예전 Flyway 버전(V12 등)까지만 적용된 상태라면, 현행 백엔드 기동 시 V13+ 마이그레이션을 시도하면서 아래 순서로 실패할 수 있습니다(2026-09-04 로컬 확인).

증상(백엔드 로그 순서):

1. `FATAL: password authentication failed for user "yangyag"` — 이미지 안 롤 비밀번호가 `.env`와 다름
2. `ERROR: permission denied for schema llm` — 접속 유저에 스키마 `USAGE, CREATE` 권한 없음
3. `ERROR: must be owner of table <table>` — 테이블 owner가 다른 롤(예: `llm_local`)이라 `ALTER TABLE` 불가

확인:

```bash
docker logs yangyag-postgres --tail 20
docker exec yangyag-postgres psql -U postgres -d llm_local -c "SELECT tablename, tableowner FROM pg_tables WHERE schemaname='llm';"
```

대응(로컬 전용, 운영 DB에는 적용 금지):

```bash
docker exec yangyag-postgres psql -U postgres -c "ALTER USER yangyag WITH PASSWORD '<db-password>';"
docker exec yangyag-postgres psql -U postgres -d llm_local -v ON_ERROR_STOP=1 \
  -c "GRANT USAGE, CREATE ON SCHEMA llm TO yangyag;"
docker exec yangyag-postgres psql -U postgres -d llm_local -v ON_ERROR_STOP=1 \
  -c "ALTER SCHEMA llm OWNER TO yangyag;" \
  -c "ALTER TABLE llm.flyway_schema_history OWNER TO yangyag;" \
  -c "ALTER TABLE llm.posts OWNER TO yangyag;" \
  -c "ALTER TABLE llm.post_attachments OWNER TO yangyag;" \
  -c "ALTER TABLE llm.upload_session_parts OWNER TO yangyag;" \
  -c "ALTER TABLE llm.upload_sessions OWNER TO yangyag;" \
  -c "ALTER TABLE llm.post_replies OWNER TO yangyag;" \
  -c "ALTER TABLE llm.admins OWNER TO yangyag;"
docker restart llm-back
```

주의:

- `GRANT ALL ON ALL TABLES`만으로는 `ALTER TABLE`에 필요한 owner 권한이 해결되지 않습니다. owner 변경이 필요합니다.
- 권한을 고친 뒤에는 `docker restart llm-back`(또는 `docker compose up -d --wait`)으로 백엔드를 재시작해야 마이그레이션이 다시 시도됩니다. 컨테이너 healthcheck 간격(10초) 때문에 판정까지 시간이 걸립니다.
- 시드 데이터가 있는 테이블에 V13+를 적용하면 백필(V15 등)이 기존 행을 갱신할 수 있습니다. 로컬 확인용 DB에서만 수행합니다.

## Git Bash에서 `docker exec`로 SQL 파일 넣기 실패

Windows Git Bash에서 호스트 SQL 파일을 `docker exec -i`의 stdin 리다이렉션으로 psql에 넣으면 경로 자동 변환과 `search_path` 미적용으로 실패할 수 있습니다(2026-09-04 로컬 확인).

실패 예:

- `cat: 'C:/Program Files/Git/var/lib/postgresql/data/pg_hba.conf'` — 컨테이너 안 리눅스 경로가 Git Bash 경로 변환을 타서 생긴 오류. `docker exec <컨테이너> cat ...` 처럼 컨테이너 기준으로 실행해야 합니다.
- `relation "admins" does not exist` — DB에는 `llm.admins`로 존재하지만 psql 기본 `search_path`(`"$user", public`)에 `llm`이 없어 생긴 오류. `--set search_path=...` 같은 접속 문자열 옵션으로는 해결되지 않았습니다.

대응:

- 컨테이너 안 파일은 `MSYS_NO_PATHCONV=1 docker exec <컨테이너> cat /리눅스/경로` 형태로 읽습니다.
- 스키마 한정 테이블에는 SQL에서 스키마를 직접 지정(`llm.admins`)하거나, `PGOPTIONS="--search-path=llm"` 환경 변수를 `docker exec -e PGOPTIONS`로 전달합니다.

```bash
MSYS_NO_PATHCONV=1 docker exec yangyag-postgres cat /var/lib/postgresql/data/pg_hba.conf
PGOPTIONS="--search-path=llm" MSYS_NO_PATHCONV=1 docker exec -i -e PGOPTIONS yangyag-postgres \
  psql -U postgres -d llm_local -v ON_ERROR_STOP=1 < ./V13__add_role_to_admins.sql
```

## Flyway checksum mismatch로 백엔드 시작 실패

증상:

```text
Migration checksum mismatch for migration version 13
-> Applied to database : -1323145214
-> Resolved locally    : 862869711
```

원인:

- EC2 운영 DB에 이미 적용된 마이그레이션과 새 이미지에 포함된 마이그레이션 파일의 내용(체크섬)이 다릅니다.
- 예: 2026-08-08 배포에서 EC2 history의 V13이 구버전 `create ai reply jobs`였고 로컬 저장소의 V13은 `add role to admins`였음.

확인:

```bash
DB_PASS=$(grep "^APP_DB_PASSWORD=" /home/ubuntu/llm/.env | cut -d= -f2-)
docker exec -e PGPASSWORD="$DB_PASS" yangyag-postgres psql -U llm -d llm   -c "select installed_rank, version, description, checksum, success from llm.flyway_schema_history order by installed_rank;"
ls /home/yangyag/llm/back/src/main/resources/db/migration/
```

대응:

1. history와 로컬 파일의 version/description을 비교해 어떤 버전이 어긋났는지 확인합니다.
2. 어긋난 버전이 현재 코드에 없는(제거된) 기능이면, DB 백업 후 해당 history 행만 제거합니다.

   ```bash
   docker exec -e PGPASSWORD="$DB_PASS" yangyag-postgres psql -U llm -d llm      -c "delete from llm.flyway_schema_history where version='13';"
   ```

3. `docker compose ... up -d --wait`로 재배포해 새 마이그레이션을 적용합니다.
4. 어긋난 버전이 현재 코드에 필요한 기능이면 Flyway repair로 체크섬을 갱신하는 대신, migration 파일을 새 버전으로 추가하는 방식으로 해결합니다(기존 적용 파일 수정 금지).

주의: history 행 삭제는 테이블(예: ai_reply_jobs)을 삭제하지 않습니다. JPA 엔티티가 없는 테이블은 `ddl-auto=validate`에 영향이 없어 그대로 둬도 무해합니다.

## 로그인 실패

확인:

```bash
docker logs --tail 100 llm-back
```

가능 원인:

- username이 영문/숫자가 아님
- 비밀번호 불일치
- 운영 기본 계정 변경
- JWT secret 변경 후 기존 token 사용

대응:

- 브라우저 localStorage의 `auth_token`, `auth_username`, `auth_last_activity` 삭제 후 재로그인
- DB `admins` 계정 확인

## 사용 중 갑자기 로그아웃됨

프론트에는 자동 로그아웃 동작이 있습니다. 다음 경우는 정상 동작이며 백엔드 장애가 아닙니다.

가능 원인:

- 로그인 상태에서 마지막 사용자 활동(클릭/키 입력/스크롤/터치) 후 1시간 동안 아무 동작이 없으면 자동 로그아웃됩니다(유휴 타임아웃, 프론트 하드코딩 상수 1시간).
- 마지막 활동 시각은 브라우저 `localStorage`의 `auth_last_activity`에 보존되며, 리로드나 탭 복원으로 유휴 데드라인이 초기화되지 않습니다. 절전/탭 복귀 시점에 유휴 시간이 재평가됩니다.
- 인증이 필요한 API 요청이 `401`을 받으면(예: 백엔드 JWT가 `APP_JWT_EXPIRATION_MS` 기본 1시간 후 만료) 즉시 강제 로그아웃됩니다. 백엔드는 토큰 갱신/슬라이딩 세션 없이 고정 만료입니다.

대응:

- 정상 동작이므로 다시 로그인합니다.
- 유휴 시간(1시간)은 프론트 상수이며 환경 변수로 조정하지 않습니다. 변경이 필요하면 `front/composables/useIdleTimeout.ts`의 `IDLE_TIMEOUT_MS`를 수정 후 front 이미지를 재빌드합니다.

## 게시글 body가 깨짐

일반 게시글은 본문을 비워둘 수 있습니다. 게시글/댓글 쓰기 API에서 본문을 보낼 때는 `bodyBase64`에 UTF-8 문자열을 Base64로 인코딩해 전달해야 합니다.

프론트는 `js-base64`의 `fromUint8Array(new TextEncoder().encode(value))` 방식을 사용합니다.

## 첨부파일 업로드 실패

가능 원인:

- `APP_ATTACHMENTS_MAX_FILE_SIZE`(파일당) 초과 → 413 `ATTACHMENT_TOO_LARGE`
- `APP_ATTACHMENTS_MAX_REQUEST_SIZE`(여러 첨부 합산) 또는 nginx `client_max_body_size` 초과 → 413
- 첨부 개수가 `APP_ATTACHMENTS_MAX_COUNT`(기본 5)를 초과 → 400 `INVALID_ATTACHMENT_REQUEST`
- `removeAttachmentIds`에 해당 게시글 첨부가 아닌 id 포함 → 400 `INVALID_ATTACHMENT_REQUEST`
- attachment volume 쓰기 권한 또는 용량 문제

확인:

```bash
docker logs --tail 200 llm-back
df -h
docker volume ls | grep llm-back-attachments
```

## ZIP 업로드 finalize 실패

가능 원인:

- 일부 chunk 누락
- chunk 번호가 연속되지 않음
- chunk 크기 불일치
- 최종 SHA-256 불일치
- 세션 만료
- secret 불일치
- `APP_ATTACHMENTS_MAX_GENERATED_FILE_SIZE` 초과
- `APP_UPLOAD_SESSIONS_ROOT_PATH` 미설정으로 예상한 volume이 아닌 JVM temp 경로를 사용하는 상황

대응:

1. sidecar 파일과 원본 ZIP이 같은지 확인합니다.
2. secret 값을 맞춥니다.
3. 세션이 오래되었으면 새 세션을 만듭니다.
4. 업로드 세션 저장 경로가 env와 컨테이너 내부에서 어떻게 잡혔는지 확인합니다.
5. 백엔드 로그에서 정확한 오류 코드를 확인합니다.

```bash
cd /home/ubuntu/llm
grep -E '^APP_UPLOAD_SESSIONS_ROOT_PATH=' .env || true
docker inspect llm-back --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E '^APP_UPLOAD_SESSIONS_ROOT_PATH=' || true
docker exec llm-back sh -lc 'ls -ld /var/lib/llm/upload-sessions /tmp/llm-upload-sessions 2>/dev/null || true'
```

## AI 답변 실패

오류별 대응:

| 오류 | 대응 |
| --- | --- |
| `AI_PROVIDER_NOT_CONFIGURED` | provider API key 설정 |
| `INVALID_AI_PROVIDER` | `GPT`, `CLAUDE`, `GROK` 중 하나 사용 |
| `AI_REPLY_NOT_ALLOWED` | 대상 게시글이 `FILE_CONVERSION_REQUEST`가 아닌 일반 게시글인지 확인 |
| `AI_REPLY_GENERATION_FAILED` | 외부 API status, model, base URL 확인 |

확인:

```bash
grep -E '^(OPENAI_MODEL|ANTHROPIC_MODEL|XAI_MODEL)=' /home/ubuntu/llm/.env
docker logs --tail 200 llm-back
```

secret key 값은 출력하지 않습니다.

## 배포 후 이전 화면이 계속 보임

가능 원인:

- Windows에서 새 `llm-front:1.0` / `llm-back:1.0` tar를 만들지 않음
- EC2에서 `docker load` 하지 않음
- tar를 `/tmp`에 두어 snap Docker가 load하지 못함 (`/home/ubuntu/llm/`에 둘 것)
- 브라우저 캐시
- `NUXT_PUBLIC_API_BASE`가 generate 시점에 잘못 들어감

대응:

```powershell
.\aws\deploy-front.ps1
.\aws\deploy-back.ps1
```

```bash
docker image ls | grep 'llm-'
docker logs --tail 100 llm-front
docker logs --tail 100 llm-back
```

브라우저 hard refresh도 확인합니다.

## EC2 SSH 접속 실패

확인:

```bash
./aws/connect.sh 'echo CONNECTED'
```

또는 직접 ssh:

```bash
ssh -o ConnectTimeout=8 -i aws/test-keypair.pem ubuntu@43.202.113.123
```

가능 원인:

- PEM 권한 오류 (Windows는 `aws/connect.ps1` 사용 또는 `icacls <key> /inheritance:r`, `icacls <key> /grant:r "$($env:USERNAME):R"`)
- 접속 사용자 오류
- 보안그룹에서 SSH inbound 미허용
- 접속 위치 공인 IP 변경
- EC2 중지 또는 IP 변경

## 문서와 운영 상태가 다를 때

운영 기준은 항상 EC2 실제 파일과 컨테이너 상태입니다.

```bash
ssh -i aws/test-keypair.pem ubuntu@43.202.113.123
cd /home/ubuntu/llm
ls -l
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
curl -fsS http://127.0.0.1:8083/api/v1/health
```

문서가 틀렸다면 운영 상태를 근거로 문서를 갱신합니다.
