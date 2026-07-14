create table family_users (
    id uuid primary key,
    kakao_id varchar(64) not null unique,
    nickname varchar(120) not null,
    profile_image_url varchar(1024),
    role varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    approved_at timestamp with time zone,
    last_login_at timestamp with time zone
);

create index idx_family_users_role on family_users (role);