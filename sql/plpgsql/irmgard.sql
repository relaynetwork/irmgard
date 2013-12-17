--
-- Irmgard: Postgres Data Replication Framework
--
-- See: https://github.com/rn-superg/irmgard
--

--CREATE SCHEMA IF NOT EXISTS irmgard;
-- TODO: convert all the DROP's to IF NOT EXISTS
DROP SCHEMA irmgard CASCADE;
CREATE SCHEMA irmgard;
COMMENT ON SCHEMA irmgard IS 'Irmgard: Postgres Data Replication Framework (https://github.com/rn-superg/irmgard)';

--
-- text_primary_key or int_primary_key may be filled in depending on the
-- primary key you have identified for the table being tracked.  Both will be
-- NULL for a TRUNCATE event, and the action will be 'T'.  In the TRUNCATE
-- case, all rows will have been eliminated, and no individual primary keys
-- will be tracked.
--
DROP TABLE IF EXISTS irmgard.row_changes;
CREATE TABLE irmgard.row_changes (
  event_id         bigserial primary key, 
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  schema_name      information_schema.sql_identifier NOT NULL,
  table_name       information_schema.sql_identifier NOT NULL,
  key_name         text NOT NULL,
  text_primary_key text,
  int_primary_key  bigint,
  action           TEXT NOT NULL CHECK (action IN ('I','D','U','T'))
);

COMMENT ON TABLE  irmgard.row_changes                  IS 'Row tracking for tracked tables.';
COMMENT ON COLUMN irmgard.row_changes.event_id         IS 'Unique identifier for DML event.';
COMMENT ON COLUMN irmgard.row_changes.created_at       IS 'Timestamp of event.';
COMMENT ON COLUMN irmgard.row_changes.schema_name      IS 'Schema of tracked table.';
COMMENT ON COLUMN irmgard.row_changes.table_name       IS 'Name of tracked table.';
COMMENT ON COLUMN irmgard.row_changes.text_primary_key IS 'Primary key of tracked row, if it was a TEXT value.';
COMMENT ON COLUMN irmgard.row_changes.int_primary_key  IS 'Primary key of tracked row, if it was an int value.';
COMMENT ON COLUMN irmgard.row_changes.action           IS 'The DML Operation that modified the row.';

-- TODO: convert to an upsert, using these indexes
-- use a functional index:
-- CREATE UNIQUE INDEX idx_row_changes_text_pk ON irmgard.row_changes (schema_name, table_name, text_primary_key)        WHERE int_primary_key  is NULL AND text_primary_key IS NOT NULL;
-- CREATE UNIQUE INDEX idx_row_changes_int_pk  ON irmgard.row_changes (schema_name, table_name, int_primary_key)         WHERE text_primary_key is NULL AND int_primary_key  IS NOT NULL;
-- CREATE UNIQUE INDEX idx_row_changes_no_pk   ON irmgard.row_changes (schema_name, table_name) WHERE text_primary_key is NULL AND int_primary_key IS NULL;

CREATE OR REPLACE FUNCTION irmgard.upsert_row_change(cache_rec irmgard.row_changes) RETURNS VOID AS $$
DECLARE
BEGIN
  -- TODO: convert this to an upsert

  INSERT INTO irmgard.row_changes
         (schema_name,
          table_name,
          key_name,
          text_primary_key,
          int_primary_key,
          action) 
  VALUES (cache_rec.schema_name,
          cache_rec.table_name,
          cache_rec.key_name,
          cache_rec.text_primary_key,
          cache_rec.int_primary_key,
          cache_rec.action);
  RETURN;
END;
$$
language 'plpgsql';

CREATE OR REPLACE FUNCTION irmgard.on_row_change_int_pk() RETURNS TRIGGER AS $$
DECLARE
  _c_rec      irmgard.row_changes;
  _c_pk       bigint;
  _q_txt      text;
BEGIN
  _c_rec.schema_name = TG_TABLE_SCHEMA;
  _c_rec.table_name  = TG_TABLE_NAME;
  _c_rec.key_name    = TG_ARGV[0];

  --RAISE NOTICE 'irmgard.on_row_change_int_pk[%]: key_name=%', TG_OP, _c_rec.key_name;

  IF TG_OP = 'INSERT'  THEN
    _q_txt = 'SELECT ($1).' || _c_rec.key_name;
    EXECUTE _q_txt INTO _c_rec.int_primary_key USING NEW;
    _c_rec.action = 'I';
  ELSIF TG_OP = 'UPDATE' THEN
    EXECUTE 'SELECT ($1).' || _c_rec.key_name INTO _c_rec.int_primary_key USING NEW;
    _c_rec.action = 'U';
  ELSIF TG_OP = 'DELETE' THEN
    EXECUTE 'SELECT ($1).' || _c_rec.key_name INTO _c_rec.int_primary_key USING OLD;
    _c_rec.action = 'D';
  ELSE
    raise notice 'irmgard.on_row_change[%]: ERROR unrecognized TG_OP', TG_OP;
  END IF;

  PERFORM irmgard.upsert_row_change(_c_rec);
  PERFORM pg_notify('irmgard', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME);

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  ELSE
    RETURN NEW;
  END IF;
END;
$$
language 'plpgsql';

CREATE OR REPLACE FUNCTION irmgard.on_row_change_text_pk() RETURNS TRIGGER AS $$
DECLARE
  _c_rec      irmgard.row_changes;
  _c_pk       bigint;
  _q_txt      text;
BEGIN
  _c_rec.schema_name = TG_TABLE_SCHEMA;
  _c_rec.table_name  = TG_TABLE_NAME;
  _c_rec.key_name    = TG_ARGV[0];

  --RAISE NOTICE 'irmgard.on_row_change_text_pk[%]: key_name=%', TG_OP, _c_rec.key_name;

  IF TG_OP = 'INSERT'  THEN
    _q_txt = 'SELECT ($1).' || _c_rec.key_name;
    EXECUTE _q_txt INTO _c_rec.text_primary_key USING NEW;
    _c_rec.action = 'I';
  ELSIF TG_OP = 'UPDATE' THEN
    EXECUTE 'SELECT ($1).' || _c_rec.key_name INTO _c_rec.text_primary_key USING NEW;
    _c_rec.action = 'U';
  ELSIF TG_OP = 'DELETE' THEN
    EXECUTE 'SELECT ($1).' || _c_rec.key_name INTO _c_rec.text_primary_key USING OLD;
    _c_rec.action = 'D';
  ELSE
    raise notice 'irmgard.on_row_change[%]: ERROR unrecognized TG_OP', TG_OP;
  END IF;

  PERFORM irmgard.upsert_row_change(_c_rec);
  PERFORM pg_notify('irmgard', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME);

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  ELSE
    RETURN NEW;
  END IF;
END;
$$
language 'plpgsql';

CREATE OR REPLACE FUNCTION irmgard.on_truncate_table() RETURNS TRIGGER AS $$
DECLARE
  _c_rec      irmgard.row_changes;
  _c_pk       bigint;
BEGIN
  _c_rec.schema_name = TG_TABLE_SCHEMA;
  _c_rec.table_name  = TG_TABLE_NAME;
  _c_rec.key_name    = TG_ARGV[0];
  _c_rec.action      = 'T';

  PERFORM irmgard.upsert_row_change(_c_rec);
  PERFORM pg_notify('irmgard', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME);
END;
$$
language 'plpgsql';

CREATE OR REPLACE FUNCTION irmgard.enable_tracking_int_pk(table_name regclass, key_name text) RETURNS void AS $body$
DECLARE
  _q_txt text;
BEGIN
  _q_txt = 'DROP TRIGGER IF EXISTS irmgard_row_tracking_trigger ON ' || table_name;
  --RAISE NOTICE 'irmgard.enable_tracking_int_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'CREATE TRIGGER irmgard_row_tracking_trigger AFTER INSERT OR UPDATE OR DELETE ON ' || 
           table_name || 
           ' FOR EACH ROW EXECUTE PROCEDURE irmgard.on_row_change_int_pk(''' || quote_ident(key_name) || ''')';
  --RAISE NOTICE 'irmgard.enable_tracking_int_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'DROP TRIGGER IF EXISTS irmgard_truncate_tracking_trigger ON ' || table_name;
  --RAISE NOTICE 'irmgard.enable_tracking_int_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'CREATE TRIGGER irmgard_truncate_tracking_trigger AFTER TRUNCATE ON ' || 
           table_name || 
           ' FOR EACH STATEMENT EXECUTE PROCEDURE irmgard.on_truncate_table()';
  --RAISE NOTICE 'irmgard.enable_tracking_int_pk: %', _q_txt;
  EXECUTE _q_txt;

  RETURN;
END;
$body$
LANGUAGE plpgsql
SECURITY DEFINER;
-- SET search_path = pg_catalog, public;

CREATE OR REPLACE FUNCTION irmgard.enable_tracking_text_pk(table_name regclass, key_name text) RETURNS void AS $body$
DECLARE
  _q_txt text;
BEGIN
  _q_txt = 'DROP TRIGGER IF EXISTS irmgard_row_tracking_trigger ON ' || table_name;
  --RAISE NOTICE 'irmgard.enable_tracking_text_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'CREATE TRIGGER irmgard_row_tracking_trigger AFTER INSERT OR UPDATE OR DELETE ON ' || 
           table_name || 
           ' FOR EACH ROW EXECUTE PROCEDURE irmgard.on_row_change_text_pk(''' || quote_ident(key_name) || ''')';
  --RAISE NOTICE 'irmgard.enable_tracking_text_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'DROP TRIGGER IF EXISTS irmgard_truncate_tracking_trigger ON ' || table_name;
  --RAISE NOTICE 'irmgard.enable_tracking_text_pk: %', _q_txt;
  EXECUTE _q_txt;

  _q_txt = 'CREATE TRIGGER irmgard_truncate_tracking_trigger AFTER TRUNCATE ON ' || 
           table_name || 
           ' FOR EACH STATEMENT EXECUTE PROCEDURE irmgard.on_truncate_table()';
  --RAISE NOTICE 'irmgard.enable_tracking_text_pk: %', _q_txt;
  EXECUTE _q_txt;

  RETURN;
END;
$body$
LANGUAGE plpgsql
SECURITY DEFINER;
-- SET search_path = pg_catalog, public;

COMMENT ON FUNCTION irmgard.enable_tracking_int_pk(table_name regclass, key_name text) IS $body$
irmgard.enable_tracking_int_pk does the following:

Parameters are:

param 0: boolean
param 1: text[]
$body$;


DROP TABLE IF EXISTS irmgard.process_log;
CREATE TABLE irmgard.process_log (
  event_id       bigserial primary key, 
  started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  ended_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  hostname       text   NOT NULL,
  num_processed  bigint NOT NULL,
  elapsed_ms     bigint NOT NULL
);

COMMENT ON TABLE  irmgard.process_log                 IS 'Tracks execution of the Irmgard Clojure Agent.';
COMMENT ON COLUMN irmgard.process_log.event_id        IS 'Unique identifier for DML event.';
COMMENT ON COLUMN irmgard.process_log.started_at      IS 'Timestamp when Agent began processing the NOTIFY.';
COMMENT ON COLUMN irmgard.process_log.ended_at        IS 'Timestamp when Agent completed processing the NOTIFY.';
COMMENT ON COLUMN irmgard.process_log.hostname        IS 'Hostname where Agent was running.';
COMMENT ON COLUMN irmgard.process_log.num_processed   IS 'Count of row_changes that were processed by the agent.';
COMMENT ON COLUMN irmgard.process_log.elapsed_ms      IS 'Elapsed time (ms) it took the Agent to process the NOTIFY.';

-- Optional irmgard.stats table to track activity of tracking functions.
DROP TABLE IF EXISTS irmgard.stats;
CREATE TABLE irmgard.stats (
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  name           TEXT      NOT NULL,
  stat           bigint    NOT NULL
);

COMMENT ON TABLE  irmgard.stats                 IS 'Stats tracking for the tracking functions.';
COMMENT ON COLUMN irmgard.stats.updated_at      IS 'Last time the stat row was updated.';
COMMENT ON COLUMN irmgard.stats.name            IS 'Name of the stat.';
COMMENT ON COLUMN irmgard.stats.stat            IS 'Value of the stat.';
