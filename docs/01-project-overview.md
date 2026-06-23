# 프로젝트 개요

`llm`은 익명 게시판, 관리자 로그인, 첨부파일, 단일 ZIP 청크 업로드, AI 답변 생성을 제공하는 monorepo입니다. 프론트엔드는 React/Vite, 백엔드는 Spring Boot, 데이터베이스는 PostgreSQL을 사용하며, 배포 단위는 Docker Compose입니다.

## 주요 기능

- 관리자 로그인과 JWT 기반 인증
- 유휴 1시간 또는 서버의 토큰 거부(401) 시 프론트엔드 자동 로그아웃
- 게시글 목록, 상세, 작성, 수정, 삭제
- 댓글 작성, 수정, 삭제
- 게시글 첨부파일 다중 업로드(일반 게시글 최대 5개, 파일당 100MB), 개별 삭제, 다운로드
- 게시글 검색
- 게시글 일괄 삭제
- OpenAI, Anthropic Claude, xAI Grok 기반 AI 답변 생성
- 단일 ZIP 파일을 청크로 업로드하고 서버에서 재조립한 뒤 결과 게시글 생성
- 공개 게시글 조회와 첨부파일 다운로드

## 저장소 구성

| 경로 | 역할 |
| --- | --- |
| `front/` | React/Vite UI, API 클라이언트, Nginx 설정, 프론트 Dockerfile |
| `back/` | Spring Boot API, JPA 도메인, Flyway 마이그레이션, 테스트, 백엔드 Dockerfile |
| `docker-compose.yml` | 공통 런타임과 빌드 프로파일 |
| `deploy-ec2.sh` | EC2 순차 배포 스크립트 |
| `.env.example` | 로컬/운영 `.env` 작성 기준 |
| `front/public/upload_zip_post.zip` | ZIP 청크 업로드 스크립트 배포 파일 |
| `docs/` | 개발부터 운영까지의 문서 |

## 기술 스택

| 영역 | 구성 |
| --- | --- |
| Frontend | React 18, Vite 5, `js-base64`, Nginx |
| Backend | Spring Boot 3.5.11, Java 25, Gradle, JPA, Flyway |
| Database | PostgreSQL 18 운영 기준, H2 테스트 기준 |
| Auth | 관리자 계정, BCrypt password hash, JWT HS256 |
| Storage | Docker volume에 첨부파일과 업로드 세션 파일 저장 |
| AI | OpenAI chat completions, Anthropic messages, xAI chat completions |
| Deployment | Docker Compose, Docker Hub 이미지 `yangyag2/llm-front:latest`, `yangyag2/llm-back:latest` |

## 사용자 흐름

1. 관리자는 `/api/v1/auth/login`으로 로그인하고 JWT를 받습니다.
2. 프론트는 JWT를 `localStorage`에 보관하고 쓰기 API에 `Authorization: Bearer <token>`을 보냅니다.
3. 프론트는 마지막 사용자 활동 후 1시간 동안 무동작이거나 인증 요청이 401을 받으면 보관한 JWT를 지우고 자동으로 로그아웃합니다. 백엔드 JWT는 만료 시간이 고정이며 토큰 갱신이나 슬라이딩 세션은 없습니다.
4. 게시글 목록, 상세, 첨부파일 다운로드는 로그인 없이 접근할 수 있습니다.
5. 쓰기 작업, 댓글 작업, AI 답변 생성, 업로드 세션 API는 JWT가 필요합니다.
6. ZIP 업로드 도구는 ZIP 파일을 base64 청크로 나누고, alias 필드와 AES-GCM 암호문 JSON으로 백엔드에 전송합니다.
7. 서버는 청크를 임시 저장소에 보관하고 finalize 시 원본 ZIP을 재조립해 첨부파일 게시글을 생성합니다.

## 현재 확인된 운영 상태

2026-05-31 KST에 `/home/yangyag/aws/test-keypair.pem`으로 EC2에 접속해 읽기 전용 점검을 수행했습니다.

- EC2 운영 디렉터리: `/home/ubuntu/llm`
- 실행 중인 LLM 컨테이너: `llm-front`, `llm-back`
- 공용 PostgreSQL 컨테이너: `auto-postgres`
- `llm-front`, `llm-back`, `auto-postgres` 모두 Docker health 기준 `healthy`
- 헬스체크 응답: `http://127.0.0.1:8083/api/v1/health`에서 `{"status":"UP",...}`
- 백엔드 8080은 호스트에 직접 publish되어 있지 않고 front 컨테이너가 `/api/`를 proxy합니다.

## 운영상 중요한 제약

- 기본 관리자 계정은 마이그레이션으로 `admin`/`admin`이 생성되므로, 공용 노출 전에 반드시 변경해야 합니다.
- `APP_JWT_SECRET`와 `APP_UPLOAD_SESSIONS_SECRET`는 운영에서 반드시 별도 값으로 설정해야 합니다.
- 일반 게시글/댓글 body는 Base64로 전송하지만 보안 기능이 아닙니다.
- `FILE_CONVERSION_REQUEST` 게시글은 수동 생성할 수 없고 업로드 세션 finalize로만 생성됩니다.
- 파일 변환 게시글에 첨부파일이 생기면 게시글 수정이 막힙니다.
- AI 답변은 수정/삭제가 막힙니다.
