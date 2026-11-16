package az.borcdefteri.inventory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Toplu qiymət dəyişikliyi. */
@RestController
@RequestMapping("/api/products")
public class BulkPriceController {

    private final BulkPriceService bulk;

    public BulkPriceController(BulkPriceService bulk) {
        this.bulk = bulk;
    }

    @PostMapping("/bulk-price")
    public BulkPriceService.Result apply(@Valid @RequestBody BulkPriceService.Request req) {
        return bulk.apply(req);
    }
}
