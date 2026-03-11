# Backend (`back/`)

LLM 데모 백엔드 모듈입니다. 이 디렉터리는 `llm` monorepo의 일부입니다.

## 역할
- REST API 제공
- 데모용 LLM 응답 생성
- 백엔드 API 테스트(JUnit/MockMvc) 제공

## API
- `GET /api/v1/health`
- `POST /api/v1/llm/demo`

## 요구 환경
- Java 25
- Docker / Docker Compose

## 로컬 실행
```bash
cd back
./gradlew clean build
./gradlew bootRun
```

접속:
- Health: `http://localhost:8082/api/v1/health`

## 통합 Docker 실행 (권장)
루트에서 front/back를 함께 기동:
```bash
docker compose up -d --build
```

## 백엔드 단독 Docker 실행
```bash
docker build -t llm-backend:local .
docker run --rm --name llm-back -p 8082:8080 \
  -e APP_CORS_ALLOWED_ORIGINS="http://localhost:8083,http://localhost:5174" \
  llm-backend:local
```

## CORS
- 기본 허용 Origin:
  - `http://localhost:5174`
  - `http://localhost:8083`
- 설정 키: `app.cors.allowed-origins`
