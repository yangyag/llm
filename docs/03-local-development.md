# 로컬 개발

로컬 개발은 프론트와 백엔드를 각각 실행하거나 Docker Compose로 전체 스택을 실행하는 방식이 있습니다.

## 전체 스택 실행

`docker-compose.yml`은 외부 Docker 네트워크 `auto_default`를 요구합니다. 로컬에 아직 없다면 먼저 생성합니다.

```bash
docker network inspect auto_default >/dev/null 2>&1 || docker network create auto_default
```

```bash
cd /home/yangyag/llm
docker compose up -d --wait
docker compose ps
curl -fsS http://localhost:8083/api/v1/health
```

기본 포트:

- Frontend/Nginx: `localhost:8083`
- Backend: compose 내부 `llm-back:8080`
- Backend health: `localhost:8083/api/v1/health`

백엔드 컨테이너는 `expose: 8080`만 설정되어 있어 호스트에 직접 publish되지 않습니다. 호스트에서는 front proxy 경유로 확인합니다.

## 로컬 소스 이미지 빌드

```bash
cd /home/yangyag/llm
cd front && npm ci && npm run build && cd ..
docker compose --profile build build back-build front-build
docker compose up -d --wait
```

`front-build`는 이미 만들어 둔 `front/.output/public`을 nginx 이미지에 넣을 뿐입니다. `nuxi generate`를 건너뛰면 이미지 빌드가 실패합니다. 런타임 컨테이너는 `back`, `front`가 실행합니다.

빌드 프로파일은 compose가 해석한 `LLM_FRONT_IMAGE`/`LLM_BACK_IMAGE` 값으로 이미지에 태그를 답니다. `.env`에서 이 값을 `yangyag2/*` 같은 다른 태그로 바꾸면 이후 `docker compose up`이 그 태그를 찾고, 문서·배포 스크립트 기준(`llm-*:1.0`)으로 빌드한 이미지는 실행 컨테이너에 반영되지 않습니다. 두 변수는 기본값(`llm-front:1.0`, `llm-back:1.0`)을 유지하고, 해석 결과는 `docker compose config`로 확인합니다(2026-09-04 로컬 확인).

## 백엔드 단독 실행

백엔드 기본 포트는 `8080`입니다. 로컬 프론트의 Nitro dev proxy는 `http://localhost:8082`로 잡혀 있으므로, 백엔드를 단독 개발 서버로 붙일 때는 포트를 맞춰야 합니다.

예시:

```bash
cd back
APP_DB_HOST=localhost SERVER_PORT=8082 ./gradlew bootRun
```

헬스체크:

```bash
curl -fsS http://localhost:8082/api/v1/health
```

`SERVER_PORT`는 Spring Boot 표준 환경 변수입니다.

## 프론트 단독 실행

```bash
cd front
npm ci
npm run dev
```

기본 URL:

- Nuxt dev server: `http://localhost:5174`
- `/api` proxy: `http://localhost:8082`

백엔드를 `8080`으로 실행한다면 `front/nuxt.config.ts`의 `nitro.devProxy` 또는 백엔드 실행 포트를 조정해야 합니다.

## 로컬 인증 흐름

마이그레이션 `V6__create_admins_table.sql`은 기본 관리자 계정(시드)을 생성합니다. 로컬 확인용으로만 사용하고, 운영에서는 반드시 변경해야 합니다.

로그인 성공 시 프론트는 `auth_token`, `auth_username`, `auth_role`(`ADMIN`/`USER`)을 `localStorage`에 저장합니다. 인증 오류가 나면 세 값을 삭제하고 로그인 화면으로 돌아갑니다. `auth_role`은 `/users` 화면 라우트 가드와 목록/상세의 수정·삭제 버튼 노출 판단에 사용됩니다.

## 첨부파일과 임시 업로드 파일

Docker Compose는 아래 volume을 컨테이너에 mount합니다.

- 첨부파일: `llm-back-attachments` 계열 volume
- 업로드 세션 임시 파일: `llm-back-upload-sessions` 계열 volume

애플리케이션이 실제로 volume을 쓰려면 `.env`에서 저장 경로가 mount 대상과 맞아야 합니다.

```env
APP_ATTACHMENTS_ROOT_PATH=/var/lib/llm/attachments
APP_UPLOAD_SESSIONS_ROOT_PATH=/var/lib/llm/upload-sessions
```

해당 환경 변수가 없으면 백엔드 fallback은 JVM temp 아래입니다. 이 경우 Docker volume은 mount되어 있어도 실제 저장 경로로 쓰이지 않을 수 있습니다.

- 첨부파일 기본값: `${java.io.tmpdir}/llm-attachments`
- 업로드 세션 기본값: `${java.io.tmpdir}/llm-upload-sessions`

## 이미지 내장 DB로 로컬 기동 시 주의

`yangyag2/postgres`처럼 PGDATA가 bake된 이미지는 컨테이너 시작 시 초기화가 건너뛰어지므로(`Skipping initialization`), 이미지 안 비밀번호·권한·Flyway 버전이 현재 코드와 다를 수 있습니다(2026-09-04 로컬에서 V12 상태 DB + V16 코드 조합 확인). `.env`의 `APP_DB_HOST/NAME/SCHEMA/USER/PASSWORD`를 맞춘 뒤에도 백엔드가 뜨지 않으면 백엔드 로그의 Flyway 에러(`password authentication failed` → `permission denied for schema` → `must be owner of table` 순서로 나타날 수 있음)를 보고 docs/15의 baked DB 항목대로 권한/owner를 정리한 뒤 백엔드를 재시작합니다.

## 자주 쓰는 명령

```bash
docker compose logs -f back
docker compose logs -f front
docker compose restart back
docker compose down
docker compose down -v
```

`docker compose down -v`는 volume까지 삭제하므로 로컬 데이터가 사라집니다. 운영에서는 사용하지 않습니다.
