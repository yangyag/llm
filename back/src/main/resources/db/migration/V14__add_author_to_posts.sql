-- 게시글 작성자 기록. 기존 글은 null(관리자만 수정/삭제 가능).
alter table posts add column author_username varchar(100);

create index idx_posts_author_username on posts(author_username);
