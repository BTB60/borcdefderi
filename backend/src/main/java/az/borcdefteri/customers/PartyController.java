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
    private final PartyProfileService profiles;
    private final PartyAccountService accounts;

    public PartyController(PartyService parties, PartyProfileService profiles, PartyAccountService accounts) {
        this.parties = parties;
        this.profiles = profiles;
        this.accounts = accounts;
    }

    /** "Borc əlavə et" on the customer's account. */
    @PostMapping("/customers/{id}/charge")
    public PartyAccountService.ChargeResult chargeCustomer(@PathVariable UUID id, @Valid @RequestBody PartyAccountRequests.Charge req) {
        return accounts.charge(true, id, req);
    }

    /** "Borc ödə" on the customer's account (oldest debt first). */
    @PostMapping("/customers/{id}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyAccountService.PayResult payCustomer(@PathVariable UUID id, @Valid @RequestBody PartyAccountRequests.Pay req) {
        return accounts.pay(true, id, req);
    }

    @PostMapping("/companies/{id}/charge")
    public PartyAccountService.ChargeResult chargeCompany(@PathVariable UUID id, @Valid @RequestBody PartyAccountRequests.Charge req) {
        return accounts.charge(false, id, req);
    }

    @PostMapping("/companies/{id}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyAccountService.PayResult payCompany(@PathVariable UUID id, @Valid @RequestBody PartyAccountRequests.Pay req) {
        return accounts.pay(false, id, req);
    }

    @GetMapping("/customers/{id}/profile")
    public PartyProfileService.Profile customerProfile(@PathVariable UUID id) {
        return profiles.customer(id);
    }

    @GetMapping("/companies/{id}/profile")
    public PartyProfileService.Profile companyProfile(@PathVariable UUID id) {
        return profiles.company(id);
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
