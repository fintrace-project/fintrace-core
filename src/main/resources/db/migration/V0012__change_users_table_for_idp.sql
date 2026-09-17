ALTER TABLE t_users
    DROP CONSTRAINT uq_t_users_username;

DELETE from t_users
    WHERE external_id='stub:testuser';