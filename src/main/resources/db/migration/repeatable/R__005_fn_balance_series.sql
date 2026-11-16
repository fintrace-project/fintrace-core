-- One row per (bucket, currency): each account's balance at its bucket's end, summed per currency.
-- A bucket with no account in the selection still answers one row, its currency NULL.
DROP FUNCTION IF EXISTS fn_balance_series(uuid, timestamp, timestamp, interval, timestamp, uuid[], boolean);

CREATE
OR REPLACE FUNCTION fn_balance_series(
    p_workspace_id uuid,
    p_first_start timestamp,
    p_last_start timestamp,
    p_step interval,
    p_until timestamp,              -- the requested `to` + 1 day
    p_account_ids uuid[],           -- NULL for all accounts
    p_include_archived boolean
) RETURNS TABLE (bucket_start date, currency char(3), total numeric(19, 4))
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH buckets AS (SELECT b                          AS starts,
                        LEAST(b + p_step, p_until) AS ends
                 FROM generate_series(p_first_start, p_last_start, p_step) AS b),
     accounts AS (SELECT id, currency
                  FROM t_accounts
                  WHERE workspace_id = p_workspace_id
                    AND (p_include_archived OR NOT archived)
                    AND (p_account_ids IS NULL OR id = ANY (p_account_ids)))
SELECT bk.starts::date,
       a.currency,
       SUM(fn_balance_of(p_workspace_id, a.id, bk.ends))::numeric(19, 4)
FROM buckets bk
         LEFT JOIN accounts a ON TRUE
GROUP BY bk.starts, a.currency
ORDER BY bk.starts, a.currency;
$$;
