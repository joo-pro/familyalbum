create table push_subscriptions (
    id uuid primary key,
    endpoint varchar(2048) not null unique,
    p256dh varchar(512) not null,
    auth varchar(256) not null,
    user_agent varchar(512),
    active boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_push_subscriptions_active on push_subscriptions (active);