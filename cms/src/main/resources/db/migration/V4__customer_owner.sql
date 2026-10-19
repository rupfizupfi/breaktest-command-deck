-- Customers become owner-scoped like projects (owner = null means shared with every user). Portable
-- SQL: runs on PostgreSQL and on H2 in MODE=PostgreSQL.

alter table customer add column owner_id bigint;
alter table customer add constraint fk_customer_owner foreign key (owner_id) references application_user (id);

-- Backfill. A customer takes the owner of its referencing projects when that owner is unique: at
-- least one project, every one of them owned (no shared project) and all by the same user. Anything
-- else stays null (shared): projects of different owners, any shared project among them, and a
-- customer no project references (deliberately shared rather than guessed). This keeps the rule
-- "a shared project only references a shared customer" true for every existing row.
update customer
set owner_id = (select min(p.owner_id) from project p where p.customer_id = customer.id)
where (select count(*) from project p where p.customer_id = customer.id and p.owner_id is null) = 0
  and (select count(distinct p.owner_id) from project p where p.customer_id = customer.id) = 1;
