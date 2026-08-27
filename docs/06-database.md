# 데이터베이스

백엔드는 PostgreSQL을 사용하고 Flyway로 schema를 관리합니다. 테스트는 H2 in-memory DB를 PostgreSQL mode로 실행하며 Flyway를 끄고 Hibernate `create-drop`을 사용합니다.

## 연결 설정

운영 기준:

```env
APP_DB_HOST=yangyag-postgres
APP_DB_PORT=5432
APP_DB_NAME=llm
APP_DB_SCHEMA=llm
```

운영 PostgreSQL은 공용 컨테이너 `yangyag-postgres`(compose 프로젝트 `auto`, 예전 이름 `auto-postgres`)를 쓰되, LLM 데이터는 전용 database `llm`(owner `llm`)에 둡니다. 스키마 이름은 그대로 `llm`입니다. 2026-08-27에 `auto` database의 `llm` 스키마에서 이관했고, 확인 후 구 스키마 `auto.llm`은 삭제했습니다. 같은 날 컨테이너 이름을 `yangyag-postgres`로 바꿨고, LLM은 Docker 네트워크 DNS(`APP_DB_HOST=yangyag-postgres`)로 접속합니다. 런타임은 `APP_DB_NAME=llm`만 사용합니다.

로컬 예시:

```env
APP_DB_HOST=host.docker.internal
APP_DB_PORT=5432
APP_DB_NAME=yangyag
APP_DB_SCHEMA=public
```

JPA와 Flyway는 같은 schema를 봅니다.

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.default-schema=${APP_DB_SCHEMA:public}
spring.flyway.schemas=${APP_DB_SCHEMA:public}
spring.flyway.create-schemas=true
```

## 주요 테이블

| 테이블 | 역할 |
| --- | --- |
| `posts` | 게시글 본문, 제목, 모드, 작성자(`author_username`), 생성/수정 시각 |
| `post_replies` | 댓글과 AI 답변(작성자 `author_username`, AI 답변은 null) |
| `post_attachments` | 게시글 첨부파일 메타데이터(일반 게시글 최대 5개) |
| `admins` | 사용자 계정(ADMIN/USER 역할) |
| `upload_sessions` | ZIP 청크 업로드 세션 |
| `upload_session_parts` | 세션별 청크 파일 메타데이터 |

## 마이그레이션 목록

| 파일 | 내용 |
| --- | --- |
| `V1__create_board_tables.sql` | `posts`, `post_replies` 생성 |
| `V2__change_body_columns_to_text.sql` | body 컬럼을 `text`로 변경 |
| `V3__add_ai_columns_to_replies.sql` | AI 답변 여부와 provider 컬럼 추가 |
| `V4__create_post_attachments_table.sql` | 첨부파일 테이블 생성(게시글당 1개 unique 제약, 이후 V12에서 해제) |
| `V5__add_post_mode_column.sql` | 게시글 `mode` 추가 |
| `V6__create_admins_table.sql` | 관리자 테이블과 기본 `admin` 계정 생성 |
| `V7__remove_password_columns.sql` | 게시글/댓글 password 컬럼 제거 |
| `V8__create_upload_sessions_tables.sql` | 업로드 세션과 part 테이블 생성 |
| `V9__migrate_upload_sessions_to_chunk_model.sql` | part를 chunk 모델로 변경하고 SHA/크기 컬럼 추가 |
| `V10__purge_upload_sessions_for_chunk_contract_break.sql` | 청크 계약 변경에 따라 기존 세션 데이터 정리 |
| `V11__add_ai_model_to_replies.sql` | AI 답변에 사용한 모델 컬럼 추가 |
| `V12__allow_multiple_post_attachments.sql` | `post_attachments.post_id` unique 제약 해제(다중 첨부 허용) |
| `V13__add_role_to_admins.sql` | `admins`에 `role` 컬럼 추가. 기존 계정(시드 admin 포함)은 전부 `ADMIN`으로 승계 |
| `V14__add_author_to_posts.sql` | `posts.author_username` 추가. 작성자 본인/관리자 수정·삭제 권한 기준. 기존 글은 null |
| `V15__backfill_post_author_as_admin.sql` | 기존 게시글 중 `author_username`이 null인 글을 전부 `admin`으로 백필 |
| `V16__add_author_to_replies.sql` | `post_replies.author_username` 추가. 작성자 본인/관리자 수정·삭제 권한 기준. 기존 일반 댓글은 `admin`으로 백필(AI 답변 제외) |

## 도메인 제약

- `post_attachments.post_id`는 더 이상 unique가 아닙니다(V12). 한 게시글에 여러 첨부파일을 허용하며, 개수 상한(일반 게시글 5개)은 애플리케이션(`app.attachments.max-count`)에서 강제합니다. 업로드 세션 finalize로 만든 `FILE_CONVERSION_REQUEST` 게시글은 항상 첨부 1개입니다.
- `post_replies.post_id`는 게시글 삭제 시 cascade 삭제됩니다.
- `upload_session_parts.session_id`는 세션 삭제 시 cascade 삭제됩니다.
- `upload_session_parts`는 `(session_id, chunk_number)` unique 제약을 가집니다.
- `admins.username`은 unique입니다.
- `admins.role`은 `varchar(20) not null default 'ADMIN'`이며 `check (role in ('ADMIN', 'USER'))` 제약으로 `ADMIN`/`USER`만 허용합니다(V13). V13 이전 생성 계정은 전부 기본값 `ADMIN`으로 승계됩니다.
- `posts.author_username`은 작성자 username이며 nullable입니다(V14). null인 레거시 글은 관리자만 수정/삭제할 수 있습니다. 새 글은 작성 시점의 JWT subject(로그인 username)가 저장됩니다. 기존 레거시 글은 `V15`에서 `admin`으로 백필됩니다.
- `post_replies.author_username`은 댓글 작성자 username이며 nullable입니다(V16). AI 답변(`is_ai=true`)은 null이고, 일반 댓글은 작성 시점의 JWT subject가 저장됩니다. 기존 일반 댓글은 `V16`에서 `admin`으로 백필됩니다. 댓글 수정/삭제도 작성자 본인 또는 ADMIN만 가능하며, null인 레거시 일반 댓글은 ADMIN만 수정/삭제할 수 있습니다.

## 기본 관리자 계정

`V6__create_admins_table.sql`이 기본 관리자 계정을 시드합니다. 시드 계정은 로컬 확인용이며, 운영 노출 전에 반드시 비밀번호를 변경하거나 별도 관리자 계정으로 교체해야 합니다.

기본 계정은 ADMIN 역할을 가지며, 추가 계정 생성과 역할/비밀번호 관리, 계정 삭제는 ADMIN 전용 사용자 관리 API(docs/07)로 수행합니다. 마지막 남은 ADMIN은 삭제/강등할 수 없습니다.

비밀번호는 BCrypt hash로 저장됩니다. 운영 DB에서 변경할 때는 새 BCrypt hash를 만들어 `admins.password_hash`를 갱신합니다.

## 운영 점검 쿼리

EC2에서 PostgreSQL 컨테이너 접속:

```bash
docker exec -it yangyag-postgres psql -U <db-user> -d llm
```

스키마 확인:

```sql
select table_name
from information_schema.tables
where table_schema = 'llm'
order by table_name;
```

Flyway 이력 확인:

```sql
select installed_rank, version, description, success, installed_on
from llm.flyway_schema_history
order by installed_rank;
```

최근 게시글 확인:

```sql
select id, title, mode, created_at
from llm.posts
order by created_at desc
limit 10;
```

업로드 세션 누적 확인:

```sql
select status, count(*)
from llm.upload_sessions
group by status
order by status;
```

## 백업 기준

운영 백업은 DB dump와 Docker volume 백업을 함께 고려해야 합니다.

- DB: `pg_dump` 또는 볼륨 snapshot
- 첨부파일: `ubuntu_llm-back-attachments` volume
- 업로드 임시 파일: `ubuntu_llm-back-upload-sessions` volume. 일반적으로 복구 우선순위는 낮지만 장애 조사에는 필요할 수 있습니다.

예시:

```bash
docker exec yangyag-postgres pg_dump -U <db-user> -d llm > llm-$(date +%Y%m%d-%H%M%S).sql
```

secret이 노출되지 않게 dump 파일 보관 위치와 권한을 제한합니다.
