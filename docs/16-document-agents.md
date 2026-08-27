# 문서 담당 에이전트와 리뷰 보고

이 문서는 각 문서별 책임 에이전트와 리뷰 기준을 기록합니다. 완료 조건은 각 담당 에이전트가 자기 문서에 대해 "이상 없음"을 보고하는 것입니다.

## 담당 배정

| 문서 | 담당 에이전트 | 검토 초점 | 상태 |
| --- | --- | --- | --- |
| `docs/README.md` | `docs-index-agent` | 목차, 경로, 전체 문서 연결 | 이상 없음 |
| `docs/01-project-overview.md` | `project-overview-agent` | 기능 범위, 현재 운영 상태, 제약 | 이상 없음 |
| `docs/02-development-setup.md` | `dev-setup-agent` | 개발 준비 절차, 의존성, Git 제외 대상 | 이상 없음 |
| `docs/03-local-development.md` | `local-dev-agent` | 로컬 실행, 포트, compose/dev server 차이 | 이상 없음 |
| `docs/04-architecture.md` | `architecture-agent` | 컴포넌트 관계, 요청 흐름, 배포 경계 | 이상 없음 |
| `docs/05-configuration.md` | `configuration-agent` | 환경 변수, secret 미노출, 운영 확인값 | 이상 없음 |
| `docs/06-database.md` | `database-agent` | Flyway, schema, 테이블, 백업 | 이상 없음 |
| `docs/07-api-reference.md` | `api-agent` | endpoint, 인증, 요청/응답, 오류 코드 | 이상 없음 |
| `docs/08-upload-session-tool.md` | `upload-tool-agent` | 청크 업로드 계약, secret 동기화, 재개 | 이상 없음 |
| `docs/09-ai-integration.md` | `ai-agent` | provider 설정, 오류, 운영 점검 | 이상 없음 |
| `docs/10-testing-quality.md` | `qa-agent` | 테스트 명령, 변경별 품질 게이트 | 이상 없음 |
| `docs/11-build-release.md` | `release-agent` | 이미지 빌드, push, 배포 전 체크 | 이상 없음 |
| `docs/12-ec2-deployment.md` | `ec2-agent` | SSH, EC2 파일, health, 네트워크, volume | 이상 없음 |
| `docs/13-operations-runbook.md` | `ops-agent` | 운영 점검, 배포, 백업, 장애 절차 | 이상 없음 |
| `docs/14-security.md` | `security-agent` | 인증/인가, secret, CORS, 네트워크 노출 | 이상 없음 |
| `docs/15-troubleshooting.md` | `troubleshooting-agent` | 증상별 원인과 대응, 운영 확인 명령 | 이상 없음 |
| `docs/16-document-agents.md` | `review-report-agent` | 담당 배정, 리뷰 결과, 완료 근거 | 이상 없음 |

## 공통 리뷰 기준

- 문서가 현재 저장소 코드와 compose 설정에 맞는가.
- 운영 관련 내용이 2026-05-31 KST EC2 읽기 전용 점검 결과와 맞는가.
- secret 값이 문서에 노출되지 않았는가.
- 명령이 실제 경로와 파일명 기준으로 실행 가능한가.
- 개발 단계부터 운영 단계까지 필요한 판단 기준과 확인 명령이 포함되어 있는가.
- 문서 간 링크가 존재하는 파일을 가리키는가.

## 리뷰 결과

아래 표는 담당 에이전트 최종 리뷰 결과입니다. 중간에 나온 지적사항은 관련 문서에 반영한 뒤 같은 담당 또는 재검토 담당이 다시 확인했습니다.

2026-08-08 변경 반영 후 재검토: 게시글/댓글 작성자 소유권(작성자 본인/ADMIN만 수정·삭제), 사용자 역할(V13), 작성자 컬럼(V14/V16), 기존 글/댓글 admin 백필(V15/V16), EC2 배포(V13 이력 충돌 해결)를 문서에 반영했습니다.

| 담당 에이전트 | 결과 | 보고 요약 |
| --- | --- | --- |
| `docs-index-agent` | 이상 없음 | 목차, 링크, 전체 개발-운영 흐름과 실제 문서 파일 존재를 확인했습니다. |
| `project-overview-agent` | 이상 없음 | 기능, 저장소 구성, 기술 스택, EC2 운영 상태가 코드와 실제 상태에 맞음을 확인했습니다. |
| `dev-setup-agent` | 이상 없음 | `auto_default`와 별도 PostgreSQL 준비 조건을 포함한 개발 준비 절차가 실제 compose와 맞음을 확인했습니다. |
| `local-dev-agent` | 이상 없음 | compose 포트, Nitro dev proxy, volume mount와 백엔드 fallback 경로 설명이 실제 파일과 맞음을 확인했습니다. |
| `architecture-agent` | 이상 없음 | 목록/상세 응답 차이, 업로드 세션 secret 소유권, 전체 요청 흐름이 코드와 맞음을 확인했습니다. |
| `configuration-agent` | 이상 없음 | `.env.example`, 현재 루트 `.env`, EC2 `.env`의 DB/CORS/AI 모델값 구분과 환경 변수 설명이 실제 상태와 맞음을 확인했습니다. |
| `database-agent` | 이상 없음 | Flyway/JPA 설정, migration, 테스트 DB, 테이블 제약, 백업 설명이 실제 파일과 맞음을 확인했습니다. |
| `api-agent` | 이상 없음 | endpoint, 인증, 응답 status/body, 오류 코드가 controller/dto/exception handler와 맞음을 확인했습니다. |
| `upload-tool-agent` | 이상 없음 | 업로드 ZIP 배포 파일, alias/AES-GCM 적용 범위, finalize 응답, 크기 제한, 재개 설명이 코드와 맞음을 확인했습니다. |
| `ai-agent` | 이상 없음 | provider, 환경 변수, 외부 API 호출, 오류 매핑, AI 답변 제약이 코드와 맞음을 확인했습니다. |
| `qa-agent` | 이상 없음 | `npm ci`, 외부 DB 조건, 문서 검증 명령의 exit code 처리, 품질 게이트가 현재 저장소와 맞음을 확인했습니다. |
| `release-agent` | 이상 없음 | Dockerfile, compose build profile, 이미지명/tag, 배포 전 체크와 롤백 제약이 실제 파일과 맞음을 확인했습니다. |
| `ec2-agent` | 이상 없음 | `/home/yangyag/aws` 메모 우선순위, LLM 관련 컨테이너 범위, 네트워크, volume/fallback 설명이 실제 EC2와 맞음을 확인했습니다. |
| `ops-agent` | 이상 없음 | 운영 점검, 배포, 백업, 복구 명령의 `--project-name ubuntu` 기준이 실제 EC2와 맞음을 확인했습니다. |
| `security-agent` | 이상 없음 | 인증/인가, secret 관리, 개발 fallback 예외, CORS, 파일 업로드, 네트워크 노출 설명이 코드와 맞음을 확인했습니다. |
| `troubleshooting-agent` | 이상 없음 | 장애 대응 명령, ZIP finalize 저장 경로 점검, AI 오류 대응 문구가 실제 코드/compose/EC2 상태와 맞음을 확인했습니다. |
| `review-report-agent` | 이상 없음 | 담당 배정과 리뷰 결과 표가 전체 문서 세트 및 최종 완료 조건을 빠짐없이 반영함을 확인했습니다. |

## EC2 점검 근거

읽기 전용으로 수행한 확인:

```bash
ssh -o BatchMode=yes -o ConnectTimeout=8 -o StrictHostKeyChecking=no \
  -i /home/yangyag/aws/test-keypair.pem ubuntu@43.202.113.123 \
  'cd /home/ubuntu/llm && docker ps && curl -fsS http://127.0.0.1:8083/api/v1/health'
```

확인 결과 요약:

- `/home/ubuntu/llm/.env`, `/home/ubuntu/llm/docker-compose.yml` 존재
- `llm-front`, `llm-back`, `yangyag-postgres` health 정상
- health API는 front proxy 경유 `http://127.0.0.1:8083/api/v1/health`에서 정상
- 백엔드 8080은 호스트에 직접 공개되지 않음
