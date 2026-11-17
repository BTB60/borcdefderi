-- Kassir PIN: a short PIN (4-6 digits, stored hashed) to switch cashiers and unlock the till without a full sign-in.
ALTER TABLE users ADD COLUMN pin_hash VARCHAR(100);
