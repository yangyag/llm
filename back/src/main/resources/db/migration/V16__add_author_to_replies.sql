-- 댓글 작성자 기록. AI 답변(is_ai=true)은 작성자 없음(null) 유지.
alter table post_replies add column author_username varchar(100);

create index idx_post_replies_author_username on post_replies(author_username);

-- 기존 일반 댓글은 전부 admin 소유로 백필한다.
update post_replies set author_username = 'admin'
where author_username is null and is_ai = false;
