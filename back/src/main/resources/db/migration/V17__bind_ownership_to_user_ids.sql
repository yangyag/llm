-- Names remain display snapshots; authorization uses the immutable account ID.
alter table posts add column author_user_id bigint references admins(id) on delete set null;
alter table post_replies add column author_user_id bigint references admins(id) on delete set null;
alter table upload_sessions add column created_by_user_id bigint references admins(id) on delete set null;

-- A newer account with a reused name must not inherit older content or uploads.
update posts p set author_user_id = a.id
from admins a
where p.author_username = a.username and a.created_at <= p.created_at;

update post_replies r set author_user_id = a.id
from admins a
where r.author_username = a.username and a.created_at <= r.created_at and r.is_ai = false;

update upload_sessions s set created_by_user_id = a.id
from admins a
where s.created_by = a.username and a.created_at <= s.created_at;

create index idx_posts_author_user_id on posts(author_user_id);
create index idx_post_replies_author_user_id on post_replies(author_user_id);
create index idx_upload_sessions_created_by_user_id on upload_sessions(created_by_user_id);
