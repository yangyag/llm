create table upload_sessions (
    id uuid primary key,
    archive_name varchar(255) not null,
    total_parts integer not null,
    status varchar(32) not null,
    created_by varchar(100) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    expires_at timestamp with time zone not null
);

create table upload_session_parts (
    id bigserial primary key,
    session_id uuid not null references upload_sessions(id) on delete cascade,
    part_number integer not null,
    original_filename varchar(255) not null,
    stored_filename varchar(255) not null,
    storage_path varchar(1000) not null,
    size bigint not null,
    created_at timestamp with time zone not null,
    constraint uk_upload_session_parts_session_part unique (session_id, part_number)
);

create index idx_upload_sessions_expires_at on upload_sessions(expires_at);
create index idx_upload_session_parts_session_id on upload_session_parts(session_id);
