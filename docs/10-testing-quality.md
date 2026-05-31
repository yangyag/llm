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
| `BoardPostControllerTest` | 게시글, 댓글, 첨부파일, AI 답변 제약 |
| `UploadSessionControllerTest` | 업로드 세션 생성, chunk, finalize, 오류 조건 |
| `ExternalAiReplyGeneratorDefaultsTest` | AI provider 기본값 |

## 변경별 권장 게이트

| 변경 유형 | 필수 검증 |
| --- | --- |
| 백엔드 controller/service/domain | `cd back && ./gradlew clean test` |
| DB migration | 백엔드 테스트와 실제 PostgreSQL 연결 검증. 루트 compose에는 PostgreSQL 서비스가 없으므로 로컬/EC2의 외부 DB 또는 별도 PostgreSQL을 준비 |
| 프론트 UI/API client | `cd front && npm run build` |
| Dockerfile/compose | `docker compose --profile build build back-build front-build`, `docker compose up -d --wait` |
| 배포 스크립트 | `./deploy-ec2.sh --help`, EC2에서 dry-run에 준하는 option/path 확인 |
| 운영 env 변경 | 컨테이너 재기동, `docker inspect`, health, 기능 smoke test |
| AI provider 변경 | provider별 성공/오류 smoke test |
| 업로드 도구 변경 | 작은 ZIP과 큰 ZIP 업로드, 중단 후 재개 테스트 |

## 수동 smoke test

1. `docker compose up -d --wait`
2. `http://localhost:8083` 접속
3. `admin`/`admin`으로 로그인
4. 게시글 작성
5. 게시글 상세 공개 URL 확인
6. 첨부파일 업로드/다운로드 확인
7. 댓글 작성/수정/삭제 확인
8. 검색 확인
9. AI provider API key가 있다면 AI 답변 생성 확인
10. `front/public/upload_zip_post.zip`에서 스크립트를 추출해 작은 ZIP 업로드 확인

## 실패 분석 기준

- 백엔드 테스트 실패: 실패 테스트 이름, expected/actual, 관련 controller/service를 먼저 확인합니다.
- 프론트 빌드 실패: TypeScript는 없지만 Vite 번들 오류, import 경로, 환경 변수 참조를 확인합니다.
- 통합 health 실패: `docker compose ps`, `docker compose logs back`, `docker compose logs front` 순서로 확인합니다.
- DB 연결 실패: `APP_DB_HOST`, `APP_DB_NAME`, `APP_DB_SCHEMA`, 네트워크 `auto_default` 존재 여부를 확인합니다.

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
