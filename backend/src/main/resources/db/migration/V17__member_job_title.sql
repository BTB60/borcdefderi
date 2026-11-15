-- Employee's job (vəzifə): the owner picks one and its permissions are pre-filled; the permissions still decide access.
ALTER TABLE store_members ADD COLUMN job_title VARCHAR(20)
    CHECK (job_title IS NULL OR job_title IN ('CASHIER', 'STOREKEEPER', 'ACCOUNTANT', 'SELLER', 'OTHER'));
