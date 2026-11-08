package az.borcdefteri.debts;

import az.borcdefteri.tenant.TenantService;

/** The three independent financial sections of a store. */
public enum DebtType {
    /** Money customers owe to the store. */
    CUSTOMER_DEBT(TenantService.PERM_CUSTOMER_DEBTS),
    /** Money other companies owe to the store. */
    COMPANY_RECEIVABLE(TenantService.PERM_COMPANY_RECEIVABLES),
    /** Money the store owes to other companies. */
    COMPANY_PAYABLE(TenantService.PERM_COMPANY_PAYABLES);

    private final String permission;

    DebtType(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }

    public boolean isCustomer() {
        return this == CUSTOMER_DEBT;
    }
}
