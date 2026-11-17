-- One row per (bucket, currency): the income, expense and net of the operations dated in the bucket, and how many.
-- A bucket with no account in the selection still answers one row, its currency NULL.
DROP FUNCTION IF EXISTS fn_cashflow_series(uuid, timestamp, timestamp, text, timestamp, uuid[], boolean);

CREATE
OR REPLACE FUNCTION fn_cashflow_series(
    p_workspace_id uuid,
    p_first_start timestamp,
    p_last_start timestamp,
    p_unit text,                    -- 'day' | 'week' | 'month' | 'year': the step, and date_trunc's field
    p_until timestamp,              -- the requested `to` + 1 day
    p_account_ids uuid[],           -- NULL for all accounts
    p_include_archived boolean
) RETURNS TABLE (bucket_start date, currency char(3), income numeric(19, 4), expense numeric(19, 4),
                 net numeric(19, 4), operations bigint)
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS
$$
WITH accounts AS (SELECT id, currency
                  FROM t_accounts
                  WHERE workspace_id = p_workspace_id
                    AND (p_include_archived OR NOT archived)
                    AND (p_account_ids IS NULL OR id = ANY (p_account_ids))),
     currencies AS (SELECT DISTINCT currency FROM accounts),
     buckets AS (SELECT b AS starts
                 FROM generate_series(p_first_start, p_last_start, ('1 ' || p_unit)::interval) AS b),
     -- Signed amounts (expenses negative): income as it is, expense negated to a magnitude, net the plain sum
     flows AS (SELECT date_trunc(p_unit, o.occurred_at)                AS starts,
                      a.currency,
                      SUM(o.amount) FILTER (WHERE o.kind = 'INCOME')   AS income,
                      -SUM(o.amount) FILTER (WHERE o.kind = 'EXPENSE') AS expense,
                      SUM(o.amount)                                    AS net,
                      COUNT(*)                                         AS operations
               FROM t_operations o
                        JOIN accounts a ON a.id = o.account_id
               WHERE o.workspace_id = p_workspace_id
                 AND o.kind <> 'TRANSFER'
                 AND o.occurred_at >= p_first_start
                 AND o.occurred_at < p_until
               GROUP BY 1, 2)
SELECT bk.starts::date,
       c.currency,
       COALESCE(f.income, 0)::numeric(19, 4),
       COALESCE(f.expense, 0)::numeric(19, 4),
       COALESCE(f.net, 0)::numeric(19, 4),
       COALESCE(f.operations, 0)
FROM buckets bk
         LEFT JOIN currencies c ON TRUE
         LEFT JOIN flows f ON f.starts = bk.starts AND f.currency = c.currency
ORDER BY bk.starts, c.currency;
$$;
