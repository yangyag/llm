# 운영 Runbook

이 문서는 EC2 운영 중 반복적으로 수행하는 점검, 배포, 장애 대응 절차입니다.

## 접속

```bash
chmod 600 /home/yangyag/aws/test-keypair.pem
ssh -i /home/yangyag/aws/test-keypair.pem ubuntu@43.202.113.123
cd /home/ubuntu/llm
```

## 일일 점검

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
docker inspect --format '{{.Name}} {{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
  llm-front llm-back auto-postgres
curl -fsS http://127.0.0.1:8083/api/v1/health
docker logs --tail 100 llm-back
docker logs --tail 100 llm-front
```

정상 기준:

- `llm-front`, `llm-back`, `auto-postgres`가 `healthy`
- health API가 `status=UP`
- 백엔드 로그에 반복되는 `INTERNAL_ERROR`, DB connection error, AI provider error가 없음

## 배포

```bash
cd /home/ubuntu/llm
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml pull
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
curl -fsS http://127.0.0.1:8083/api/v1/health
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml ps
```

`auto_default`가 없으면 빈 네트워크를 생성하지 말고 auto-postgres 스택을 확인합니다.

배포 기록에 남길 것:

- 배포 시각
- Git commit SHA
- 이미지 digest
- `.env` 변경 여부
- DB migration 변경 여부
- health 결과

## 백엔드만 재기동

```bash
cd /home/ubuntu/llm
LLM_ENV_FILE=/home/ubuntu/llm/.env \
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --no-deps --wait --wait-timeout 180 back
```

## 프론트만 재기동

```bash
cd /home/ubuntu/llm
LLM_ENV_FILE=/home/ubuntu/llm/.env \
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --no-deps --wait --wait-timeout 180 front
```

## 로그 확인

```bash
docker logs --tail 200 llm-back
docker logs --tail 200 llm-front
docker logs -f llm-back
```

자주 보는 오류 코드:

- `INVALID_CREDENTIALS`: 로그인 실패, 토큰 누락/만료
- `FORBIDDEN`: 권한 없음. USER가 남의 게시글 수정/삭제, 레거시 글 수정/삭제, 사용자 관리 API 호출
- `AI_PROVIDER_NOT_CONFIGURED`: API key 누락
- `AI_REPLY_GENERATION_FAILED`: 외부 AI 호출 실패
- `ATTACHMENT_STORAGE_ERROR`: volume 또는 파일 권한 문제
- `UPLOAD_SESSION_STATE_ERROR`: 세션 만료/완료/finalizing

## DB 점검

```bash
docker exec -it auto-postgres psql -U <db-user> -d auto
```

```sql
select count(*) from llm.posts;
select count(*) from llm.post_replies;
select count(*) from llm.post_attachments;
select status, count(*) from llm.upload_sessions group by status;
```

DB 계정과 비밀번호는 `.env`에서 확인하되 화면 공유나 로그에 노출하지 않습니다.

## 디스크 점검

```bash
df -h
docker system df
docker volume ls | grep llm
```

첨부파일 volume 용량이 커질 수 있습니다. 운영 중 무작정 `docker system prune --volumes`를 실행하지 않습니다.

## 백업

DB dump:

```bash
docker exec auto-postgres pg_dump -U <db-user> -d auto -n llm > llm-$(date +%Y%m%d-%H%M%S).sql
```

첨부파일 volume backup 예시:

```bash
docker run --rm \
  -v ubuntu_llm-back-attachments:/data:ro \
  -v "$PWD":/backup \
  alpine tar czf /backup/llm-attachments-$(date +%Y%m%d-%H%M%S).tgz -C /data .
```

백업 파일은 권한과 보관 위치를 제한합니다.

## 복구 개요

1. 새 DB 또는 기존 DB에 dump를 복원합니다.
2. 첨부파일 volume을 복원합니다.
3. `/home/ubuntu/llm/.env`의 DB 접속 정보와 schema를 확인합니다.
4. 운영 project name과 env file을 명시해 서비스를 시작합니다.
5. health, 게시글 목록, 첨부파일 다운로드를 확인합니다.

```bash
cd /home/ubuntu/llm
LLM_ENV_FILE=/home/ubuntu/llm/.env \
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180
```

## 장애 대응 우선순위

### 서비스가 내려감

```bash
docker ps -a | grep llm
docker logs --tail 200 llm-back
docker logs --tail 200 llm-front
cd /home/ubuntu/llm
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml pull
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
```

### Health 실패

1. `docker inspect` health 상태 확인
2. `docker logs llm-back` 확인
3. DB 연결 오류인지 확인
4. `auto-postgres` health 확인
5. 최근 `.env` 변경을 되돌릴지 판단

### AI 답변 실패

1. provider API key가 비어 있지 않은지 확인
2. model 값이 운영 의도와 맞는지 확인
3. 외부 API status code를 로그에서 확인
4. 특정 provider만 실패하는지 전체 실패인지 분리

### 업로드 세션 실패

1. secret 불일치 여부 확인
2. 청크 크기와 최종 파일 크기 제한 확인
3. 업로드 세션 volume 용량 확인
4. 세션 만료 여부 확인
5. 실패한 sidecar 대신 새 세션으로 재시도

## 운영 금지 사항

- 운영 volume을 확인 없이 삭제하지 않습니다.
- 운영 `.env`를 채팅, 문서, 로그에 붙여넣지 않습니다.
- `docker compose down -v`를 운영에서 실행하지 않습니다.
- 기본 관리자 계정 `admin`/`admin`을 공용 운영에 그대로 두지 않습니다.
