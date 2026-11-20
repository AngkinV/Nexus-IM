-- Run once only when Hibernate has not already added the column.
ALTER TABLE companion_roles
    ADD COLUMN conversation_hidden BOOLEAN NOT NULL DEFAULT FALSE;
