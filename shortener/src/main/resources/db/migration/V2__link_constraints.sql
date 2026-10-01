-- Enforce in the database the invariants the application already guarantees.
ALTER TABLE links
  ADD CONSTRAINT links_code_canonical CHECK (code ~ '^[a-z0-9-]{4,32}$');

ALTER TABLE link_stats
  ADD CONSTRAINT link_stats_redirect_count_non_negative CHECK (redirect_count >= 0);

-- Counter rows are updated in place; free page space enables HOT updates and limits bloat.
-- Applies to newly written pages; existing pages adopt it as they are rewritten.
ALTER TABLE link_stats SET (fillfactor = 70);
