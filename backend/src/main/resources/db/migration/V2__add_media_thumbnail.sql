alter table media_assets
    add column thumbnail_object_key varchar(1024);

create index idx_media_assets_thumbnail_object_key on media_assets (thumbnail_object_key);