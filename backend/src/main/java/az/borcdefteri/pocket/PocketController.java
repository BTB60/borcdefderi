package az.borcdefteri.pocket;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** "Cib pulu" — the caller's own pocket money (see {@link PocketService}). */
@RestController
@RequestMapping("/api/pocket")
public class PocketController {

    private final PocketService pocket;

    public PocketController(PocketService pocket) {
        this.pocket = pocket;
    }

    @GetMapping
    public PocketService.Day day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pocket.day(date);
    }

    @GetMapping("/history")
    public List<PocketService.DaySummary> history(@RequestParam(defaultValue = "30") int days) {
        return pocket.history(days);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PocketService.Entry add(@Valid @RequestBody PocketService.EntryRequest req) {
        return pocket.add(req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        pocket.delete(id);
    }
}
