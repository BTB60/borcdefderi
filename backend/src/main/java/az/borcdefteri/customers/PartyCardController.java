package az.borcdefteri.customers;

import az.borcdefteri.audit.ActivityService;
import az.borcdefteri.common.FileResponses;
import az.borcdefteri.customers.PartyService.Party;
import az.borcdefteri.reports.PartyExportService;
import az.borcdefteri.reports.ReportService.ExportFile;
import az.borcdefteri.reports.StatementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HesabPro 2.0 customer card: history, notes, contact, statement PDF, portal links, exports and activity. */
@RestController
public class PartyCardController {

    private final PartyCardService cards;
    private final CustomerPortalService portal;
    private final StatementService statements;
    private final PartyExportService exports;
    private final ActivityService activity;

    public PartyCardController(PartyCardService cards, CustomerPortalService portal, StatementService statements,
                               PartyExportService exports, ActivityService activity) {
        this.cards = cards;
        this.portal = portal;
        this.statements = statements;
        this.exports = exports;
        this.activity = activity;
    }

    // ----- history -----

    @GetMapping("/api/customers/{id}/history")
    public List<PartyCardService.HistoryEntry> customerHistory(@PathVariable UUID id) {
        return cards.history(true, id);
    }

    @GetMapping("/api/companies/{id}/history")
    public List<PartyCardService.HistoryEntry> companyHistory(@PathVariable UUID id) {
        return cards.history(false, id);
    }

    // ----- notes and contact -----

    @GetMapping("/api/customers/{id}/notes")
    public List<PartyCardService.Note> notes(@PathVariable UUID id) {
        return cards.notes(id);
    }

    @PostMapping("/api/customers/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyCardService.Note addNote(@PathVariable UUID id, @Valid @RequestBody PartyCardService.NoteRequest req) {
        return cards.addNote(id, req);
    }

    @PostMapping("/api/customers/{id}/contact")
    public Party contact(@PathVariable UUID id, @Valid @RequestBody PartyCardService.ContactRequest req) {
        return cards.contacted(id, req);
    }

    // ----- statement PDF -----

    @GetMapping("/api/customers/{id}/statement")
    public ResponseEntity<byte[]> customerStatement(@PathVariable UUID id) {
        return attach(statements.forStaff(true, id));
    }

    @GetMapping("/api/companies/{id}/statement")
    public ResponseEntity<byte[]> companyStatement(@PathVariable UUID id) {
        return attach(statements.forStaff(false, id));
    }

    // ----- customer portal links -----

    @GetMapping("/api/customers/{id}/portal-links")
    public List<CustomerPortalService.LinkView> links(@PathVariable UUID id) {
        return portal.links(id);
    }

    @PostMapping("/api/customers/{id}/portal-links")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerPortalService.CreatedLink createLink(@PathVariable UUID id,
                                                        @Valid @RequestBody(required = false) CustomerPortalService.CreateRequest req) {
        return portal.create(id, req);
    }

    @DeleteMapping("/api/customers/{id}/portal-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeLink(@PathVariable UUID id, @PathVariable UUID linkId) {
        portal.revoke(id, linkId);
    }

    /** Public (no login): the customer's own read-only page. */
    @GetMapping("/api/public/portal/{token}")
    public CustomerPortalService.PortalView portalView(@PathVariable String token) {
        return portal.view(token);
    }

    @GetMapping("/api/public/portal/{token}/statement")
    public ResponseEntity<byte[]> portalStatement(@PathVariable String token) {
        ExportFile f = statements.forPortal(token);
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }

    // ----- exports and activity -----

    @GetMapping("/api/reports/customers/export")
    public ResponseEntity<byte[]> exportCustomers() {
        return attach(exports.customers());
    }

    @GetMapping("/api/reports/companies/export")
    public ResponseEntity<byte[]> exportCompanies() {
        return attach(exports.companies());
    }

    @GetMapping("/api/activity")
    public ActivityService.Activity activity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(defaultValue = "200") int limit) {
        return activity.activity(from, to, actorId, limit);
    }

    private static ResponseEntity<byte[]> attach(ExportFile f) {
        return FileResponses.attachment(f.content(), f.contentType(), f.fileName());
    }
}
