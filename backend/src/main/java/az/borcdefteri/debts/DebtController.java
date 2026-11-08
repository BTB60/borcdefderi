package az.borcdefteri.debts;

import az.borcdefteri.debts.DebtService.DebtPage;
import az.borcdefteri.debts.DebtService.DebtView;
import az.borcdefteri.debts.DebtService.LedgerEntry;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/debts")
public class DebtController {

    private final DebtService debts;

    public DebtController(DebtService debts) {
        this.debts = debts;
    }

    @GetMapping
    public DebtPage list(@RequestParam DebtType type,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String q,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        return debts.list(type, status, q, from, to, page, size);
    }

    @GetMapping("/{id}")
    public DebtView get(@PathVariable UUID id) {
        return debts.get(id);
    }

    @GetMapping("/{id}/history")
    public List<LedgerEntry> history(@PathVariable UUID id) {
        return debts.history(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DebtView create(@Valid @RequestBody DebtRequest req) {
        return debts.create(req);
    }

    @PutMapping("/{id}")
    public DebtView update(@PathVariable UUID id, @Valid @RequestBody DebtUpdateRequest req) {
        return debts.update(id, req);
    }
}
