alter table post_replies
    add column is_ai boolean not null default false,
    add column ai_provider varchar(32);
