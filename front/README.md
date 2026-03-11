# Frontend (`front/`)

단일 인사 화면을 제공하는 프론트엔드 모듈입니다. 이 디렉터리는 `llm` monorepo의 일부입니다.

## 기술 스택
- React 18
- Vite 5
- Nginx (정적 서빙)

## 로컬 개발
```bash
cd front
npm ci
npm run dev
```

기본 접속:
- `http://localhost:5174`

## 프로덕션 빌드
```bash
cd front
npm run build
```

## Docker 실행 (프론트 단독)
```bash
cd front
docker build -t llm-frontend:local .
docker run --rm -p 8083:80 llm-frontend:local
```

접속:
- `http://localhost:8083`

## 통합 실행 (권장)
루트에서 프론트/백엔드를 함께 실행합니다.
```bash
cd ..
docker compose up -d --build
```

## 화면 동작
- `/` 에서 `안녕하세요 LLM 입니다.` 한 문장만 표시
