update family_users set role = 'FATHER' where role = 'ADMIN';
update family_users set role = 'FAMILY' where role = 'VIEWER';

alter table media_assets add column visibility varchar(32) not null default 'FAMILY';
alter table media_assets add column uploaded_by_role varchar(32);

update media_assets set visibility = 'FAMILY' where visibility is null;