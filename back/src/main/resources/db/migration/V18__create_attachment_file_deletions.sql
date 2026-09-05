create table attachment_file_deletions (
    storage_path varchar(1000) primary key,
    created_at timestamp with time zone not null
);
create index idx_attachment_file_deletions_created_at on attachment_file_deletions(created_at);
