-- Run once only when Hibernate has not already added the nullable column.

ALTER TABLE companion_status
    ADD COLUMN gesture VARCHAR(20) NULL;
