package az.borcdefteri.reports;

import az.borcdefteri.common.FileResponses;
import az.borcdefteri.debts.DebtType;
import az.borcdefteri.reports.ReportService.ExportFile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
public class ReportController {

    private final ReportService reports;
    private final PaymentReportService paymentReports;
    private final az.borcdefteri.payments.ReceiptLinks links;

    public ReportController(ReportService reports, PaymentReportService paymentReports,
                            az.borcdefteri.payments.ReceiptLinks links) {
        this.reports = reports;
        this.paymentReports = paymentReports;
        this.links = links;
    }

    /** Debts of one section (customer debts / company receivables / company payables) as XLSX or PDF. */
    @GetMapping("/api/reports/debts/export")
    public ResponseEntity<byte[]> exportDebts(
            @RequestParam DebtType type,
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return send(reports.exportDebts(type, status, from, to, format));
    }

    /** Daily / monthly payments report (cash, card, transfer, other with totals). Requires REPORTS. */
    @GetMapping("/api/reports/payments/export")
    public ResponseEntity<byte[]> exportPayments(
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return send(paymentReports.paymentsReport(from, to, format));
    }

    /** Payment receipt (çek) as PDF. Store-scoped and limited to the sections the caller may see. */
    @GetMapping("/api/payments/{id}/receipt")
    public ResponseEntity<byte[]> paymentReceipt(@PathVariable UUID id) {
        return send(paymentReports.receiptPdf(id));
    }

    /**
     * Public receipt link sent to the customer over WhatsApp. Valid only with the HMAC token of that payment;
     * anything else is reported as not found (no information about which payments exist).
     */
    @GetMapping("/api/public/receipts/{id}/{token}")
    public ResponseEntity<byte[]> sharedReceipt(@PathVariable UUID id, @PathVariable String token) {
        if (!links.isValid(id, token)) {
            throw az.borcdefteri.common.ApiException.notFound("Çek tapılmadı");
        }
        ExportFile file = paymentReports.sharedReceiptPdf(id);
        return FileResponses.inline(file.content(), file.contentType(), file.fileName());
    }

    private static ResponseEntity<byte[]> send(ExportFile file) {
        return FileResponses.attachment(file.content(), file.contentType(), file.fileName());
    }
}
