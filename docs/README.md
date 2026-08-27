# llm 프로젝트 문서

이 폴더는 `llm` monorepo를 개발 단계부터 EC2 운영 단계까지 관리하기 위한 문서 세트입니다. 문서는 현재 저장소, Docker Compose 구성, 애플리케이션 코드, 테스트, `/home/yangyag/aws`의 EC2 접속 자료, 그리고 2026-05-31 KST 기준 EC2 읽기 전용 점검 결과를 근거로 작성했습니다.

## 빠른 진입

| 목적 | 문서 |
| --- | --- |
| 프로젝트가 무엇인지 파악 | [01-project-overview.md](./01-project-overview.md) |
| 처음 개발 환경 준비 | [02-development-setup.md](./02-development-setup.md) |
| 로컬에서 프론트/백엔드 실행 | [03-local-development.md](./03-local-development.md) |
| 시스템 구조와 데이터 흐름 이해 | [04-architecture.md](./04-architecture.md) |
| 환경 변수와 설정값 확인 | [05-configuration.md](./05-configuration.md) |
| DB 스키마와 마이그레이션 확인 | [06-database.md](./06-database.md) |
| API 계약 확인 | [07-api-reference.md](./07-api-reference.md) |
| ZIP 청크 업로드 도구 운영 | [08-upload-session-tool.md](./08-upload-session-tool.md) |
| AI 답변 연동 운영 | [09-ai-integration.md](./09-ai-integration.md) |
| 테스트와 품질 게이트 | [10-testing-quality.md](./10-testing-quality.md) |
| 이미지 빌드와 릴리스 | [11-build-release.md](./11-build-release.md) |
| EC2 배포 | [12-ec2-deployment.md](./12-ec2-deployment.md) |
| 운영 점검과 장애 대응 절차 | [13-operations-runbook.md](./13-operations-runbook.md) |
| 보안 기준 | [14-security.md](./14-security.md) |
| 문제 해결 | [15-troubleshooting.md](./15-troubleshooting.md) |
| 문서 담당 에이전트와 리뷰 결과 | [16-document-agents.md](./16-document-agents.md) |

## 현재 운영 기준

- 로컬 저장소 루트: `/home/yangyag/llm`
- EC2 접속 자료: 저장소 루트 `aws/` 폴더 (Git 제외)
- EC2 접속 키: `aws/test-keypair.pem`
- EC2 운영 디렉터리: `/home/ubuntu/llm`
- EC2 운영 서비스: `llm-front`, `llm-back`, `yangyag-postgres`
- EC2 운영 DB: `yangyag-postgres` 컨테이너의 database `llm` (schema `llm`)
- EC2 외부 공개 포트: front `8083`
- EC2 백엔드 헬스 확인: `http://127.0.0.1:8083/api/v1/health`

## 공통 원칙

- `.env`, 운영 키, API 키, JWT secret, 업로드 세션 secret은 문서나 Git에 기록하지 않습니다.
- 루트 `docker-compose.yml`이 로컬과 EC2의 공통 실행 단위입니다.
- 운영 배포는 `/home/ubuntu/llm/.env`와 `/home/ubuntu/llm/docker-compose.yml`을 기준으로 확인합니다.
- 백엔드 API 변경 시 `cd back && ./gradlew clean test`를 통과시킵니다.
- 게시글 수정/삭제는 작성자 본인 또는 ADMIN만 가능합니다(작성자 소유권, V14/V15).
- 프론트 변경 시 `cd front && npm run typecheck && npm run build`를 통과시킵니다.
- 통합 영향이 있으면 `docker compose up -d --wait`와 헬스체크를 확인합니다.
