alter table media_assets
    add column content_hash varchar(64);

create unique index ux_media_assets_content_hash_uploaded
    on media_assets (content_hash)
    where content_hash is not null and upload_status = 'UPLOADED';