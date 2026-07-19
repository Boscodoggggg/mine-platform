#!/usr/bin/env bash
set -euo pipefail

reader_user="${TIMESCALE_READER_USER:-mine_ts_reader}"
replay_user="${TIMESCALE_REPLAY_USER:-mine_ts_replay}"
writer_user="${POSTGRES_USER:-mine_ts}"
if [[ ! "${reader_user}" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
  echo "TimescaleDB 只读用户名格式不合法" >&2
  exit 1
fi
if [[ ! "${replay_user}" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
  echo "TimescaleDB 空间重放用户名格式不合法" >&2
  exit 1
fi
if [[ "${replay_user}" == "${writer_user}" || "${replay_user}" == "postgres"
      || "${replay_user}" == "${reader_user}" ]]; then
  echo "TimescaleDB 空间重放账号必须独立于写入、只读和 postgres 账号" >&2
  exit 1
fi
if [[ "${reader_user}" == "${writer_user}" || "${reader_user}" == "postgres" ]]; then
  echo "TimescaleDB 只读账号不得与写入账号或 postgres 相同" >&2
  exit 1
fi

export MINE_TIMESCALE_READER_PASSWORD
MINE_TIMESCALE_READER_PASSWORD="$(< /run/secrets/timescale_reader_password)"
if [[ -z "${MINE_TIMESCALE_READER_PASSWORD}" ]]; then
  echo "TimescaleDB 只读密码不能为空" >&2
  exit 1
fi
export MINE_TIMESCALE_REPLAY_PASSWORD
MINE_TIMESCALE_REPLAY_PASSWORD="$(< /run/secrets/timescale_replay_password)"
if [[ -z "${MINE_TIMESCALE_REPLAY_PASSWORD}" ]]; then
  echo "TimescaleDB 空间重放密码不能为空" >&2
  exit 1
fi

if [[ -f /run/secrets/timescale_password ]]; then
  export PGPASSWORD
  PGPASSWORD="$(< /run/secrets/timescale_password)"
fi

psql_arguments=(
  --set=ON_ERROR_STOP=1
  --username "${POSTGRES_USER}"
  --dbname "${POSTGRES_DB}"
  --variable="reader_user=${reader_user}"
  --variable="replay_user=${replay_user}"
)
if [[ -n "${PGHOST:-}" ]]; then
  psql_arguments+=(--host "${PGHOST}")
fi

psql "${psql_arguments[@]}" <<'SQL'
\getenv reader_password MINE_TIMESCALE_READER_PASSWORD
\getenv replay_password MINE_TIMESCALE_REPLAY_PASSWORD

SELECT EXISTS (
    SELECT 1
    FROM pg_roles role_data
    WHERE role_data.rolname = :'reader_user'
      AND COALESCE(shobj_description(role_data.oid, 'pg_authid'), '')
          <> 'mine-platform:timescale-reader:v1'
) AS reader_unmanaged \gset

\if :reader_unmanaged
\warn 'TimescaleDB 同名账号不是平台托管的只读角色，拒绝接管'
SELECT 1 / 0 AS unmanaged_reader_role;
\endif

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'reader_user', :'reader_password')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'reader_user'
) \gexec
SELECT format(
    'COMMENT ON ROLE %I IS %L',
    :'reader_user', 'mine-platform:timescale-reader:v1'
) \gexec

SELECT EXISTS (
    SELECT 1
    FROM pg_class object_class
    JOIN pg_namespace namespace ON namespace.oid = object_class.relnamespace
    JOIN pg_roles role_data ON role_data.oid = object_class.relowner
    WHERE role_data.rolname = :'reader_user'
      AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
    UNION ALL
    SELECT 1
    FROM pg_namespace namespace
    JOIN pg_roles role_data ON role_data.oid = namespace.nspowner
    WHERE role_data.rolname = :'reader_user'
      AND namespace.nspname NOT LIKE 'pg_%'
      AND namespace.nspname <> 'information_schema'
    UNION ALL
    SELECT 1
    FROM pg_proc procedure_data
    JOIN pg_namespace namespace ON namespace.oid = procedure_data.pronamespace
    JOIN pg_roles role_data ON role_data.oid = procedure_data.proowner
    WHERE role_data.rolname = :'reader_user'
      AND namespace.nspname NOT LIKE 'pg_%'
      AND namespace.nspname <> 'information_schema'
    UNION ALL
    SELECT 1
    FROM pg_type type_data
    JOIN pg_namespace namespace ON namespace.oid = type_data.typnamespace
    JOIN pg_roles role_data ON role_data.oid = type_data.typowner
    WHERE role_data.rolname = :'reader_user'
      AND namespace.nspname NOT LIKE 'pg_%'
      AND namespace.nspname <> 'information_schema'
    UNION ALL
    SELECT 1
    FROM pg_largeobject_metadata object_data
    JOIN pg_roles role_data ON role_data.oid = object_data.lomowner
    WHERE role_data.rolname = :'reader_user'
    UNION ALL
    SELECT 1
    FROM pg_database database_data
    JOIN pg_roles role_data ON role_data.oid = database_data.datdba
    WHERE role_data.rolname = :'reader_user'
      AND database_data.datname = current_database()
) AS reader_owns_objects \gset
\if :reader_owns_objects
\warn 'TimescaleDB 只读账号已拥有业务对象，拒绝自动收权，请先迁移对象所有权'
SELECT 1 / 0 AS unsafe_reader_role;
\endif

SELECT format(
    'ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'reader_user', :'reader_password'
) \gexec
SELECT format('REVOKE %I FROM %I', granted_role.rolname, :'reader_user')
FROM pg_auth_members membership
JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
JOIN pg_roles member_role ON member_role.oid = membership.member
WHERE member_role.rolname = :'reader_user' \gexec
SELECT format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I', current_database(), :'reader_user') \gexec
SELECT format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I', namespace.nspname, :'reader_user')
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM %I',
    namespace.nspname, :'reader_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM %I',
    namespace.nspname, :'reader_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA %I FROM %I',
    namespace.nspname, :'reader_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'reader_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'reader_user') \gexec
SELECT format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', :'reader_user') \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I',
    :'reader_user'
) \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO %I',
    :'reader_user'
) \gexec

SELECT EXISTS (
    SELECT 1
    FROM pg_roles role_data
    WHERE role_data.rolname = :'replay_user'
      AND COALESCE(shobj_description(role_data.oid, 'pg_authid'), '')
          <> 'mine-platform:timescale-spatial-replay:v1'
) AS replay_unmanaged \gset

\if :replay_unmanaged
\warn 'TimescaleDB 同名账号不是平台托管的空间重放角色，拒绝接管'
SELECT 1 / 0 AS unmanaged_replay_role;
\endif

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'replay_user', :'replay_password')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'replay_user'
) \gexec
SELECT format(
    'COMMENT ON ROLE %I IS %L',
    :'replay_user', 'mine-platform:timescale-spatial-replay:v1'
) \gexec

SELECT EXISTS (
    SELECT 1
    FROM pg_class object_class
    JOIN pg_namespace namespace ON namespace.oid = object_class.relnamespace
    JOIN pg_roles role_data ON role_data.oid = object_class.relowner
    WHERE role_data.rolname = :'replay_user'
      AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
    UNION ALL
    SELECT 1
    FROM pg_namespace namespace
    JOIN pg_roles role_data ON role_data.oid = namespace.nspowner
    WHERE role_data.rolname = :'replay_user'
      AND namespace.nspname NOT LIKE 'pg_%'
      AND namespace.nspname <> 'information_schema'
    UNION ALL
    SELECT 1
    FROM pg_proc procedure_data
    JOIN pg_namespace namespace ON namespace.oid = procedure_data.pronamespace
    JOIN pg_roles role_data ON role_data.oid = procedure_data.proowner
    WHERE role_data.rolname = :'replay_user'
      AND namespace.nspname NOT LIKE 'pg_%'
      AND namespace.nspname <> 'information_schema'
    UNION ALL
    SELECT 1
    FROM pg_database database_data
    JOIN pg_roles role_data ON role_data.oid = database_data.datdba
    WHERE role_data.rolname = :'replay_user'
      AND database_data.datname = current_database()
) AS replay_owns_objects \gset
\if :replay_owns_objects
\warn 'TimescaleDB 空间重放账号已拥有业务对象，拒绝自动收权，请先迁移对象所有权'
SELECT 1 / 0 AS unsafe_replay_role;
\endif

SELECT format(
    'ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'replay_user', :'replay_password'
) \gexec
SELECT format('REVOKE %I FROM %I', granted_role.rolname, :'replay_user')
FROM pg_auth_members membership
JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
JOIN pg_roles member_role ON member_role.oid = membership.member
WHERE member_role.rolname = :'replay_user' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON DATABASE %I FROM %I', current_database(), :'replay_user'
) \gexec
SELECT format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I', namespace.nspname, :'replay_user')
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM %I',
    namespace.nspname, :'replay_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM %I',
    namespace.nspname, :'replay_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA %I FROM %I',
    namespace.nspname, :'replay_user'
)
FROM pg_namespace namespace
WHERE namespace.nspname NOT LIKE 'pg_%'
  AND namespace.nspname <> 'information_schema' \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'replay_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'replay_user') \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I',
    :'replay_user'
) \gexec
SELECT format(
    'ALTER DATABASE %I SET mine.spatial_replay_role TO %L',
    current_database(), :'replay_user'
) \gexec
SELECT format(
    'GRANT SELECT, INSERT ON TABLE spatial_replay_request TO %I', :'replay_user'
)
WHERE to_regclass('public.spatial_replay_request') IS NOT NULL \gexec
SELECT format(
    'GRANT SELECT ON TABLE spatial_replay_active_zone_snapshot TO %I',
    :'replay_user'
)
WHERE to_regclass('public.spatial_replay_active_zone_snapshot') IS NOT NULL \gexec
SQL

unset MINE_TIMESCALE_READER_PASSWORD
unset MINE_TIMESCALE_REPLAY_PASSWORD
unset PGPASSWORD || true
