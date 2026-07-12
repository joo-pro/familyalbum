create extension if not exists pgcrypto;

create table media_assets (
    id uuid primary key default gen_random_uuid(),
    original_object_key varchar(1024) not null unique,
    original_filename varchar(512) not null,
    content_type varchar(255) not null,
    media_type varchar(32) not null,
    byte_size bigint not null,
    upload_status varchar(32) not null,
    captured_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_media_assets_created_at on media_assets (created_at desc);
create index idx_media_assets_media_type on media_assets (media_type);
