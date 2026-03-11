create table posts (
    id bigserial primary key,
    title varchar(200) not null,
    body varchar(20000) not null,
    password_hash varchar(120) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table post_replies (
    id bigserial primary key,
    post_id bigint not null references posts(id) on delete cascade,
    body varchar(20000) not null,
    password_hash varchar(120) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_posts_created_at on posts(created_at desc);
create index idx_post_replies_post_id on post_replies(post_id);
