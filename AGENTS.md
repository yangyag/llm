# AGENTS.md

## 목적
- monorepo(`llm`) 운영 기준 문서다.
- `front/`, `back/` 하위 프로젝트를 한 저장소에서 일관되게 관리한다.

## 에이전트
최대 6개의 에이전트를 구동할 수 있다. 가능한 아래의 역할에 따라 병렬로 작업을 수행한다.

[ Main Session ]
 - 역할:
  1) 사용자 요청 최종 해석
  2) agent 할당하고 모델을 지정
  3) 충돌 해결
  4) 최종 결과 통합
  5) 최종 승인
 - 비고: 결코 직접 구현 하지 않는다. 구현은 하위 에이전트들을 통해서만 수행한다. “통합자 + 최종 판단자”에 집중

[ Planner / Task Decomposer ]
 - 역할:
  1) 요구사항 분석
  2) 작업 분해
  3) 병렬 가능 여부 판단
  4) 완료 기준(acceptance criteria) 정의
  5) 구현 순서/의존성 정의

[ Implementer ]
 - 역할:
  1) 주 구현 담당
  2) 요구사항에 따라 백엔드 / 프론트로 영역 분리
 - 비고 : 요구사항 수준에 따라 Agent 의 개수가 정해진다.
 
[ Review Gatekeeper ]
 - 역할:
  1) 구현물 리뷰
  2) 요구사항 충족 여부 점검
  3) 리스크 판정
  4) 수정 요청 우선순위화
 - 비고: pass / revise / block 명확히

[ Test Verifier ]
 - 역할:
  1) 테스트 시나리오 설계
  2) 수동/자동 테스트 실행
  3) 실패 케이스 정리
  4) 회귀 위험 보고
 - 비고: 코드 수정 금지, 결과 보고 전담

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
