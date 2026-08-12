-- A file appears once in a group, and the database says so.
--
-- A group is a set of files that resemble one another, and the screen reads it
-- as one: one row per file, one verdict each. Nothing enforced that. The writer
-- assembles a group from the keep plus the delete candidates plus the review
-- candidates, and if any file ever reached two of those lists it would be
-- written twice - once as the original to keep and once as a copy to delete.
--
-- That is not a display problem. The Duplicates screen acts on verdicts, so a
-- file listed as both is a file the user can be shown as safe and offered for
-- deletion in the same group; and the count the group carries would describe a
-- population that does not exist.
--
-- Groups are derived, so a surplus row is repaired rather than kept: the lowest
-- position survives - which by construction is the keep - and the count the
-- group publishes is brought back to what it now holds. Recomputing would fix
-- both anyway; this is so an existing catalogue does not have to wait for it.

DELETE FROM similarity_group_member m
      USING similarity_group_member other
      WHERE m.group_id = other.group_id
        AND m.catalog_file_public_id = other.catalog_file_public_id
        AND (other.position, other.id) < (m.position, m.id);

UPDATE similarity_group g
   SET file_count = counted.members
  FROM (SELECT group_id, count(*) AS members FROM similarity_group_member GROUP BY group_id) counted
 WHERE counted.group_id = g.id
   AND g.file_count <> counted.members;

ALTER TABLE similarity_group_member
    ADD CONSTRAINT uk_similarity_group_member UNIQUE (group_id, catalog_file_public_id);

COMMENT ON CONSTRAINT uk_similarity_group_member ON similarity_group_member IS
    'A file is one member of a group. Two rows for the same file would let one file carry two verdicts at once, and would make the count the group publishes describe a population it does not hold.';