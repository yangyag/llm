# 테스트와 품질 게이트

변경 유형별로 필요한 검증 범위를 정합니다.

## 기본 검증 명령

백엔드:

```powershell
cd back
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test
```

`<JAVA_25_HOME>`은 실행 환경의 Java 25 설치 경로로 바꿉니다. 경로·secret 값은 저장소에 기록하지 않습니다.

프론트:

```bash
cd /home/yangyag/llm/front
npm ci
npm test
npm run typecheck
npm run build
```

통합 명령은 compose 스택과 외부 PostgreSQL이 준비된 환경에서 실행합니다. 2026-09-08 최신 `llm-back:1.0` 이미지로 compose를 기동해 `llm-back`·`llm-front` health, 8083 health, 공개 게시글 목록 조회를 확인했고, 별도 격리 환경에서 실제 인증 ZIP HTTP upload/download smoke도 성공했습니다. smoke는 `m2-llm-back:smoke` sha256 `8572eaceed6e2df0b9211d3bfdb47e67a7bfbe9ea644d570a2152086ac2d68ef` 및 `m2-llm-front:smoke` sha256 `55f49492d323754df8972a4a6177771a16290154c75d68cc4f8e1e521ad61582`를 사용했습니다. PostgreSQL 17, database `llm_m2_smoke`, schema `llm`, Flyway V1~V18이 모두 성공했고, 검증 후 격리 자원을 제거하고 원본 front 네트워크를 복원했습니다.

## 백엔드 테스트 구성

테스트 설정 파일: `back/src/test/resources/application.properties`

- H2 in-memory DB 사용
- PostgreSQL mode 사용
- Flyway 비활성화
- Hibernate `create-drop`
- 테스트용 JWT secret과 업로드 세션 secret 사용
- 첨부파일과 업로드 세션 root는 temp 디렉터리 사용
- 테스트 multipart 제한은 `2MB`

현재 테스트 범위:

| 테스트 파일 | 범위 |
| --- | --- |
| `AuthControllerTest` | 로그인, JWT 검증, 인증 실패 |
| `HealthControllerTest` | health endpoint |
| `UserManagementControllerTest` | 사용자 추가/수정/삭제, ADMIN 전용, 마지막 ADMIN/자기 자신 보호 |
| `BoardPostControllerTest` | 게시글, 댓글, 첨부파일, AI 답변 제약, 작성자 소유권(본인/ADMIN/레거시), 일괄 삭제 권한, 댓글 소유권 |
| `UploadSessionControllerTest` | 업로드 세션 생성, chunk, finalize, 오류 조건, finalize 게시글 작성자 기록, 크기 제한 경계, 실패 시 파일 정리, 타인 접근/만료/완료 상태 |
| `JwtProviderTest` | 토큰 생성/검증, 만료, 위조, Bearer 형식 |
| `SecretKeyDerivationTest` | 키 파생(32바이트 미만 확장/이상 절단) |
| `BoardContentCodecTest` | bodyBase64 디코딩 경계(blank/100만자/오류) |
| `UploadSessionWireCodecTest` | 암호화 라운드트립, AAD alias 바인딩, 변조/타 secret 거부 |
| `ExternalAiReplyGeneratorDefaultsTest` | legacy AI provider 기본값 및 종료된 기능의 잔재 |
| `ApplicationModulesDiagnosticTest` | Spring Modulith 모듈 경계·순환·내부 접근·허용 의존 strict verification |
| `GeneratedAttachmentPolicyTest` | board 공개 생성 첨부 크기 정책과 제한 경계 |
| `PostgresUploadFinalizeTest` | disposable PostgreSQL finalize transaction rollback·commit failure와 파일 정리 |

## 추가 회귀 검증

`SecurityAndStorageRegressionTest`는 삭제 계정의 모든 쓰기·업로드 차단, username 재사용 시 토큰/소유권 분리, 첨부 롤백·삭제 재시도, 긴 ZIP 제목을 검증합니다. `front/tests/postDetail.test.cjs`는 실제 Pinia store에서 응답 순서 역전·조회 실패·저장 중 이동·ID 불일치·계정 ID 권한 표시를 검증합니다.

`PostgresMigrationTest`와 `PostgresUploadFinalizeTest`는 `LLM_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:<임시포트>/postgres`가 있을 때만 실행하고, 변수가 없으면 JUnit 조건에 따라 각각 1개·2개 테스트를 건너뜁니다. 별도 disposable PostgreSQL의 postgres 사용자와 격리된 무작위 schema·임시 파일 저장소를 사용하며 운영 DB를 지정하지 않습니다. Migration 테스트는 V1~V18 적용·소유권 백필·삭제 FK·Hibernate validate를 확인하고, finalize 테스트는 (a) 본문 flush 이후 실패와 (c) 실제 commit 단계 지연 삭제 실패를 확인합니다. (b) 명시적 session/part 삭제 flush 뒤 본문 실패는 production-only flush hook을 추가하지 않아 미커버입니다.

2026-09-08 최신 확인은 Docker 이미지 `postgres:1.0`(PostgreSQL 17.10)을 localhost port `55432`에 둔 환경에서 수행했습니다. PostgreSQL 17.10 disposable test image는 stated production PostgreSQL 18을 대체하지 않습니다. focused 결과는 `PostgresMigrationTest` 1/1 통과와 `PostgresUploadFinalizeTest` 2/2 통과입니다. 환경변수 없이 실행한 전체 `clean test`는 **146개 발견, 143개 통과, 3개 skipped, 실패 0개·오류 0개**였고, skipped 3개는 두 PostgreSQL 조건부 테스트입니다. 실제 PostgreSQL focused 실행에서는 해당 3개가 모두 통과했습니다.

실제 전체 실행 예시:

```powershell
cd back
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test
```

focused PostgreSQL 실행 예시(환경변수 값 자체는 기록하거나 공유하지 않음):

```powershell
cd back
$env:LLM_TEST_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:<임시포트>/postgres'
.\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' test --tests com.llm.app.review.PostgresMigrationTest --tests com.llm.app.review.PostgresUploadFinalizeTest
Remove-Item Env:LLM_TEST_POSTGRES_URL
```
| 변경 유형 | 필수 검증 |
| --- | --- |
| 백엔드 controller/service/domain | `cd back && .\gradlew.bat '-Porg.gradle.java.installations.paths=<JAVA_25_HOME>' clean test` |
| DB migration | 백엔드 테스트와 실제 PostgreSQL 연결 검증. 루트 compose에는 PostgreSQL 서비스가 없으므로 로컬/EC2의 외부 DB 또는 별도 PostgreSQL을 준비 |
| 프론트 UI/API client | `cd front && npm run typecheck && npm run build` |
| Dockerfile/compose | 프론트는 `cd front && npm run build` 후 `docker compose --profile build build front-build`. 백엔드는 `build back-build`. 이어서 `docker compose up -d --wait` |
| EC2 배포 절차 | `auto_default` 존재와 `docker compose --project-name ubuntu --env-file .env -f docker-compose.yml config --quiet`를 확인하고 Compose pull/up/ps 및 8083 health 경로 검증 |
| 운영 env 변경 | 컨테이너 재기동, `docker inspect`, health, 기능 smoke test |
| AI provider 변경 | provider별 성공/오류 smoke test |
| 업로드 도구 변경 | 작은 ZIP과 큰 ZIP 업로드, 중단 후 재개 테스트 |

## 수동 smoke test

아래 절차는 compose 스택이 실행 중이고 외부 PostgreSQL이 준비된 환경에서 수행합니다. 2026-09-08에는 최신 백엔드 이미지로 compose 기동, 컨테이너 health, 8083 health와 공개 게시글 목록 조회를 확인했습니다. 이어 격리 네트워크(back 18080, front proxy 18083)에서 실제 HTTP 로그인과 `upload_zip_post.py`의 AES-GCM alias 세션 생성, 암호화 청크 1건, finalize, 다운로드를 성공했습니다. `upload_zip_post.py`는 exit 0이었고, 결과 게시글은 `FILE_CONVERSION_REQUEST`, `conversionReady=true`, `authorUserId=2`, 첨부 `/api/v1/posts/1/attachments/1`이었습니다. 원본·다운로드 ZIP SHA-256 `a7a184d93123d7f52442f8bd6b4897f3665cc8f00cbc7aa647699b48279f2904`가 일치하고 바이트 비교 및 다운로드 HTTP 200/application/zip 길이 비교가 통과했습니다. 정리 후 `upload_sessions=0`, `upload_session_parts=0`, 세션 임시 volume empty, 영구 첨부 volume에 ZIP이 남았습니다.

1. `docker compose up -d --wait`
2. `http://localhost:8083` 접속
3. 기본 관리자 계정으로 로그인
4. 게시글 작성
5. 게시글 상세 공개 URL 확인
6. 첨부파일 업로드/다운로드 확인
7. 댓글 작성/수정/삭제 확인
8. 검색 확인
9. AI 답변 endpoint가 `410 Gone` / `AI_REPLY_DISABLED`를 반환하는지 확인(외부 provider 호출 없음)
10. `front/public/upload_zip_post.zip`에서 스크립트를 추출해 작은 ZIP 업로드 확인
11. 일반 글 상세(로그인·공개)에서 본문 복사 → 붙여넣기 = 화면 본문; 변환글에서는 버튼 없음

## 실패 분석 기준

- 백엔드 테스트 실패: 실패 테스트 이름, expected/actual, 관련 controller/service를 먼저 확인합니다.
- 프론트 검사/빌드 실패: `nuxi typecheck`의 TypeScript 오류, Vue 컴포넌트 import, `NUXT_PUBLIC_API_BASE`, 정적 생성 로그를 확인합니다.
- 통합 health 실패: `docker compose ps`, `docker compose logs back`, `docker compose logs front` 순서로 확인합니다.
- DB 연결 실패: `APP_DB_HOST`, `APP_DB_NAME`, `APP_DB_SCHEMA`, 네트워크 `auto_default` 존재 여부를 확인합니다.

## 프론트 의존성 보안 점검

```bash
cd /home/yangyag/llm/front
npm audit --omit=dev
npm ls nuxt nitropack archiver archiver-utils readdir-glob zip-stream minimatch brace-expansion vue-tsc --all
```

2026-07-30 기준 `vue-tsc`를 Nuxt와 호환되는 `3.3.8`로 갱신한 뒤 `npm audit --omit=dev`에 남은 high 11건은 하나의 [`brace-expansion` 메모리 고갈(DoS) 권고](https://github.com/advisories/GHSA-mh99-v99m-4gvg)에서 파생됩니다.

- 빌드 경로: `nuxt` → `@nuxt/nitro-server` → `nitropack` → `archiver` → `glob`/`minimatch`/`brace-expansion`
- 직접 선언 의존성으로 표시되는 것은 `nuxt`이고, 취약 구현은 전이 의존성에 있습니다. 기존 `vue-tsc` 2.x 경로는 3.3.8 갱신으로 제거했습니다.
- Docker 최종 단계는 `.output/public`만 `nginx:1.27-alpine`에 복사하므로 Node/Nuxt 의존성은 운영 이미지에 포함되지 않습니다. 따라서 HTTP 런타임 노출은 없고, 신뢰하지 않는 glob 입력을 빌드에 넣을 때의 빌드 가용성 위험으로 분류합니다.
- 당시 최신 Nuxt 3(`3.21.10`)과 Nuxt 4도 같은 전이 경로가 보고되어, 강제 major override나 Nuxt 3 다운그레이드는 적용하지 않습니다. 패치된 Nuxt 3/Nitropack 계열이 나오면 `npm install` 후 typecheck/build/Docker 회귀검증을 수행합니다.

`npm audit fix --force`는 Nuxt/타입 도구의 호환성을 깨뜨릴 수 있으므로 사용하지 않습니다.

## 문서 변경 검증

문서만 바꾼 경우에도 최소한 다음을 확인합니다.

```bash
find docs -maxdepth 1 -type f -name '*.md' | sort
rg -n 'TO''DO|TB''D|FIX''ME' docs || true
```

문서에 secret 값이 들어가지 않았는지도 확인합니다.

```bash
rg -n 'API_''KEY=.*[A-Za-z0-9_-]{12,}|SEC''RET=.*[A-Za-z0-9_-]{12,}|PASS''WORD=.*[A-Za-z0-9_-]{8,}|TO''KEN=.*[A-Za-z0-9_-]{12,}' docs || true
```
