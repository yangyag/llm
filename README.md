# llm monorepo

익명 게시글과 답변을 작성할 수 있는 게시판 저장소입니다.

## 디렉터리 구조
```text
llm/
  front/                  # React 게시판 UI
  back/                   # Spring Boot 게시판 API
  docker-compose.yml      # 통합 실행
  .env.example
  README.md
  AGENTS.md
```

## 통합 실행
```bash
cp .env.example .env
docker compose up -d --build
```

접속:
- Frontend: `http://localhost:8083`
- Backend health: `http://localhost:8082/api/v1/health`

중지:
```bash
docker compose down
```

## EC2 이미지 배포
소스 빌드 없이 Docker Hub 이미지로 바로 기동:

```bash
cat >/home/ubuntu/llm.env <<'EOF'
APP_CORS_ALLOWED_ORIGINS=http://43.202.113.123:8083
APP_DB_HOST=llm-db
APP_DB_PORT=5432
APP_DB_NAME=yangyag
APP_DB_USER=yangyag
APP_DB_PASSWORD=yangyag1!
POSTGRES_DB=yangyag
POSTGRES_USER=yangyag
POSTGRES_PASSWORD=yangyag1!
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
XAI_API_KEY=
EOF

vi /home/ubuntu/llm.env

docker pull yangyag2/llm-front:latest
docker pull yangyag2/llm-back:latest
docker pull yangyag2/llm-db:18

docker network create llm-net
docker volume create llm-pgdata
docker run -d --name llm-db --network llm-net --env-file /home/ubuntu/llm.env -v llm-pgdata:/var/lib/postgresql yangyag2/llm-db:18
docker run -d --name llm-back --network llm-net --env-file /home/ubuntu/llm.env yangyag2/llm-back:latest
docker run -d --name llm-front --network llm-net -p 8083:80 yangyag2/llm-front:latest
```

기존 컨테이너를 업데이트할 때:

```bash
docker rm -f llm-front llm-back llm-db || true
docker pull yangyag2/llm-front:latest
docker pull yangyag2/llm-back:latest
docker pull yangyag2/llm-db:18
docker network create llm-net || true
docker volume create llm-pgdata
docker run -d --name llm-db --network llm-net --env-file /home/ubuntu/llm.env -v llm-pgdata:/var/lib/postgresql yangyag2/llm-db:18
docker run -d --name llm-back --network llm-net --env-file /home/ubuntu/llm.env yangyag2/llm-back:latest
docker run -d --name llm-front --network llm-net -p 8083:80 yangyag2/llm-front:latest
```

접속:
- Frontend: `http://EC2_PUBLIC_IP:8083`

운영 메모:
- 외부 보안그룹은 보통 `22`, `8083`만 열면 됩니다.
- `8080`, `8082`, `5432`는 외부에 열지 않습니다.
- EC2에서는 `/home/ubuntu/llm.env` 파일 하나만 관리하면 됩니다.
- EC2 배포용 compose와 `docker run` 모두 front/back/db를 같은 Docker 네트워크에 올리고, 백엔드는 내부 `llm-db:5432`로만 접속합니다.

## 현재 범위
- 익명 게시글 작성, 목록 조회, 상세 조회
- 답변 작성/수정/삭제
- AI 답변 생성(GPT / Claude / Grok)
- 게시글 수정/삭제
- 게시글/답변 본문은 프론트에서 Base64 인코딩 후 전송
- 서버는 디코딩 후 PostgreSQL 18에 저장

## 전송 방식 주의
- Base64는 보안 기능이 아닙니다.
- 현재 구조는 본문을 전송 직전에 문자열로 포장하는 수준이며, 서버에는 디코딩된 평문이 저장됩니다.
- 실제 기밀성이 필요해지면 Base64가 아니라 별도의 암호화 설계를 추가해야 합니다.

## DB 연결
- 로컬 통합 실행 기준
- 포트: `5432`
- DB: `yangyag`
- User: `yangyag`
- Password: `yangyag1!`
- `llm-back` 컨테이너는 `host.docker.internal:5432` 경유로 접속

## 개별 개발
### Frontend
```bash
cd front
npm ci
npm run dev
```

기본 접속:
- `http://localhost:5174`

### Backend
```bash
cd back
APP_DB_HOST=localhost ./gradlew bootRun
```

## 검증 명령
```bash
cd back && ./gradlew clean test
cd front && npm run build
```

## AI 설정
- 로컬 Docker 실행은 루트 `ai-keys.env`를 사용합니다.
- EC2 배포는 루트 `llm.env.example`를 복사한 `llm.env` 파일 하나로 DB 설정과 AI 키를 함께 관리합니다.
