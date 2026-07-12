alter table media_assets
    add column preview_object_key varchar(1024),
    add column preview_content_type varchar(255);

create index idx_media_assets_preview_object_key on media_assets (preview_object_key);