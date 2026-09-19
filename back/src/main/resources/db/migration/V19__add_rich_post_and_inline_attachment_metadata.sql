alter table posts add column body_format varchar(30) not null default 'PLAIN_TEXT';
alter table posts add column body_document text;
alter table posts add constraint ck_posts_body_format
    check (body_format in ('PLAIN_TEXT', 'TIPTAP_JSON'));
alter table posts add constraint ck_posts_body_format_document
    check (
        (body_format = 'PLAIN_TEXT' and body_document is null)
        or (body_format = 'TIPTAP_JSON' and body_document is not null)
    );

alter table post_attachments add column attachment_kind varchar(30) not null default 'DOWNLOAD';
alter table post_attachments add column inline_key uuid;
alter table post_attachments add constraint ck_post_attachments_kind
    check (attachment_kind in ('DOWNLOAD', 'INLINE_IMAGE'));
alter table post_attachments add constraint ck_post_attachments_kind_inline_key
    check (
        (attachment_kind = 'DOWNLOAD' and inline_key is null)
        or (attachment_kind = 'INLINE_IMAGE' and inline_key is not null)
    );
create unique index uk_post_attachments_post_inline_key
    on post_attachments(post_id, inline_key)
    where inline_key is not null;
