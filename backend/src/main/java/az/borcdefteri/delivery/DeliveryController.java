package az.borcdefteri.delivery;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Çatdırılma və kuryer, plus the customer's public tracking page. */
@RestController
@RequestMapping("/api")
public class DeliveryController {

    private final DeliveryService deliveries;

    public DeliveryController(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @GetMapping("/deliveries")
    public List<DeliveryService.Delivery> list(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "false") boolean mine,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return deliveries.list(status, mine, from, to);
    }

    @GetMapping("/deliveries/couriers")
    public List<DeliveryService.Courier> couriers() {
        return deliveries.couriers();
    }

    @GetMapping("/deliveries/summary")
    public List<DeliveryService.CourierSummary> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return deliveries.summary(from, to);
    }

    /** Cash couriers still hold (collected at the door, not handed in). */
    @GetMapping("/deliveries/courier-cash")
    public List<DeliveryService.CourierCash> courierCash() {
        return deliveries.courierCash();
    }

    /** Kuryer pulu təhvil verdi: the courier's collected cash goes into the cash desk. */
    @PostMapping("/deliveries/handover")
    public DeliveryService.HandoverResult handOver(@Valid @RequestBody DeliveryService.HandoverRequest req) {
        return deliveries.handOver(req);
    }

    @GetMapping("/deliveries/{id}")
    public DeliveryService.Delivery get(@PathVariable UUID id) {
        return deliveries.get(id);
    }

    @PostMapping("/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryService.Delivery create(@Valid @RequestBody DeliveryService.CreateRequest req) {
        return deliveries.create(req);
    }

    @PostMapping("/deliveries/{id}/assign")
    public DeliveryService.Delivery assign(@PathVariable UUID id, @Valid @RequestBody DeliveryService.AssignRequest req) {
        return deliveries.assign(id, req);
    }

    @PostMapping("/deliveries/{id}/pick-up")
    public DeliveryService.Delivery pickUp(@PathVariable UUID id) {
        return deliveries.pickUp(id);
    }

    @PostMapping("/deliveries/{id}/deliver")
    public DeliveryService.Delivery deliver(@PathVariable UUID id, @Valid @RequestBody(required = false) DeliveryService.DeliverRequest req) {
        return deliveries.deliver(id, req);
    }

    @PostMapping("/deliveries/{id}/cancel")
    public DeliveryService.Delivery cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) DeliveryService.CancelRequest req) {
        return deliveries.cancel(id, req);
    }

    /** No sign-in: the customer opens the link the store sent. */
    @GetMapping("/public/deliveries/{token}")
    public DeliveryService.Tracking track(@PathVariable String token) {
        return deliveries.track(token);
    }
}
