-- Brings databases ddl-auto built to V1's enum columns: it froze each enum's constants when it created
-- the column (a CHECK on PostgreSQL, a native ENUM on H2), so a constant added later failed the insert.
-- No-op on a database V1 built.
alter table test_result drop constraint if exists test_result_run_status_check;
alter table user_roles drop constraint if exists user_roles_roles_check;
alter table test_result alter column run_status set data type varchar(32);
alter table user_roles alter column roles set data type varchar(255);
