# EC2 배포

EC2 접속과 운영 파일 확인에는 `/home/yangyag/aws` 폴더를 사용합니다.

## 접속 정보

로컬 접속 자료:

| 항목 | 값 |
| --- | --- |
| PEM key | `/home/yangyag/aws/test-keypair.pem` |
| SSH user | `ubuntu` |
| Host | `43.202.113.123` |
| 운영 디렉터리 | `/home/ubuntu/llm` |

접속:

```bash
chmod 600 /home/yangyag/aws/test-keypair.pem
ssh -i /home/yangyag/aws/test-keypair.pem ubuntu@43.202.113.123
```

처음 접속 시 host key 확인 질문이 나오면 fingerprint를 확인한 뒤 진행합니다.

## 현재 EC2 운영 파일

2026-05-31 KST 읽기 전용 점검 결과:

| 파일 | 상태 |
| --- | --- |
| `/home/ubuntu/llm/.env` | 존재 |
| `/home/ubuntu/llm/docker-compose.yml` | 존재 |

`/home/yangyag/aws`의 기존 메모에는 다른 프로젝트용 `/home/ubuntu/auto` 경로와 예전 LLM 경로인 `/home/ubuntu/llm.env`, `/home/ubuntu/docker-compose.ec2.yml`, `/home/yangyag/playground/test-keypair.pem`가 남아 있습니다. LLM 운영 작업의 우선 기준은 실제 EC2에서 확인한 `/home/ubuntu/llm` 경로와 `/home/yangyag/aws/test-keypair.pem`입니다.

## 현재 LLM 관련 컨테이너 상태

2026-08-08 KST 배포 후 확인:

```text
llm-front      yangyag2/llm-front:latest  healthy  0.0.0.0:8083->80/tcp
llm-back       yangyag2/llm-back:latest   healthy  8080/tcp
auto-postgres  postgres:18                healthy  127.0.0.1:5432->5432/tcp
```

이전 확인(2026-05-31 KST)도 동일한 3개 컨테이너였습니다.

백엔드는 호스트 8080에 직접 공개되지 않습니다. 헬스체크는 front proxy를 경유합니다.

EC2에는 LLM 외 다른 서비스 컨테이너도 함께 실행될 수 있습니다. 이 섹션은 `llm-front`, `llm-back`, `auto-postgres`처럼 LLM 운영에 직접 필요한 컨테이너만 다룹니다.

```bash
curl -fsS http://127.0.0.1:8083/api/v1/health
```

## 운영 `.env` 핵심 확인값

2026-05-31 KST 기준으로 secret이 아닌 값만 확인했습니다.

```env
APP_CORS_ALLOWED_ORIGINS=http://43.202.113.123:8083,http://localhost:8083,https://yangyag.duckdns.org
APP_DB_HOST=auto-postgres
APP_DB_PORT=5432
APP_DB_NAME=auto
APP_DB_SCHEMA=llm
APP_ATTACHMENTS_ROOT_PATH=/var/lib/llm/attachments
OPENAI_MODEL=gpt-5.5
ANTHROPIC_MODEL=claude-opus-4-7
XAI_MODEL=grok-4.3
```

secret 값은 확인하거나 문서에 기록하지 않습니다.

## 배포 절차

EC2에서 Docker 데몬과 외부 네트워크를 확인한 뒤, Compose를 직접 실행합니다.

```bash
cd /home/ubuntu/llm
docker info >/dev/null
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml pull
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml ps
```

`auto_default`가 없으면 배포를 진행하지 말고 auto-postgres 스택과 네트워크 상태를 확인합니다. 이 네트워크는 auto-postgres 스택 소유이므로 EC2에서 빈 네트워크를 직접 만들면 DB 누락을 가릴 수 있습니다.

## 배포 후 검증

```bash
docker inspect --format '{{.Name}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
  llm-front llm-back auto-postgres

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

## Flyway 마이그레이션 주의 (V13 이력 충돌)

2026-08-08 배포에서 EC2 운영 DB에 **이전 버전의 `V13__create_ai_reply_jobs.sql`**(ai_reply_jobs/ai_reply_outbox 테이블 생성, 로컬 저장소에는 없는 파일)이 이미 적용되어 있어 새 이미지의 `V13__add_role_to_admins.sql`과 체크섬 충돌로 `llm-back`이 시작하지 못하는 문제가 있었습니다. 해당 기능은 현재 코드에 없으므로 `llm.flyway_schema_history`에서 version=13 행만 제거하고 새 이미지의 V13~V15(`add role to admins` → `add author to posts` → `backfill post author as admin`)를 적용해 해결했습니다.

배포 전 확인:

```bash
docker exec -e PGPASSWORD="$DB_PASS" auto-postgres psql -U llm -d auto   -c "select version, description, checksum from llm.flyway_schema_history order by installed_rank;"
```

- 로컬 저장소의 마이그레이션 파일과 EC2 history의 description/checksum이 일치해야 합니다.
- history에 로컬에 없는 버전이 있으면 체크섬 충돌로 시작 실패합니다. 이력이 불일치하면 백업 후 불필요한 행을 제거하거나(위 사례) Flyway repair를 검토합니다. 자세한 대응은 docs/15의 "Flyway checksum mismatch" 항목을 참고합니다.

## 네트워크 조건

- `docker-compose.yml`은 외부 네트워크 `auto_default`를 요구합니다.
- `auto-postgres`가 `auto_default` 네트워크에 연결되어 있어야 합니다.
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

첨부파일은 EC2 컨테이너 env에 `APP_ATTACHMENTS_ROOT_PATH=/var/lib/llm/attachments`가 있어 위 volume을 사용합니다. 2026-05-31 KST 확인 시 EC2 `.env`와 `llm-back` 컨테이너 env에는 `APP_UPLOAD_SESSIONS_ROOT_PATH`가 없었습니다. 이 값이 없으면 백엔드는 `${java.io.tmpdir}/llm-upload-sessions` fallback을 사용하므로, upload-session volume mount가 있어도 실제 임시 청크 저장 경로가 아닐 수 있습니다.

> **2026-08-08 기준 적용 완료:** 운영 `.env`에 `APP_UPLOAD_SESSIONS_ROOT_PATH=/var/lib/llm/upload-sessions`가 추가되어 있고 `llm-back` 재기동도 완료된 상태입니다. 적용 여부는 `docker exec llm-back printenv APP_UPLOAD_SESSIONS_ROOT_PATH`로 확인합니다.

운영 데이터가 들어 있는 volume은 임의 삭제하지 않습니다. 업로드 세션 장애 조사 시에는 먼저 컨테이너 env와 실제 저장 경로를 확인합니다.
