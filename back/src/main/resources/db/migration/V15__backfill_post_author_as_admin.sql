-- 기존 게시글 작성자 백필: 작성자 미지정(레거시) 글은 전부 admin 소유로 처리한다.
-- V14가 author_username을 nullable로 추가하므로, 이 마이그레이션은 V14 이후 적용돼야 한다.
update posts set author_username = 'admin' where author_username is null;
