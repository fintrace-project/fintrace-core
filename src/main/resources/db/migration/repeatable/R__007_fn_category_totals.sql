-- The whole branch of one kind, archived categories included: a row per (category, currency) with the magnitude of
-- the operations booked to that category itself in the period, its subcategories not rolled in. A category with
-- nothing booked to it answers one row with NULL currency and self_total, so the caller still has its parent_id.
DROP FUNCTION IF EXISTS fn_category_totals(uuid, text, timestamp, timestamp, uuid[], boolean);
DROP FUNCTION IF EXISTS fn_category_totals(uuid, text, timestamp, timestamp, uuid[], char(3), boolean);

CREATE
OR REPLACE FUNCTION fn_category_totals(
    p_workspace_id uuid,
    p_kind text,                    -- 'INCOME' | 'EXPENSE'
    p_from timestamp,               -- the requested `from`, at 00:00
    p_until timestamp,              -- the requested `to` + 1 day
    p_account_ids uuid[],           -- NULL for all accounts
    p_currency char(3),             -- NULL for every currency
    p_include_archived boolean
) RETURNS TABLE (category_id uuid, parent_id uuid, currency char(3), self_total numeric(19, 4))
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH accounts AS (SELECT id, currency
                  FROM t_accounts
                  WHERE workspace_id = p_workspace_id
                    AND (p_include_archived OR NOT archived)
                    AND (p_account_ids IS NULL OR id = ANY (p_account_ids))
                    AND (p_currency IS NULL OR currency = p_currency)),
     sums AS (
         -- One kind carries one sign, so ABS of the sum is the magnitude, as cashflow answers it
         SELECT o.category_id,
                a.currency,
                ABS(SUM(o.amount))::numeric(19, 4) AS self_total
         FROM t_operations o
                  JOIN accounts a ON a.id = o.account_id
         WHERE o.workspace_id = p_workspace_id
           AND o.kind = p_kind
           AND o.occurred_at >= p_from
           AND o.occurred_at < p_until
         GROUP BY o.category_id, a.currency
         HAVING SUM(o.amount) <> 0)
SELECT c.id,
       c.parent_id,
       s.currency,
       s.self_total
FROM t_categories c
         LEFT JOIN sums s ON s.category_id = c.id
WHERE c.workspace_id = p_workspace_id
  AND c.kind = p_kind
ORDER BY c.id, s.currency;
$$;
