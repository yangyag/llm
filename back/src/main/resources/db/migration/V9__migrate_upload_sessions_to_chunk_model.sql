delete from upload_session_parts;
delete from upload_sessions;

alter table upload_sessions
    add column file_size_bytes bigint not null default 0,
    add column chunk_size_bytes bigint not null default 0,
    add column total_chunks integer not null default 1,
    add column file_sha256 varchar(64) not null default '0000000000000000000000000000000000000000000000000000000000000000';

alter table upload_sessions
    drop column total_parts;

alter table upload_sessions
    alter column file_size_bytes drop default,
    alter column chunk_size_bytes drop default,
    alter column total_chunks drop default,
    alter column file_sha256 drop default;

alter table upload_session_parts
    rename column part_number to chunk_number;
