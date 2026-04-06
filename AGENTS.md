# AGENTS.md

## 목적
- monorepo(`llm`) 운영 기준 문서다.
- `front/`, `back/` 하위 프로젝트를 한 저장소에서 일관되게 관리한다.

## 저장소 컨텍스트
- Frontend: React + Vite
- Backend: Spring Boot 3.5.11, Java 25 LTS
- 배포: Docker 이미지 2개(front/back) + 루트 `docker-compose.yml`
- 테스트 원칙: 백엔드 API JUnit(`MockMvc`) 중심

## 시스템 아키텍처
```text
[Browser]
  -> Frontend (front, Nginx, :8083)
      -> /api/v1/*
         -> Backend (back, Spring Boot, :8082 -> container 8080)
```

## 에이전트 운영 원칙
- 작업은 `Planner -> Generator -> Evaluator` 3단계 파이프라인으로 운영한다.
- 최대 6개의 에이전트를 구동할 수 있다.
- 에이전트 모델과 추론 수준은 Planner가 작업 규모와 위험도에 맞게 정한다.
- 공용 계약(API 경로/응답/자산 규칙)은 구현 전에 Planner가 먼저 확정한다.

### 파이프라인 구조
```text
[사용자]
  -> Planner
      -> 요구사항 해석 / 작업 분해 / 공용 계약 확정 / 완료 기준 정의
  -> Generator
      -> 구현 / 테스트 / 문서 갱신
  -> Evaluator
      -> 코드 리뷰 / QA / pass|revise|block 판정
         pass -> Planner 최종 승인 -> 사용자 보고
         revise/block -> Generator 재작업
```

### Planner — 메인 세션
- 역할:
  1) 사용자 요청 최종 해석
  2) 요구사항 분석 및 기능 명세 작성
  3) 작업을 독립 실행 가능한 단위로 분해
  4) 병렬 가능 여부와 구현 순서, 의존성 정의
  5) API 계약 등 공용 인터페이스 사전 확정
  6) 완료 기준(acceptance criteria) 정의
  7) Evaluator 판정 수용 및 최종 승인
- 비고:
  - 직접 구현하지 않는다. 구현은 반드시 Generator를 통해 수행한다.
  - Generator 작업 단위에 명세, 대상 범위, 완료 기준을 명확히 전달한다.
  - Evaluator가 `revise` 또는 `block`을 판정하면 재작업 범위를 확정해 Generator에 다시 지시한다.

### Generator — 구현 단계
- 역할:
  1) Planner 산출물을 바탕으로 구현 수행
  2) `front/`, `back/`, DB 마이그레이션, 테스트, 문서 변경을 작업 단위별로 처리
  3) 빌드 및 테스트 검증 수행
  4) 결과를 취합해 Evaluator에 전달
- 병렬 작업 규칙:
  - 전체 상한(최대 6개 에이전트) 안에서 독립 작업 단위를 병렬로 수행한다.
  - `front/`와 `back/`는 파일 충돌이 없으면 병렬 작업한다.
  - 의존 관계가 있는 작업은 공용 계약 확정 이후 순차 실행한다.
  - 루트 공용 파일(`docker-compose.yml`, `AGENTS.md`)은 통합 리뷰 후 반영한다.
- 비고:
  - 구현 중 공용 계약 변경이 필요하면 즉시 Planner에 보고하고 재확정 후 진행한다.
  - 코드, 테스트, 문서는 같은 변경 단위로 함께 갱신한다.

### Evaluator — 리뷰 + QA 통합
- 역할:
  1) Generator 산출물 전체를 대상으로 코드 리뷰 수행
  2) Planner가 정의한 완료 기준 충족 여부 점검
  3) 테스트 시나리오 설계 및 수동/자동 검증 결과 정리
  4) 회귀 위험과 엣지 케이스 점검
  5) `pass / revise / block` 중 하나로 최종 판정
- 판정 기준:
  - `pass`: 완료 기준을 모두 충족했고 승인 가능한 상태다.
  - `revise`: 일부 미충족 또는 국소 수정이 필요한 상태다.
  - `block`: 요구사항 미달, 중대 결함, 계약 위반 등으로 재작업이 필요한 상태다.
- 비고:
  - 코드를 직접 수정하지 않는다. 판정과 근거 보고만 담당한다.
  - `revise`와 `block`은 반드시 원인, 영향 범위, 재작업 요구사항을 구체적으로 남긴다.

## 디렉터리 규칙
- `front/`: UI, 라우팅, 상태관리, 프론트 Docker 빌드
- `back/`: API, 도메인, 테스트, 정적 자산, 백엔드 Docker 빌드
- 루트: 통합 실행/문서(`docker-compose.yml`, `llm.env.example`, `README.md`)

## Git 규칙
- `git commit` 메시지는 한글로 작성한다.

## 배포 규칙
- Docker Hub 이미지 push는 기본적으로 `latest` 태그만 사용한다.
- 타임스탬프 등 추가 태그는 사용하지 않으며, 예외는 사용자가 명시적으로 요청한 경우만 허용한다.

## 품질 게이트
- 백엔드 기능 변경 시 `back` 테스트를 반드시 통과시킨다.
- 프론트 변경 시 `front` 빌드를 반드시 통과시킨다.
- 통합 영향이 있으면 루트 기준 컨테이너 실행과 헬스 체크를 확인한다.
- 최소 검증:
  - `cd back && ./gradlew clean test`
  - `cd front && npm run build`
  - 루트 `docker compose up -d --build` + health 체크
