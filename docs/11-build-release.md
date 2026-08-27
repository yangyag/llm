# 빌드와 릴리스

백엔드 배포 산출물은 Docker Hub 이미지입니다. 프론트는 Hub에 올리지 않고, Windows에서 정적 파일을 만든 뒤 nginx 이미지를 tar로 EC2에 넣습니다.

## 이미지

| 서비스 | 기본 이미지 | 배포 |
| --- | --- | --- |
| Backend | `yangyag2/llm-back:latest` | Docker Hub push/pull |
| Frontend | `llm-front:1.0` | 로컬 `docker build` → `docker save` → EC2 `docker load` |

백엔드 Hub push는 기본적으로 `latest` 태그만 사용합니다. 프론트는 레지스트리 네임스페이스가 없습니다.

## 로컬 빌드

백엔드 jar 빌드:

```bash
cd back
./gradlew clean bootJar
```

프론트 번들 빌드:

```bash
cd front
npm ci
npm run typecheck
npm run build
```

Compose 이미지 빌드:

```bash
cd /home/yangyag/llm
cd front && npm ci && npm run build && cd ..
docker compose --profile build build back-build front-build
```

> `NUXT_PUBLIC_API_BASE`는 Windows에서 `nuxi generate` 할 때 정적 번들로 굳어집니다(런타임 변경 불가). 운영은 상대경로 `/api`를 쓰도록 비운 채 generate하고, API base URL을 바꾸려면 다시 generate한 뒤 front 이미지를 빌드·load해야 합니다. `front-build` Dockerfile은 `.output/public`만 nginx에 복사합니다.

## Dockerfile 요약

Backend:

- builder: `eclipse-temurin:25-jdk-jammy`
- runtime: `eclipse-temurin:25-jre-jammy`
- build command: `./gradlew clean bootJar --no-daemon`
- runtime entrypoint: `java -jar /app/app.jar`

Frontend:

- Windows 호스트: `npm ci`, `npm run typecheck`, `npm run build` (`nuxi generate`)
- 이미지: `nginx:1.27-alpine`에 `.output/public`과 `nginx.conf`만 복사. Node는 이미지에 없음
- 태그: `llm-front:1.0`
- compose `mem_limit: 64m` (런타임 nginx 한도). EC2에서 Node generate를 하지 않는 것과 함께 호스트 메모리를 줄이기 위함
- Nginx가 `/api/`를 `http://llm-back:8080`으로 proxy

## 릴리스 전 체크리스트

1. 변경 범위 확인

   ```bash
   git status --short
   git diff --stat
   ```

2. 백엔드 테스트

   ```bash
   cd back && ./gradlew clean test
   ```

3. 프론트 타입 검사와 빌드

   ```bash
   cd front && npm run typecheck && npm run build
   ```

4. 이미지 빌드

   ```bash
   cd /home/yangyag/llm
   cd front && npm ci && npm run build && cd ..
   docker compose --profile build build back-build front-build
   ```

5. 통합 실행 (외부 네트워크 `auto_default`가 없으면 health 단계에서 실패)

   ```bash
   docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
   docker compose --env-file .env up -d --wait
   curl -fsS http://localhost:8083/api/v1/health
   ```

   > EC2 운영에서는 docs/12의 명령처럼 `--project-name ubuntu`, `--env-file .env`, `-f docker-compose.yml`을 모두 명시합니다. **EC2에서는 `auto_default`를 직접 생성하지 마세요** — 이 네트워크는 compose 프로젝트 `auto` 소유이고, 빈 네트워크를 만들면 DB 누락을 가립니다. 배포 전에 `docker network inspect auto_default`로 존재를 확인합니다. 자세한 구분은 docs/15 참조.

## 백엔드 이미지 push

```bash
docker push yangyag2/llm-back:latest
```

## 프론트 이미지 배포 (Hub 없음)

Windows 저장소 루트:

```powershell
.\aws\deploy-front.ps1
```

 tar는 EC2 `/home/ubuntu/llm/`에 둡니다. snap Docker는 `/tmp`에서 `docker load`가 실패합니다.

백엔드 pull + 기동만 EC2에서 할 때:

```bash
cd /home/ubuntu/llm
docker network inspect auto_default >/dev/null
export LLM_ENV_FILE=/home/ubuntu/llm/.env
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml pull back
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml up -d --wait --wait-timeout 180 --remove-orphans
docker compose --project-name ubuntu --env-file .env -f docker-compose.yml ps
```

## 롤백 기준

백엔드는 Hub `latest`라 태그 롤백이 어렵습니다. 프론트는 이전 tar를 다시 `docker load`하면 됩니다. 릴리스 기록에 남길 것:

- 배포 시각
- Git commit SHA
- 백엔드 이미지 digest
- 프론트 이미지 id/`llm-front:1.0` tar 보관 여부
- 운영 `.env` 변경 여부
- 배포 전후 health 결과

이미지 확인:

```bash
docker image inspect yangyag2/llm-back:latest --format '{{index .RepoDigests 0}}'
docker image inspect llm-front:1.0 --format '{{.Id}} {{.Created}}'
```

롤백이 필요한 경우 이전 digest를 알고 있어야 안정적으로 되돌릴 수 있습니다.

## 릴리스 기록 템플릿

```markdown
## YYYY-MM-DD HH:mm KST

- Commit:
- Backend image:
- Frontend image:
- Env changes:
- DB migration:
- Verification:
  - back tests:
  - front build:
  - compose health:
  - EC2 health:
- Notes:
```
