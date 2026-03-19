create table post_attachments (
    id bigserial primary key,
    post_id bigint not null references posts(id) on delete cascade,
    original_filename varchar(255) not null,
    stored_filename varchar(255) not null,
    storage_path varchar(1000) not null,
    content_type varchar(255),
    size bigint not null,
    created_at timestamp with time zone not null,
    constraint uk_post_attachments_post_id unique (post_id)
);

create index idx_post_attachments_post_id on post_attachments(post_id);
