-- 사용자 레벨 도입: admins 테이블에 role 컬럼 추가.
-- 기존 계정(시드 admin 포함)은 전부 관리자로 승계한다.
alter table admins add column role varchar(20) not null default 'ADMIN';
alter table admins add constraint admins_role_check check (role in ('ADMIN', 'USER'));
