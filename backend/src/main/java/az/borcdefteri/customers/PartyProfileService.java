package az.borcdefteri.customers;

import az.borcdefteri.common.Money;
import az.borcdefteri.customers.PartyService.Party;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.payments.PaymentService;
import az.borcdefteri.payments.PaymentService.PaymentView;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Customer / company profile: totals, every debt and every payment, from the caller's store only. */
@Service
public class PartyProfileService {

    public record Totals(BigDecimal totalDebt, BigDecimal paid, BigDecimal remaining, long debtCount, long openDebtCount) {}

    /**
     * [receivable] (the company owes us) and [payable] (we owe the company) are separate for a company — they must
     * never be added together. Both null for a customer.
     */
    public record Profile(Party party, Totals totals, List<DebtView> debts, List<PaymentView> payments,
                          Totals receivable, Totals payable) {}

    private final TenantService tenant;
    private final PartyService parties;
    private final DebtService debts;
    private final PaymentService payments;

    public PartyProfileService(TenantService tenant, PartyService parties, DebtService debts, PaymentService payments) {
        this.tenant = tenant;
        this.parties = parties;
        this.debts = debts;
        this.payments = payments;
    }

    public Profile customer(UUID id) {
        CurrentMember m = tenant.requireBusinessMember();
        Party party = parties.customer(m, id);
        return build(m, party, true);
    }

    public Profile company(UUID id) {
        CurrentMember m = tenant.requireBusinessMember();
        Party party = parties.company(m, id);
        return build(m, party, false);
    }

    private Profile build(CurrentMember m, Party party, boolean customer) {
        List<DebtView> list = debts.forParty(m, customer, party.id());
        Totals receivable = customer ? null : sum(list.stream().filter(d -> d.debtType() == DebtType.COMPANY_RECEIVABLE).toList());
        Totals payable = customer ? null : sum(list.stream().filter(d -> d.debtType() == DebtType.COMPANY_PAYABLE).toList());
        // for a company the combined figure only makes sense from one side: what it owes us
        Totals totals = customer ? sum(list) : receivable;
        return new Profile(party, totals, list, payments.listForParty(m, customer, party.id()), receivable, payable);
    }

    private static Totals sum(List<DebtView> list) {
        BigDecimal total = Money.zero(), paid = Money.zero(), remaining = Money.zero();
        long open = 0;
        for (DebtView d : list) {
            total = total.add(d.amount());
            paid = paid.add(d.paidAmount());
            remaining = remaining.add(d.remainingAmount());
            if (!"PAID".equals(d.status())) open++;
        }
        return new Totals(Money.of(total), Money.of(paid), Money.of(remaining), list.size(), open);
    }
}
