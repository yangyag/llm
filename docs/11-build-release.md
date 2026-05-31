# 빌드와 릴리스

이 프로젝트의 배포 산출물은 Docker 이미지입니다. 기본 Docker Hub namespace는 `yangyag2`이고 기본 태그는 `latest`입니다.

## 이미지

| 서비스 | 기본 이미지 |
| --- | --- |
| Backend | `yangyag2/llm-back:latest` |
| Frontend | `yangyag2/llm-front:latest` |

프로젝트 규칙상 Docker Hub push는 기본적으로 `latest` 태그만 사용합니다. 타임스탬프 등 추가 태그는 사용자가 명시적으로 요청한 경우에만 사용합니다.

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
npm run build
```

Compose 이미지 빌드:

```bash
cd /home/yangyag/llm
docker compose --profile build build back-build front-build
```

## Dockerfile 요약

Backend:

- builder: `eclipse-temurin:25-jdk-jammy`
- runtime: `eclipse-temurin:25-jre-jammy`
- build command: `./gradlew clean bootJar --no-daemon`
- runtime entrypoint: `java -jar /app/app.jar`

Frontend:

- builder: `node:22-bookworm-slim`
- runtime: `nginx:1.27-alpine`
- build command: `npm ci`, `npm run build`
- `VITE_API_BASE_URL` build arg 지원
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

3. 프론트 빌드

   ```bash
   cd front && npm run build
   ```

4. 이미지 빌드

   ```bash
   cd /home/yangyag/llm
   docker compose --profile build build back-build front-build
   ```

5. 통합 실행

   ```bash
   docker compose up -d --wait
   curl -fsS http://localhost:8083/api/v1/health
   ```

## 이미지 push

```bash
docker push yangyag2/llm-back:latest
docker push yangyag2/llm-front:latest
```

push 후 EC2에서 pull/up을 수행합니다.

```bash
cd /home/ubuntu/llm
./deploy-ec2.sh
```

## 롤백 기준

현재 정책은 `latest`만 사용하므로 이미지 태그 기반 롤백이 어렵습니다. 장애 대응을 위해 최소한 다음 정보를 릴리스 기록에 남깁니다.

- 배포 시각
- Git commit SHA
- push한 이미지 digest
- 운영 `.env` 변경 여부
- 배포 전후 health 결과

이미지 digest 확인:

```bash
docker image inspect yangyag2/llm-back:latest --format '{{index .RepoDigests 0}}'
docker image inspect yangyag2/llm-front:latest --format '{{index .RepoDigests 0}}'
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
