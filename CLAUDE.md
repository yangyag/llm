# CLAUDE.md

## 프로젝트 개요
- LLM 데모 게시판 monorepo (`front/`, `back/`)
- Frontend: React + Vite (port 8083, Nginx)
- Backend: Spring Boot 3.5.11, Java 25 LTS (port 8082 -> container 8080)
- DB: PostgreSQL (Flyway 마이그레이션)
- 배포: Docker Compose

## 아키텍처
```
[Browser] -> Frontend (Nginx, :8083) -> /api/v1/* -> Backend (Spring Boot, :8082)
```

## 에이전트 기반 작업 규약

최대 10개의 에이전트를 구동할 수 있다. 가능한 아래의 역할에 따라 병렬로 작업을 수행한다.
Agent 들의 모델 지정은 Main Session 에서 요구사항의 규모에 맞게 알아서 지정한다.

### Main Session (메인 세션)
- **역할**:
  1. 사용자 요청 최종 해석
  2. Agent 할당: Agent 들의 추론 Level을 지정
  3. 충돌 해결
  4. 최종 결과 통합
  5. 최종 승인
- **비고**: 결코 직접 구현하지 않는다. 구현은 하위 에이전트들을 통해서만 수행한다. "통합자 + 최종 판단자"에 집중한다.

### Planner / Task Decomposer
- **역할**:
  1. 요구사항 분석
  2. 작업 분해
  3. 병렬 가능 여부 판단
  4. 완료 기준(acceptance criteria) 정의
  5. 구현 순서/의존성 정의

### Implementer
- **역할**:
  1. 주 구현 담당
  2. 요구사항에 따라 백엔드 / 프론트로 영역 분리
- **비고**: 요구사항 수준에 따라 Agent 의 개수가 정해진다.

### Review Gatekeeper
- **역할**:
  1. 구현물 리뷰
  2. 요구사항 충족 여부 점검
  3. 리스크 판정
  4. 수정 요청 우선순위화
- **비고**: pass / revise / block 명확히 판정한다.

### Test Verifier
- **역할**:
  1. 테스트 시나리오 설계
  2. 수동/자동 테스트 실행
  3. 실패 케이스 정리
  4. 회귀 위험 보고
- **비고**: 코드 수정 금지, 결과 보고 전담한다.

## 빌드 및 테스트 명령어
```bash
# 백엔드 테스트
cd back && ./gradlew clean test

# 프론트 빌드
cd front && npm run build

# 통합 실행
docker compose up -d --build

# 환경 변수
cp llm.env.example llm.env
```

## 품질 게이트
- 백엔드 변경 시 `./gradlew clean test` 필수 통과
- 프론트 변경 시 `npm run build` 필수 통과
- 통합 영향 시 `docker compose up -d --build` + 헬스 체크

## 디렉터리 규칙
- `front/`: UI, 라우팅, 상태관리, 프론트 Docker 빌드
- `back/`: API, 도메인, 테스트, 백엔드 Docker 빌드
- 루트: 통합 실행/문서 (`docker-compose.yml`, `llm.env.example`, `README.md`)

## 코드 컨벤션
- git commit 메시지는 한글로 작성한다.
- 백엔드 테스트는 JUnit + MockMvc 중심으로 작성한다.
- `front/`와 `back/`는 파일 충돌이 없으면 병렬 작업한다.
- 공용 계약(API 경로/응답)은 먼저 고정한 뒤 구현한다.
