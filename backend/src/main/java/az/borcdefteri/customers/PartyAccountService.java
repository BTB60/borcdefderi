package az.borcdefteri.customers;

import az.borcdefteri.common.ApiException;
import az.borcdefteri.common.Money;
import az.borcdefteri.debts.DebtRequest;
import az.borcdefteri.debts.DebtService;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.payments.PaymentRequest;
import az.borcdefteri.payments.PaymentService;
import az.borcdefteri.payments.PaymentService.PaymentView;
import az.borcdefteri.tenant.CurrentMember;
import az.borcdefteri.tenant.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The customer / company as one running account: "Borc əlavə et" adds to the balance, "Borc ödə" pays it off.
 * Underneath, the existing debts, payments and immutable ledger are used, so every step stays in the history.
 * All debts of the party in that section are row-locked for the whole operation.
 */
@Service
public class PartyAccountService {

    public record ChargeResult(DebtType debtType, BigDecimal remainingBefore, BigDecimal remainingAfter, DebtView debt) {}

    public record PayResult(DebtType debtType, BigDecimal remainingBefore, BigDecimal remainingAfter,
                            List<PaymentView> payments) {}

    private final TenantService tenant;
    private final PartyService parties;
    private final DebtService debts;
    private final PaymentService payments;

    public PartyAccountService(TenantService tenant, PartyService parties, DebtService debts, PaymentService payments) {
        this.tenant = tenant;
        this.parties = parties;
        this.debts = debts;
        this.payments = payments;
    }

    @Transactional
    public ChargeResult charge(boolean customer, UUID partyId, PartyAccountRequests.Charge req) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtType type = resolveType(m, customer, partyId, req.debtType());
        List<DebtView> all = debts.lockForParty(m, type, partyId);
        BigDecimal before = remaining(all);
        checkExpected(req.expectedRemaining(), before);

        DebtView result;
        if (all.isEmpty()) {
            result = debts.create(new DebtRequest(type, partyId, req.amount(),
                    req.date() != null ? req.date() : tenant.today(), req.dueDate(), blankToNull(req.note()), null),
                    req.receiptNumber());
        } else {
            // the latest debt of the account carries the running balance
            DebtView target = all.get(all.size() - 1);
            result = debts.increaseLocked(m, target, req.amount(), req.date(), req.receiptNumber(), req.note());
            if (req.dueDate() != null) {
                result = debts.setDueDateLocked(m, result, req.dueDate());
            }
        }
        return new ChargeResult(type, before, Money.of(before.add(Money.of(req.amount()))), result);
    }

    @Transactional
    public PayResult pay(boolean customer, UUID partyId, PartyAccountRequests.Pay req) {
        CurrentMember m = tenant.requireBusinessMember();
        DebtType type = resolveType(m, customer, partyId, req.debtType());
        List<DebtView> unpaid = debts.lockForParty(m, type, partyId).stream()
                .filter(d -> d.remainingAmount().signum() > 0).toList();
        BigDecimal before = remaining(unpaid);
        checkExpected(req.expectedRemaining(), before);
        BigDecimal amount = Money.of(req.amount());
        if (before.signum() == 0) {
            throw ApiException.conflict("NO_DEBT", "Bu hesab üzrə ödəniləcək borc yoxdur");
        }
        if (amount.compareTo(before) > 0) {
            throw ApiException.conflict("PAYMENT_EXCEEDS_BALANCE",
                    "Ödəniş qalıq borcdan (" + before + " AZN) böyük ola bilməz");
        }
        List<PaymentView> made = new ArrayList<>();
        BigDecimal left = amount;
        for (DebtView d : unpaid) {   // oldest first
            if (left.signum() == 0) {
                break;
            }
            BigDecimal part = left.min(d.remainingAmount());
            made.add(payments.record(d.id(), new PaymentRequest(part, req.paymentMethod(), req.paidAt(),
                    req.description(), req.note())));
            left = Money.of(left.subtract(part));
        }
        return new PayResult(type, before, Money.of(before.subtract(amount)), made);
    }

    private DebtType resolveType(CurrentMember m, boolean customer, UUID partyId, DebtType requested) {
        DebtType type;
        if (customer) {
            parties.customer(m, partyId);
            type = DebtType.CUSTOMER_DEBT;
        } else {
            parties.company(m, partyId);
            if (requested == null || requested == DebtType.CUSTOMER_DEBT) {
                throw ApiException.badRequest("DEBT_TYPE_REQUIRED",
                        "Seçin: firma bizə borcludur və ya biz firmaya borcluyuq");
            }
            type = requested;
        }
        debts.requireAccess(m, type);
        return type;
    }

    private static void checkExpected(BigDecimal expected, BigDecimal actual) {
        if (expected != null && Money.of(expected).compareTo(actual) != 0) {
            throw ApiException.conflict("STALE_BALANCE",
                    "Borc qalığı artıq dəyişib (" + actual + " AZN). Səhifəni yeniləyib yenidən cəhd edin");
        }
    }

    private static BigDecimal remaining(List<DebtView> list) {
        BigDecimal sum = Money.zero();
        for (DebtView d : list) {
            sum = sum.add(d.remainingAmount());
        }
        return Money.of(sum);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
