package az.borcdefteri.cash;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashService cash;

    public CashController(CashService cash) {
        this.cash = cash;
    }

    @GetMapping("/accounts")
    public List<CashService.Account> accounts() {
        return cash.accounts();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public CashService.Account create(@Valid @RequestBody CashService.AccountRequest req) {
        return cash.createAccount(req);
    }

    @PutMapping("/accounts/{id}")
    public CashService.Account update(@PathVariable UUID id, @Valid @RequestBody CashService.AccountRequest req) {
        return cash.updateAccount(id, req);
    }

    @GetMapping("/movements")
    public List<CashService.Movement> movements(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "300") int limit) {
        return cash.movements(accountId, from, to, kind, limit);
    }

    @PostMapping("/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public CashService.Movement deposit(@Valid @RequestBody CashService.MoveRequest req) {
        return cash.deposit(req);
    }

    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public CashService.Movement withdraw(@Valid @RequestBody CashService.MoveRequest req) {
        return cash.withdraw(req);
    }

    @PostMapping("/opening")
    @ResponseStatus(HttpStatus.CREATED)
    public CashService.Movement opening(@Valid @RequestBody CashService.MoveRequest req) {
        return cash.opening(req);
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CashService.Movement> transfer(@Valid @RequestBody CashService.TransferRequest req) {
        return cash.transfer(req);
    }

    @PostMapping("/count")
    public CashService.CountResult count(@Valid @RequestBody CashService.CountRequest req) {
        return cash.count(req);
    }

    @GetMapping("/day-report")
    public CashService.DayReport dayReport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cash.dayReport(date);
    }
}
