package az.borcdefteri.customers;

import az.borcdefteri.customers.PartyService.Party;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PartyController {

    private final PartyService parties;

    public PartyController(PartyService parties) {
        this.parties = parties;
    }

    @GetMapping("/customers")
    public List<Party> customers(@RequestParam(required = false) String q) {
        return parties.customers(q);
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Party createCustomer(@Valid @RequestBody PartyRequest req) {
        return parties.createCustomer(req);
    }

    @PutMapping("/customers/{id}")
    public Party updateCustomer(@PathVariable UUID id, @Valid @RequestBody PartyRequest req) {
        return parties.updateCustomer(id, req);
    }

    @GetMapping("/companies")
    public List<Party> companies(@RequestParam(required = false) String q) {
        return parties.companies(q);
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public Party createCompany(@Valid @RequestBody PartyRequest req) {
        return parties.createCompany(req);
    }

    @PutMapping("/companies/{id}")
    public Party updateCompany(@PathVariable UUID id, @Valid @RequestBody PartyRequest req) {
        return parties.updateCompany(id, req);
    }
}
