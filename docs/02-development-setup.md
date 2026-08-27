# 개발 환경 설정

이 문서는 새 개발자가 저장소를 받은 뒤 로컬 개발과 검증을 시작하는 절차입니다.

## 필수 도구

| 도구 | 용도 |
| --- | --- |
| Git | 저장소 관리 |
| Docker, Docker Compose v2 | 전체 스택 실행과 이미지 빌드 |
| Java 25 | 백엔드 로컬 실행과 Gradle 테스트 |
| Node.js 22 계열 권장 | 프론트 설치와 빌드 |
| npm | 프론트 패키지 관리 |
| curl | 헬스체크와 API 확인 |

백엔드는 Gradle wrapper를 포함하므로 별도 Gradle 설치는 필수는 아닙니다.

## 저장소 준비

```bash
cd /home/yangyag/llm
git status --short --branch
```

다른 위치에 처음 받는 경우:

```bash
git clone REPOSITORY_URL llm
cd llm
```

## 환경 파일 준비

```bash
cp .env.example .env
```

`.env`는 Git 추적 대상이 아닙니다. 운영 secret, API key, DB password를 실제 값으로 채우더라도 커밋하지 않습니다.

로컬에서 Docker Compose를 그대로 쓸 때 기본 기대값:

```env
LLM_FRONT_PORT=8083
APP_DB_HOST=host.docker.internal
APP_DB_PORT=5432
APP_DB_NAME=yangyag
APP_DB_USER=yangyag
APP_DB_SCHEMA=public
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://localhost:8083
```

로컬 DB를 Docker로 별도 실행하지 않는 구성이라면, `APP_DB_HOST`가 실제 PostgreSQL 접속 위치를 가리켜야 합니다.

## 의존성 설치

프론트:

```bash
cd front
npm ci
```

백엔드:

```bash
cd back
./gradlew --version
```

## 빠른 통합 실행

루트 `docker-compose.yml`은 PostgreSQL 컨테이너를 포함하지 않고 외부 네트워크 `auto_default`를 요구합니다. 운영에서는 기존 `yangyag-postgres`가 이 네트워크에 붙어 있습니다. 새 로컬 환경에서는 먼저 네트워크와 접근 가능한 PostgreSQL을 준비하고, `.env`의 `APP_DB_HOST`, `APP_DB_NAME`, `APP_DB_USER`, `APP_DB_PASSWORD`, `APP_DB_SCHEMA`가 그 DB를 가리키게 해야 합니다.

```bash
docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
```

로컬에서 `yangyag-postgres` 같은 별도 DB 컨테이너를 쓴다면 그 컨테이너를 `auto_default`에 연결합니다. DB 자체와 user/database/schema 권한이 준비되지 않으면 `docker compose up -d --wait`는 백엔드 health 확인 단계에서 실패합니다.

```bash
cd /home/yangyag/llm
docker compose up -d --wait
```

접속:

- Frontend: `http://localhost:8083`
- Backend health through frontend proxy: `http://localhost:8083/api/v1/health`

중지:

```bash
docker compose down
```

## 개발 전 점검

```bash
cd /home/yangyag/llm
git status --short
cd back && ./gradlew clean test
cd ../front && npm run typecheck && npm run build
```

## Git 관리 기준

- `.env`, `.env.*`, `llm.env`, `llm.env.*`는 커밋하지 않습니다.
- `front/node_modules/`, `front/.nuxt/`, `front/.output/`, `back/build/`, `.gradle/`은 커밋하지 않습니다.
- 프로젝트 규칙상 Git commit 메시지는 한글로 작성합니다.
- 작업 전후 `git status --short`로 변경 범위를 확인합니다.
