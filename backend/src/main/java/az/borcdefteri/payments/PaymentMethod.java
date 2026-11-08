package az.borcdefteri.payments;

/** CASH = nağd, CARD = kart (POS), TRANSFER = bank köçürməsi / M10 və s., OTHER = digər. */
public enum PaymentMethod {
    CASH, CARD, TRANSFER, OTHER;

    /** Ledger transaction type (financial_transactions.transaction_type). */
    public String ledgerType() {
        return "PAYMENT_" + name();
    }
}
