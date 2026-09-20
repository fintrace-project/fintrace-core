ALTER TABLE t_accounts
    ADD COLUMN external_ref varchar(60);

ALTER TABLE t_categories
    ADD COLUMN external_ref varchar(60);

ALTER TABLE t_balance_anchors
    ADD COLUMN external_ref varchar(60);
