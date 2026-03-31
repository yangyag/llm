# AGENTS.md

## 작업 실행 규약
- 모든 작업은 루트 `Harness.md`에 정의된 워크플로우를 의무적으로 따른다.
- 작업은 반드시 `Planner -> Generator -> Evaluator` 순서로 진행한다.
- 각 단계는 별도 단계로 취급하며, 중간 판단과 최종 보고도 해당 순서를 유지한다.
- `Harness.md`와 `AGENTS.md`가 충돌하면 `Harness.md`를 우선한다.
- 실제 서브 에이전트를 사용할 수 있는 환경이면 단계별 역할을 분리한다.
- 실제 서브 에이전트 사용이 불가능한 환경이면 단일 에이전트로 수행하더라도 `Planner`, `Generator`, `Evaluator` 산출물과 판정은 반드시 분리해서 남긴다.
- Evaluator 점수가 100점 만점 기준 85점 미만이면 작업 완료로 간주하지 않고, Evaluator 피드백을 기준으로 Generator 단계로 복귀한다.
- 재작업과 재평가는 최대 3회까지 반복한다.
- 최종 보고는 반드시 `계획`, `구현`, `평가`, `남은 리스크`를 포함한다.
- 사용자가 명시적으로 절차 생략을 허용하지 않는 한 이 규약은 생략하지 않는다.

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

## 디렉터리 규칙
- `front/`: UI, 라우팅, 상태관리, 프론트 Docker 빌드
- `back/`: API, 도메인, 테스트, 정적 자산, 백엔드 Docker 빌드
- 루트: 통합 실행/문서(`docker-compose.yml`, `llm.env.example`, `README.md`)

## 멀티 에이전트 역할
- `A0-Orchestrator`: 작업 분해, 우선순위, 통합 문서/결과 정리
- `A1-Backend`: API/검증/예외 처리, JUnit(MockMvc)
- `A2-Frontend`: 화면/상태/API 연동
- `A3-Integration`: LLM 연동 계약 및 환경 변수 설계
- `A4-DevOps`: compose, Dockerfile, 실행/배포 체인
- `A5-QA`: 회귀 체크, 수용 기준 판정

## 병렬 작업 규칙
- `front/`와 `back/`는 파일 충돌이 없으면 병렬 작업한다.
- 공용 계약(API 경로/응답/자산 규칙)은 먼저 고정한다.
- 루트 공용 파일(`docker-compose.yml`, `AGENTS.md`)은 통합 리뷰 후 반영한다.

## Git 규칙
- `git commit` 메시지는 한글로 작성한다.

## 품질 게이트
- 코드 + 테스트 + 문서를 같은 변경 단위로 갱신한다.
- 백엔드 기능 변경 시 `back` 테스트를 반드시 통과시킨다.
- 최소 검증:
  - `cd back && ./gradlew clean test`
  - `cd front && npm run build`
  - 루트 `docker compose up -d --build` + health 체크

## 단계별 산출물
### Planner
- 요구사항 요약
- 세부 기능 설계
- 영향 범위 파일 또는 모듈
- 테스트 계획
- 수용 기준

### Generator
- Planner 산출물 기준 구현
- 테스트 코드와 문서 동시 반영
- 구현 중 설계 변경 시 변경 사유 기록

### Evaluator
- 실제 실행 검증 결과
- 점수표 채점 결과
- 통과 또는 실패 판정
- 실패 시 재작업 항목

## 참조 문서
- 작업 시작 전 반드시 루트 `Harness.md`를 확인하고, 진행 중 의사결정과 완료 판정도 해당 문서를 기준으로 판단한다.
- 현재 v1은 LLM 데모 골격 단계이며, 실제 외부 LLM 연동은 후속 작업으로 진행한다.
