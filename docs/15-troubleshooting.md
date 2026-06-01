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

- EC2에서는 `auto-postgres`가 붙은 기존 `auto_default` 네트워크가 있어야 합니다.
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

`front/vite.config.js`는 `/api` proxy를 `http://localhost:8082`로 보냅니다.

대응:

```bash
cd back
APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun
```

또는 Vite proxy target을 백엔드 실제 포트로 수정합니다.

## DB 연결 실패

증상:

- `Connection refused`
- `UnknownHostException: auto-postgres`
- Flyway migration 실패

확인:

```bash
docker ps | grep postgres
docker network inspect auto_default
grep -E '^(APP_DB_HOST|APP_DB_PORT|APP_DB_NAME|APP_DB_SCHEMA)=' .env
```

대응:

- 운영: `APP_DB_HOST=auto-postgres`, `APP_DB_NAME=auto`, `APP_DB_SCHEMA=llm`
- 로컬: 실제 PostgreSQL 위치와 DB/schema가 있는지 확인
- schema는 Flyway `create-schemas=true`로 생성 가능하지만 DB 자체와 권한은 먼저 있어야 합니다.

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
- 유휴 시간(1시간)은 프론트 상수이며 환경 변수로 조정하지 않습니다. 변경이 필요하면 `front/src/App.jsx`의 `IDLE_TIMEOUT_MS`를 수정 후 front 이미지를 재빌드합니다.

## 게시글 body가 깨짐

일반 게시글/댓글 쓰기 API는 `bodyBase64`를 기대합니다. UTF-8 문자열을 Base64로 인코딩해야 합니다.

프론트는 `js-base64`의 `fromUint8Array(new TextEncoder().encode(value))` 방식을 사용합니다.

## 첨부파일 업로드 실패

가능 원인:

- `APP_ATTACHMENTS_MAX_FILE_SIZE` 초과
- `APP_ATTACHMENTS_MAX_REQUEST_SIZE` 초과
- attachment volume 쓰기 권한 또는 용량 문제
- `removeAttachment=true`와 새 attachment 동시 전송

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

- 새 front 이미지가 push되지 않음
- EC2에서 pull하지 않음
- 브라우저 캐시
- `VITE_API_BASE_URL`이 빌드 시점에 잘못 들어감

대응:

```bash
cd /home/ubuntu/llm
./deploy-ec2.sh
docker image ls | grep 'llm-front'
docker logs --tail 100 llm-front
```

브라우저 hard refresh도 확인합니다.

## EC2 SSH 접속 실패

확인:

```bash
chmod 600 /home/yangyag/aws/test-keypair.pem
ssh -o ConnectTimeout=8 -i /home/yangyag/aws/test-keypair.pem ubuntu@43.202.113.123
```

가능 원인:

- PEM 권한 오류
- 접속 사용자 오류
- 보안그룹에서 SSH inbound 미허용
- 접속 위치 공인 IP 변경
- EC2 중지 또는 IP 변경

## 문서와 운영 상태가 다를 때

운영 기준은 항상 EC2 실제 파일과 컨테이너 상태입니다.

```bash
ssh -i /home/yangyag/aws/test-keypair.pem ubuntu@43.202.113.123
cd /home/ubuntu/llm
ls -l
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
curl -fsS http://127.0.0.1:8083/api/v1/health
```

문서가 틀렸다면 운영 상태를 근거로 문서를 갱신합니다.
