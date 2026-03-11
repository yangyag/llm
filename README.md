# llm monorepo

Docker로 바로 띄울 수 있는 최소 LLM 샘플 저장소입니다.

## 디렉터리 구조
```text
llm/
  front/                  # 단일 인사 화면
  back/                   # Spring Boot health API
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

## 현재 범위
- 프론트엔드: `http://localhost:8083/` 에서 `안녕하세요 LLM 입니다.` 표시
- 백엔드: `GET /api/v1/health`
- front/back compose 구조는 유지

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
./gradlew bootRun
```

## 검증 명령
```bash
cd back && ./gradlew clean test
cd front && npm run build
```
