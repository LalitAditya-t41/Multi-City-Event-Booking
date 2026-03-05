-- owner: identity module demo seed
-- Idempotent preference options for FR3 settings flow.

INSERT INTO preference_options (type, value, active, sort_order)
VALUES
  ('CITY', 'Mumbai', TRUE, 1),
  ('CITY', 'Delhi', TRUE, 2),
  ('GENRE', 'MUSIC', TRUE, 1),
  ('GENRE', 'COMEDY', TRUE, 2),
  ('GENRE', 'THEATRE', TRUE, 3)
ON CONFLICT (type, value) DO NOTHING;
