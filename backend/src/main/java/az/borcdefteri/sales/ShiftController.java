package az.borcdefteri.sales;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Kassir növbəsi: open, close with the counted cash, and the Z-report. */
@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

    private final ShiftService shifts;

    public ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    /** The caller's open shift; 204 when there is none. */
    @GetMapping("/current")
    public ResponseEntity<ShiftService.Report> current() {
        ShiftService.Report r = shifts.current();
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    @PostMapping("/open")
    public ShiftService.Report open(@Valid @RequestBody(required = false) ShiftService.OpenRequest req) {
        return shifts.open(req);
    }

    @PostMapping("/{id}/close")
    public ShiftService.Report close(@PathVariable UUID id, @Valid @RequestBody ShiftService.CloseRequest req) {
        return shifts.close(id, req);
    }

    @GetMapping
    public List<ShiftService.Report> list(@RequestParam(defaultValue = "50") int limit) {
        return shifts.list(limit);
    }

    @GetMapping("/{id}")
    public ShiftService.Report get(@PathVariable UUID id) {
        return shifts.get(id);
    }
}
