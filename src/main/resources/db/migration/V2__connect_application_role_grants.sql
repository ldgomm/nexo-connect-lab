DO $role_check$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = 'nexo_connect_lab_app'
          AND NOT rolsuper
          AND NOT rolcreatedb
          AND NOT rolcreaterole
          AND NOT rolreplication
          AND NOT rolbypassrls
    ) THEN
        RAISE EXCEPTION 'required least-privilege role nexo_connect_lab_app is missing or unsafe';
    END IF;
END
$role_check$;

GRANT CONNECT ON DATABASE nexo_connect_lab TO nexo_connect_lab_app;
GRANT USAGE ON SCHEMA connect TO nexo_connect_lab_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA connect TO nexo_connect_lab_app;
GRANT SELECT ON TABLE public.flyway_schema_history TO nexo_connect_lab_app;

ALTER DEFAULT PRIVILEGES FOR ROLE nexo_connect_lab IN SCHEMA connect
    GRANT SELECT, INSERT, UPDATE ON TABLES TO nexo_connect_lab_app;
