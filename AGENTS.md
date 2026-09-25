# AGENTS.md

게시판 + ZIP 청크 업로드 모노레포. `front/`(Nuxt 3/Vue 3/TypeScript/Pinia), `back/`(Spring Boot, Java 25, PostgreSQL/Flyway), 루트 `docker-compose.yml`. AI 답변 기능은 종료되어 레거시 코드만 남아 있다(docs/09).

## 이 파일의 역할
- 이 파일에는 작업 규칙과 문서 위치만 둔다. 구조·설정값·API 계약·절차는 `docs/`가 유일한 기준이다. 목차는 [docs/index.md](docs/index.md).
- 설명·수치·버전을 이 파일에 추가하지 않는다. 새 사실은 해당 docs 문서에 쓰고, 필요하면 여기서 링크만 건다.
- 코드를 바꾸면 같은 변경에서 관련 docs를 함께 고친다(아래 표). 문서와 운영이 다르면 EC2 실제 파일·컨테이너 상태가 기준이다.

## 검증 게이트
- 백엔드 변경: `cd back && ./gradlew clean test`
- 프론트 변경: `cd front && npm test && npm run typecheck && npm run build`
- 통합 영향: `docker compose up -d --wait` 후 `curl -fsS http://127.0.0.1:8083/api/v1/health`
- 변경 유형별 추가 검증은 docs/10.

## 반드시 지킬 규칙
- secret(`.env` 값, API key, JWT·업로드 세션 secret, DB 비밀번호, PEM key)은 문서·Git·로그·화면에 기록하지 않는다. `.env`, `.env.*`, `llm.env*`와 빌드 산출물은 커밋하지 않는다. (docs/14)
- 적용된 Flyway migration은 수정하지 않고 새 버전 파일을 추가한다. 배포 전 운영 `flyway_schema_history`와 로컬 파일을 대조한다. (docs/06, docs/12)
- 운영에서 `docker compose down -v`, volume 삭제·prune을 하지 않는다(첨부 데이터 손실). 운영 compose 명령은 docs/12 그대로 쓴다.
- EC2에서 `auto_default` 네트워크를 만들지 않는다. 존재만 확인한다. (docs/12)
- health 확인은 front proxy 8083을 거친다. 백엔드 8080은 호스트에 공개되지 않는다. (docs/03)
- 이미지는 Docker Hub 없이 Windows에서 빌드해 tar로 EC2에 load한다. EC2에서 소스를 빌드하지 않는다. (docs/11, docs/12)
- 커밋 메시지는 한글로 쓴다. 작업 전후 `git status --short`로 변경 범위를 확인한다.

## 무엇을 바꾸면 어느 문서를 고치나
| 변경 | 문서 |
|------|------|
| API endpoint·요청/응답·오류 코드 | docs/07 (보안 영향은 docs/14) |
| 인증·권한·세션 | docs/14, docs/04 |
| DB migration·테이블·제약 | docs/06 |
| 환경 변수·설정 기본값 | docs/05, `.env.example` |
| rich 본문·inline 이미지 | docs/04, docs/07, docs/14 |
| ZIP 업로드 세션·스크립트 | docs/08 |
| 테스트 추가·검증 결과 | docs/10 |
| Dockerfile·compose·nginx·배포 스크립트 | docs/11, docs/12 |
| 운영 절차·장애 대응 | docs/13, docs/15 |
| 백엔드 모듈 경계 | docs/19, docs/04 |
| 문서 추가·삭제 | docs/index.md |
