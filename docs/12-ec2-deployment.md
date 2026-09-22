# EC2 배포

EC2 접속과 운영 파일 확인에는 이 저장소 루트의 `aws/` 폴더를 사용합니다. 접속 스크립트(`connect.sh`, `connect.ps1`)는 Git에 포함되고, PEM 키만 Git에서 제외합니다.

## 접속 정보

로컬 접속 자료:

| 항목 | 값 |
| --- | --- |
| PEM key | `aws/test-keypair.pem` (로컬 전용, Git 제외) |
| SSH user | `ubuntu` |
| Host | `43.202.113.123` |
| 운영 디렉터리 | `/home/ubuntu/llm` |

접속 스크립트:

```bash
# Git Bash / WSL
./aws/connect.sh            # 대화형 접속
./aws/connect.sh 'docker ps' # 원격 명령 실행
```

```powershell
# PowerShell
.\aws\connect.ps1
.\aws\connect.ps1 "docker ps"
```

스크립트는 키 권한(600/icacls)을 자동 정리합니다. 직접 ssh를 쓸 경우:

```bash
ssh -i aws/test-keypair.pem ubuntu@43.202.113.123
```

처음 접속 시 host key 확인 질문이 나오면 fingerprint를 확인한 뒤 진행합니다.

## 현재 EC2 운영 파일

EC2에서 확인된 파일:

| 파일 | 상태 |
| --- | --- |
| `/home/ubuntu/llm/.env` | 존재 |
| `/home/ubuntu/llm/docker-compose.yml` | 존재 |

`/home/yangyag/aws`의 기존 메모에는 다른 프로젝트용 `/home/ubuntu/auto` 경로와 예전 LLM 경로인 `/home/ubuntu/llm.env`, `/home/ubuntu/docker-compose.ec2.yml`, `/home/yangyag/playground/test-keypair.pem`가 남아 있습니다. LLM 운영 작업의 우선 기준은 실제 EC2에서 확인한 `/home/ubuntu/llm` 경로이며, 접속 자료는 이 저장소 루트의 `aws/` 폴더를 사용합니다.

## 현재 LLM 관련 컨테이너 상태

기대 상태:

```text
llm-front         llm-front:1.0              healthy  0.0.0.0:8083->80/tcp  mem_limit 64m
llm-back          llm-back:1.0               healthy  8080/tcp
yangyag-postgres  postgres:18                healthy  127.0.0.1:5432->5432/tcp
```

백엔드는 호스트 8080에 직접 공개되지 않습니다. 헬스체크는 front proxy를 경유합니다.

EC2에는 LLM 외 다른 서비스 컨테이너도 함께 실행될 수 있습니다. 이 섹션은 `llm-front`, `llm-back`, `yangyag-postgres`처럼 LLM 운영에 직접 필요한 컨테이너만 다룹니다.

```bash
curl -fsS http://127.0.0.1:8083/api/v1/health
```

## 운영 `.env` 핵심 확인값

secret이 아닌 값만 기재합니다.

```env
APP_CORS_ALLOWED_ORIGINS=http://43.202.113.123:8083,https://yangyag.duckdns.org
APP_DB_HOST=yangyag-postgres
APP_DB_PORT=5432
APP_DB_NAME=llm
APP_DB_SCHEMA=llm
APP_ATTACHMENTS_ROOT_PATH=/var/lib/llm/attachments
OPENAI_MODEL=gpt-5.5
ANTHROPIC_MODEL=claude-opus-4-7
XAI_MODEL=grok-4.3
LLM_FRONT_IMAGE=llm-front:1.0
LLM_BACK_IMAGE=llm-back:1.0
```

`localhost` origin은 운영 CORS에 없습니다.

secret 값은 확인하거나 문서에 기록하지 않습니다.

## 배포 절차

EC2에서 Docker 데몬과 외부 네트워크를 확인한 뒤, Compose를 직접 실행합니다.

프론트와 백엔드 모두 Docker Hub에 올리지 않습니다. Windows에서 이미지를 만들어 저장소의 `docker/` 폴더에 tar를 저장한 뒤 EC2에 넣습니다. EC2(snap Docker)에서는 `/tmp`의 tar를 `docker load`하지 못하므로 전송한 tar는 `/home/ubuntu/llm/`에 둡니다.

```powershell
# Windows 저장소 루트
.\aws\deploy-front.ps1
.\aws\deploy-back.ps1
```

수동으로 프론트와 백엔드를 올릴 때:

```powershell
cd front
npm ci
npm run typecheck
$env:NUXT_PUBLIC_API_BASE=""
npm run build
cd ..
docker build -t llm-front:1.0 .\front
docker save -o docker/llm-front-1.0.tar llm-front:1.0
scp -i aws\test-keypair.pem docker/llm-front-1.0.tar ubuntu@43.202.113.123:/home/ubuntu/llm/llm-front-1.0.tar
docker build -t llm-back:1.0 .\back
docker save -o docker/llm-back-1.0.tar llm-back:1.0
scp -i aws\test-keypair.pem docker/llm-back-1.0.tar ubuntu@43.202.113.123:/home/ubuntu/llm/llm-back-1.0.tar
```

Gradle은 Windows Docker 빌드 안에서 실행됩니다.

```bash
# EC2 (이미 load된 이미지로 기동)
cd /home/ubuntu/llm
docker load -i llm-front-1.0.tar
docker load -i llm-back-1.0.tar
docker info >/dev/null
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml ps
```

front/back 모두 `pull_policy: never`이고 로컬 load 이미지 `llm-front:1.0`, `llm-back:1.0`을 씁니다. front 런타임은 `mem_limit: 64m`입니다.

`auto_default`가 없으면 배포를 진행하지 말고 compose 프로젝트 `auto`와 네트워크 상태를 확인합니다. 이 네트워크는 auto 스택 소유이므로 EC2에서 빈 네트워크를 직접 만들면 DB 누락을 가릴 수 있습니다.

## 배포 후 검증

```bash
docker inspect --format '{{.Name}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
  llm-front llm-back yangyag-postgres

curl -fsS http://127.0.0.1:8083/api/v1/health

docker logs --tail 100 llm-back
docker logs --tail 100 llm-front
```

외부에서:

```bash
curl -fsS http://43.202.113.123:8083/api/v1/health
curl -fsS https://yangyag.duckdns.org/api/v1/health
```

도메인과 HTTPS 경로는 DNS/프록시 설정 상태에 따라 달라질 수 있습니다.

## rich 본문·inline 이미지 배포 순서 (2026-09-22)

V19 rich 본문·inline 이미지가 들어간 배포는 backend를 먼저, front를 나중에 올립니다. 동작 근거는 docs/04의 '호환성과 배포 순서' 절을 참조합니다.

1. 운영 Flyway history와 로컬 migration 파일 V1~V19를 대조합니다(아래 "Flyway 마이그레이션 주의"의 확인 쿼리 사용). 불일치가 있으면 배포하지 않습니다.
2. backend 이미지를 배포하고 V19 migration 적용과 기동을 확인합니다.
3. 기존 front 이미지로 rich 글이 추출 평문으로 표시되는 plain body fallback과 기존 API를 확인합니다.
4. front 이미지를 배포합니다.
5. 실제 paste/create/read/edit/delete smoke를 수행합니다.

운영 규칙:

- health는 어디서든 front proxy 8083 경유로 확인합니다(`curl -fsS http://127.0.0.1:8083/api/v1/health`).
- `docker compose down -v`, volume prune, Flyway history 직접 수정은 금지합니다.
- 순서는 backend 먼저, front 나중입니다. backend만 먼저 올라간 상태에서 구형 front가 rich 글을 평문으로 표시하는 것은 정상이며, 구형 front의 rich 글 수정 요청은 새 backend가 409 `RICH_TEXT_CLIENT_REQUIRED`로 거부합니다.

롤백 원칙:

- V19는 additive이므로 운영에서 컬럼을 즉시 제거하지 않습니다.
- 배포 전 이전 backend/front 이미지 식별자를 기록해 복구할 수 있게 합니다.
- 구형 front로 롤백하면 rich 글은 추출 평문으로 표시되고 inline 이미지는 일반 다운로드 카드로 보일 수 있습니다.
- rich 글 생성 후 이전 backend로 롤백하면 구형 backend가 `body`만 수정하고 알지 못하는 `body_format`·`body_document`는 그대로 남아 새 backend 복구 후 편집 내용이 어긋날 수 있습니다. 이전 backend 사용 중에는 게시글 쓰기를 중지하고 새 backend 복구를 우선합니다.

## Flyway 마이그레이션 주의 (V13 이력 충돌)

2026-08-08 배포에서 EC2 운영 DB에 **이전 버전의 `V13__create_ai_reply_jobs.sql`**(ai_reply_jobs/ai_reply_outbox 테이블 생성, 로컬 저장소에는 없는 파일)이 이미 적용되어 있어 새 이미지의 `V13__add_role_to_admins.sql`과 체크섬 충돌로 `llm-back`이 시작하지 못하는 문제가 있었습니다. 해당 기능은 현재 코드에 없으므로 `llm.flyway_schema_history`에서 version=13 행만 제거하고 새 이미지의 V13~V15(`add role to admins` → `add author to posts` → `backfill post author as admin`)를 적용해 해결했습니다. 이후 배포(V14~V16: 게시글/댓글 작성자 컬럼과 백필)도 history-파일 일치를 확인한 뒤 적용해야 합니다.

배포 전 확인:

```bash
docker exec -e PGPASSWORD="$DB_PASS" yangyag-postgres psql -U llm -d llm   -c "select version, description, checksum from llm.flyway_schema_history order by installed_rank;"
```

- 로컬 저장소의 마이그레이션 파일과 EC2 history의 description/checksum이 일치해야 합니다.
- history에 로컬에 없는 버전이 있으면 체크섬 충돌로 시작 실패합니다. 이력이 불일치하면 백업 후 불필요한 행을 제거하거나(위 사례) Flyway repair를 검토합니다. 자세한 대응은 docs/15의 "Flyway checksum mismatch" 항목을 참고합니다.

## 네트워크 조건

- `docker-compose.yml`은 외부 네트워크 `auto_default`를 요구합니다.
- `yangyag-postgres`가 `auto_default` 네트워크에 연결되어 있어야 합니다.
- front는 호스트 `8083`만 publish합니다.
- back은 호스트에 publish하지 않고 `8080`만 expose합니다. 다만 `default`와 외부 `auto_default` 네트워크에 모두 연결되므로 같은 Docker 네트워크의 컨테이너에서는 접근할 수 있습니다.

확인:

```bash
docker network ls | grep auto_default
docker network inspect auto_default
```

## Volume

EC2 확인된 mount:

```text
ubuntu_llm-back-attachments -> /var/lib/llm/attachments
ubuntu_llm-back-upload-sessions -> /var/lib/llm/upload-sessions
```

첨부파일은 EC2 컨테이너 env에 `APP_ATTACHMENTS_ROOT_PATH=/var/lib/llm/attachments`가 있어 위 volume을 사용합니다. `APP_UPLOAD_SESSIONS_ROOT_PATH`가 없으면 백엔드는 `${java.io.tmpdir}/llm-upload-sessions` fallback을 사용하므로, upload-session volume mount가 있어도 실제 임시 청크 저장 경로가 아닐 수 있습니다.

> 운영 `.env`에는 `APP_UPLOAD_SESSIONS_ROOT_PATH=/var/lib/llm/upload-sessions`가 설정되어 있어야 합니다. 적용 여부는 `docker exec llm-back printenv APP_UPLOAD_SESSIONS_ROOT_PATH`로 확인합니다.

운영 데이터가 들어 있는 volume은 임의 삭제하지 않습니다. 업로드 세션 장애 조사 시에는 먼저 컨테이너 env와 실제 저장 경로를 확인합니다.
