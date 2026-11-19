-- Auto-crafting to a watched item's target (ADR-0021): who switched it on, null while off.
ALTER TABLE watch ADD COLUMN restock_by TEXT;
