-- 게시글당 첨부파일 1개 제약을 풀어 다중 첨부(최대 개수는 애플리케이션에서 제한)를 허용한다.
-- 기존 idx_post_attachments_post_id 인덱스는 1:N 조회에 그대로 유효하므로 유지한다.
alter table post_attachments drop constraint if exists uk_post_attachments_post_id;
