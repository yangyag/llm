# Frontend (`front/`)

익명 게시판 프론트엔드 모듈입니다. 이 디렉터리는 `llm` monorepo의 일부입니다.

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
- 게시글 목록, 글 작성, 글 상세를 한 앱 셸에서 처리
- 게시글 본문과 답변 본문은 서버 전송 전에 Base64 인코딩
- 제목은 평문으로 전송
- 수정/삭제는 비밀번호 입력 방식
- Base64는 보안 기능이 아니며, 전송 직전 문자열 포장에 가깝습니다.

## 연동 API
- `GET /api/v1/posts`
- `POST /api/v1/posts`
- `GET /api/v1/posts/{id}`
- `PUT /api/v1/posts/{id}`
- `DELETE /api/v1/posts/{id}`
- `POST /api/v1/posts/{id}/replies`
- `PUT /api/v1/posts/replies/{id}`
- `DELETE /api/v1/posts/replies/{id}`
