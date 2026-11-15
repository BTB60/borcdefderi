-- A debt recorded by mistake can be cancelled (ləğv) when nothing was paid on it: amount and remaining drop to 0,
-- the row stays for the history (its DEBT_CREATED entry is reversed by a DEBT_ADJUSTED row) and is hidden from lists.
ALTER TABLE debts ADD COLUMN cancelled_at TIMESTAMPTZ;

-- Cancelled operations the user removed from the history view. Ledger rows themselves are immutable; a hidden group
-- (an entry and its cancellation, a voided payment and its reversal, or every row of a cancelled debt) nets to zero.
CREATE TABLE ledger_hidden (
    entry_id  UUID        PRIMARY KEY REFERENCES financial_transactions(id),
    store_id  UUID        NOT NULL REFERENCES stores(id),
    hidden_by UUID        NOT NULL REFERENCES users(id),
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
