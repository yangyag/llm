# 테스트와 품질 게이트

변경 유형별로 필요한 검증 범위를 정합니다.

## 기본 검증 명령

백엔드:

```bash
cd /home/yangyag/llm/back
./gradlew clean test
```

프론트:

```bash
cd /home/yangyag/llm/front
npm ci
npm run typecheck
npm run build
```

통합:

```bash
cd /home/yangyag/llm
docker compose up -d --wait
curl -fsS http://localhost:8083/api/v1/health
```

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
| `UploadSessionControllerTest` | 업로드 세션 생성, chunk, finalize, 오류 조건, finalize 게시글 작성자 기록, 타인 접근/만료/완료 상태 |
| `JwtProviderTest` | 토큰 생성/검증, 만료, 위조, Bearer 형식 |
| `SecretKeyDerivationTest` | 키 파생(32바이트 미만 확장/이상 절단) |
| `BoardContentCodecTest` | bodyBase64 디코딩 경계(blank/100만자/오류) |
| `UploadSessionWireCodecTest` | 암호화 라운드트립, AAD alias 바인딩, 변조/타 secret 거부 |
| `ExternalAiReplyGeneratorDefaultsTest` | AI provider 기본값 |

## 변경별 권장 게이트

| 변경 유형 | 필수 검증 |
| --- | --- |
| 백엔드 controller/service/domain | `cd back && ./gradlew clean test` |
| DB migration | 백엔드 테스트와 실제 PostgreSQL 연결 검증. 루트 compose에는 PostgreSQL 서비스가 없으므로 로컬/EC2의 외부 DB 또는 별도 PostgreSQL을 준비 |
| 프론트 UI/API client | `cd front && npm run typecheck && npm run build` |
| Dockerfile/compose | 프론트는 `cd front && npm run build` 후 `docker compose --profile build build front-build`. 백엔드는 `build back-build`. 이어서 `docker compose up -d --wait` |
| EC2 배포 절차 | `auto_default` 존재와 `docker compose --project-name ubuntu --env-file .env -f docker-compose.yml config --quiet`를 확인하고 Compose pull/up/ps 및 8083 health 경로 검증 |
| 운영 env 변경 | 컨테이너 재기동, `docker inspect`, health, 기능 smoke test |
| AI provider 변경 | provider별 성공/오류 smoke test |
| 업로드 도구 변경 | 작은 ZIP과 큰 ZIP 업로드, 중단 후 재개 테스트 |

## 수동 smoke test

1. `docker compose up -d --wait`
2. `http://localhost:8083` 접속
3. 기본 관리자 계정으로 로그인
4. 게시글 작성
5. 게시글 상세 공개 URL 확인
6. 첨부파일 업로드/다운로드 확인
7. 댓글 작성/수정/삭제 확인
8. 검색 확인
9. AI provider API key가 있다면 AI 답변 생성 확인
10. `front/public/upload_zip_post.zip`에서 스크립트를 추출해 작은 ZIP 업로드 확인

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
