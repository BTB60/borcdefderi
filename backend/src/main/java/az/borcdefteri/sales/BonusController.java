package az.borcdefteri.sales;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Customer bonus balance / history, manual adjustment, and the store's bonus percent. */
@RestController
@RequestMapping("/api")
public class BonusController {

    public record PercentUpdate(BigDecimal bonusPercent) {}

    private final BonusService bonus;

    public BonusController(BonusService bonus) {
        this.bonus = bonus;
    }

    @GetMapping("/customers/{id}/bonus")
    public BonusService.Account account(@PathVariable UUID id) {
        return bonus.account(id);
    }

    @PostMapping("/customers/{id}/bonus")
    public BonusService.Account adjust(@PathVariable UUID id, @Valid @RequestBody BonusService.Adjust req) {
        return bonus.adjust(id, req);
    }

    @GetMapping("/settings/bonus")
    public Map<String, BigDecimal> percent() {
        return Map.of("bonusPercent", bonus.currentStorePercent());
    }

    @PutMapping("/settings/bonus")
    public Map<String, BigDecimal> setPercent(@RequestBody PercentUpdate req) {
        return Map.of("bonusPercent", bonus.setStorePercent(req.bonusPercent()));
    }
}
