create table admins (
    id bigserial primary key,
    username varchar(100) not null unique,
    password_hash varchar(255) not null,
    created_at timestamp not null default now()
);

-- default admin account (password: admin, BCrypt hash)
insert into admins (username, password_hash)
values ('admin', '$2a$10$Sk24Km7ykwB8aYJq4cjDEOSKf5ZOrX8DPcuj9TnjZlYPmfPUahkES');
