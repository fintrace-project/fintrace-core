-- A feed's items at their position: newest first is `occurred_at, item_rank, sort_id`, all
-- DESC, so one row comparison pages it. The workspace's feed with a null account; an account's adds
-- its anchors and their differences. Paging, the period and kinds are the caller's, over the columns
-- returned. A transfer is one row from its source leg, the only negative one.
-- Flyway runs repeatables by name, so fn_unexplained_difference_of may not exist yet: the body is
-- checked on first call instead, for this script's transaction only.
SET LOCAL check_function_bodies = false;

DROP FUNCTION IF EXISTS fn_load_feed(uuid, uuid);

CREATE
OR REPLACE FUNCTION fn_load_feed(
    p_workspace_id uuid,
    p_account_id uuid
) RETURNS TABLE (
    item_type           text,
    occurred_at         timestamp,
    item_rank           smallint,
    sort_id             uuid,
    recorded_at         timestamp,
    comment             text,
    account_id          uuid,
    currency            text,
    category_id         uuid,
    kind                text,
    amount              numeric(19, 4),
    side                text,
    source_operation_id uuid,
    source_account_id   uuid,
    source_currency     text,
    source_amount       numeric(19, 4),
    target_operation_id uuid,
    target_account_id   uuid,
    target_currency     text,
    target_amount       numeric(19, 4),
    anchor_value        numeric(19, 4)
)
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH anchors AS (SELECT a.id,
                        a.value,
                        a.occurred_at,
                        a.recorded_at,
                        fn_unexplained_difference_of(a.workspace_id, a.id) AS difference
                 FROM t_balance_anchors a
                 WHERE a.workspace_id = p_workspace_id
                   AND a.account_id = p_account_id)
SELECT 'OPERATION'::text,
       o.occurred_at,
       0::smallint,
       o.id,
       o.recorded_at,
       o.comment::text,
       o.account_id,
       acc.currency::text,
       o.category_id,
       o.kind::text,
       o.amount,
       NULL::text,
       NULL::uuid,
       NULL::uuid,
       NULL::text,
       NULL::numeric(19, 4),
       NULL::uuid,
       NULL::uuid,
       NULL::text,
       NULL::numeric(19, 4),
       NULL::numeric(19, 4)
FROM t_operations o
         JOIN t_accounts acc ON acc.workspace_id = o.workspace_id AND acc.id = o.account_id
WHERE o.workspace_id = p_workspace_id
  AND o.transfer_id IS NULL
  AND (p_account_id IS NULL OR o.account_id = p_account_id)

UNION ALL

SELECT 'TRANSFER',
       s.occurred_at,
       0,
       s.transfer_id,
       s.recorded_at,
       s.comment,
       p_account_id,
       CASE p_account_id WHEN s.account_id THEN sa.currency WHEN t.account_id THEN ta.currency END,
       NULL,
       NULL,
       CASE p_account_id WHEN s.account_id THEN s.amount WHEN t.account_id THEN t.amount END,
       CASE p_account_id WHEN s.account_id THEN 'SOURCE' WHEN t.account_id THEN 'TARGET' END,
       s.id,
       s.account_id,
       sa.currency,
       s.amount,
       t.id,
       t.account_id,
       ta.currency,
       t.amount,
       NULL
FROM t_operations s
         JOIN t_operations t ON t.workspace_id = s.workspace_id AND t.id = s.counterpart_id
         JOIN t_accounts sa ON sa.workspace_id = s.workspace_id AND sa.id = s.account_id
         JOIN t_accounts ta ON ta.workspace_id = t.workspace_id AND ta.id = t.account_id
WHERE s.workspace_id = p_workspace_id
  AND s.transfer_id IS NOT NULL
  AND s.amount < 0
  AND (p_account_id IS NULL OR p_account_id IN (s.account_id, t.account_id))

UNION ALL

SELECT 'BALANCE_ANCHOR',
       a.occurred_at,
       2,
       a.id,
       a.recorded_at,
       NULL,
       p_account_id,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       a.value
FROM anchors a

UNION ALL

SELECT 'DIFFERENCE',
       a.occurred_at,
       1,
       a.id,
       NULL,
       NULL,
       p_account_id,
       NULL,
       NULL,
       NULL,
       a.difference,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL,
       NULL
FROM anchors a
WHERE a.difference IS NOT NULL
  AND a.difference <> 0;
$$;
