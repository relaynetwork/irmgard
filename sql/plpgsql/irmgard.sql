--
-- Irmgard: Postgres Data Replication Framework
--
-- See: https://github.com/rn-superg/irmgard
--

CREATE SCHEMA irmgard;
COMMENT ON SCHEMA irmgard IS 'Irmgard: Postgres Data Replication Framework (https://github.com/rn-superg/irmgard)';

--
-- text_primary_key or int_primary_key may be filled in depending on the
-- primary key you have identified for the table being tracked.  Both will be
-- NULL for a TRUNCATE event, and the action will be 'T'.  In the TRUNCATE
-- case, all rows will have been eliminated, and no individual primary keys
-- will be tracked.
--
CREATE TABLE irmgard.row_changes (
  event_id    bigserial primary key, 
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  schema_name information_schema.sql_identifier NOT NULL,
  table_name  information_schema.sql_identifier NOT NULL,
  text_primary_key text,
  int_primary_key  bigint,
  action      TEXT NOT NULL CHECK (action IN ('I','D','U','T'))
);

COMMENT ON TABLE  irmgard.row_changes                  IS 'Row tracking for tracked tables.';
COMMENT ON COLUMN irmgard.row_changes.event_id         IS 'Unique identifier for DML event.';
COMMENT ON COLUMN irmgard.row_changes.created_at       IS 'Timestamp of event.';
COMMENT ON COLUMN irmgard.row_changes.schema_name      IS 'Schema of tracked table.';
COMMENT ON COLUMN irmgard.row_changes.table_name       IS 'Name of tracked table.';
COMMENT ON COLUMN irmgard.row_changes.text_primary_key IS 'Primary key of tracked row, if it was a TEXT value.';
COMMENT ON COLUMN irmgard.row_changes.int_primary_key  IS 'Primary key of tracked row, if it was an int value.';
COMMENT ON COLUMN irmgard.row_changes.action           IS 'The DML Operation that modified the row.';

-- NB: Indexes for irmgard.row_changes?
-- use a functional index:
CREATE UNIQUE INDEX idx_row_changes_text_pk ON irmgard.row_changes (text_primary_key)        WHERE int_primary_key  is NULL AND text_primary_key IS NOT NULL;
CREATE UNIQUE INDEX idx_row_changes_int_pk  ON irmgard.row_changes (int_primary_key)         WHERE text_primary_key is NULL AND int_primary_key  IS NOT NULL;
CREATE UNIQUE INDEX idx_row_changes_no_pk   ON irmgard.row_changes (schema_name, table_name) WHERE text_primary_key is NULL AND int_primary_key IS NULL;


CREATE OR REPLACE FUNCTION irmgard.xxx() RETURNS TRIGGER AS $body$
DECLARE
BEGIN
END;
$body$
LANGUAGE plpgsql
SECURITY DEFINER;
-- SET search_path = pg_catalog, public;

COMMENT ON FUNCTION irmgard.xxx() IS $body$
irmgard.xxx does the following:

Parameters are:

param 0: boolean
param 1: text[]
$body$;



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
CREATE TABLE irmgard.stats (
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  name           TEXT      NOT NULL,
  stat           bigint    NOT NULL,
);

COMMENT ON TABLE  irmgard.stats                       IS 'Stats tracking for the tracking functions.';
COMMENT ON COLUMN irmgard.process_log.updated_at      IS 'Last time the stat row was updated.';
COMMENT ON COLUMN irmgard.process_log.name            IS 'Name of the stat.';
COMMENT ON COLUMN irmgard.process_log.stat            IS 'Value of the stat.';
