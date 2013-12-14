--
--
--
--

DROP TABLE IF EXISTS irmgard.example_table;
CREATE TABLE irmgard.example_table (
  id bigserial primary key,
  value text
);

SELECT irmgard.enable_tracking_int_pk('irmgard.example_table', 'id');

INSERT INTO irmgard.example_table (value) VALUES
  ('one'),
  ('two'),
  ('three'),
  ('four');

-- irmgard.row_changes should now have 4 records in it, all with ACTION=I

UPDATE irmgard.example_table
  SET  value = upper(value)
 WHERE value = 'two';

-- irmgard.row_changes should still have 4 records in it
--   three with ACTION=I
--   one   with ACTION=U

DELETE FROM irmgard.example_table WHERE value = 'four';

-- irmgard.row_changes should still have 4 records in it
--   two   with ACTION=I
--   one   with ACTION=U
--   one   with ACTION=D

