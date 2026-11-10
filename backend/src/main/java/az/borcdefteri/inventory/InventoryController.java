package az.borcdefteri.inventory;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/product-categories")
    public List<InventoryService.Category> categories() {
        return inventory.categories();
    }

    @PostMapping("/product-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryService.Category createCategory(@RequestBody Map<String, String> body) {
        return inventory.createCategory(body.get("name"));
    }

    @GetMapping("/products")
    public List<InventoryService.Product> products(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) UUID categoryId,
                                                   @RequestParam(required = false) String stock,
                                                   @RequestParam(required = false) Boolean active) {
        return inventory.products(q, categoryId, stock, active);
    }

    @GetMapping("/products/summary")
    public InventoryService.Summary summary() {
        return inventory.summary();
    }

    @GetMapping("/products/by-code/{code}")
    public InventoryService.Product byCode(@PathVariable String code) {
        return inventory.byBarcode(code);
    }

    @GetMapping("/products/{id}")
    public InventoryService.Product product(@PathVariable UUID id) {
        return inventory.product(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryService.Product create(@Valid @RequestBody InventoryService.ProductRequest req) {
        return inventory.create(req);
    }

    @PutMapping("/products/{id}")
    public InventoryService.Product update(@PathVariable UUID id, @Valid @RequestBody InventoryService.ProductRequest req) {
        return inventory.update(id, req);
    }

    @DeleteMapping("/products/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        boolean deleted = inventory.delete(id);
        return Map.of("deleted", deleted, "deactivated", !deleted);
    }

    @PostMapping(value = "/products/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InventoryService.Product image(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return inventory.setImage(id, file);
    }

    @GetMapping("/products/{id}/image")
    public ResponseEntity<byte[]> getImage(@PathVariable UUID id) {
        InventoryService.Image img = inventory.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.noStore()).body(img.content());
    }

    @PostMapping("/inventory/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryService.Movement stock(@Valid @RequestBody InventoryService.StockRequest req) {
        return inventory.stock(req);
    }

    @GetMapping("/inventory/movements")
    public List<InventoryService.Movement> movements(@RequestParam(required = false) UUID productId,
                                                     @RequestParam(defaultValue = "300") int limit) {
        return inventory.movements(productId, limit);
    }
}
