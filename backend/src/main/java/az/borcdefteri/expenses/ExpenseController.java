package az.borcdefteri.expenses;

import az.borcdefteri.common.FileResponses;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping("/expense-categories")
    public List<ExpenseService.Category> categories() {
        return expenses.categories();
    }

    @PostMapping("/expense-categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.Category createCategory(@Valid @RequestBody ExpenseService.CategoryRequest req) {
        return expenses.createCategory(req);
    }

    @GetMapping("/expenses")
    public ExpenseService.ExpensePage list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return expenses.list(from, to, categoryId, q, page, size);
    }

    @GetMapping("/expenses/summary")
    public ExpenseService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "category") String groupBy) {
        return expenses.summary(from, to, groupBy);
    }

    @GetMapping("/expenses/{id}")
    public ExpenseService.Expense get(@PathVariable UUID id) {
        return expenses.get(id);
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.Expense create(@Valid @RequestBody ExpenseService.ExpenseRequest req) {
        return expenses.create(req);
    }

    @PutMapping("/expenses/{id}")
    public ExpenseService.Expense update(@PathVariable UUID id, @Valid @RequestBody ExpenseService.ExpenseRequest req) {
        return expenses.update(id, req);
    }

    @DeleteMapping("/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        expenses.delete(id);
    }

    @PostMapping(value = "/expenses/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.Attachment attach(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return expenses.attach(id, file);
    }

    @GetMapping("/expenses/{id}/attachments")
    public List<ExpenseService.Attachment> attachments(@PathVariable UUID id) {
        return expenses.attachments(id);
    }

    @GetMapping("/expenses/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        ExpenseService.FileContent f = expenses.attachment(id, attachmentId);
        return FileResponses.inline(f.content(), f.contentType(), f.fileName());
    }

    @GetMapping("/recurring-expenses")
    public List<ExpenseService.Recurring> recurring() {
        return expenses.recurring();
    }

    @PostMapping("/recurring-expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.Recurring createRecurring(@Valid @RequestBody ExpenseService.RecurringRequest req) {
        return expenses.createRecurring(req);
    }

    @PutMapping("/recurring-expenses/{id}")
    public ExpenseService.Recurring updateRecurring(@PathVariable UUID id, @Valid @RequestBody ExpenseService.RecurringRequest req) {
        return expenses.updateRecurring(id, req);
    }

    @PostMapping("/recurring-expenses/{id}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseService.Expense payRecurring(@PathVariable UUID id, @Valid @RequestBody ExpenseService.PayRecurringRequest req) {
        return expenses.payRecurring(id, req);
    }

    @GetMapping("/budgets")
    public List<ExpenseService.Budget> budgets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return expenses.budgets(month);
    }

    @PutMapping("/budgets")
    public List<ExpenseService.Budget> setBudget(@Valid @RequestBody ExpenseService.BudgetRequest req) {
        return expenses.setBudget(req);
    }

    @DeleteMapping("/budgets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBudget(@PathVariable UUID id) {
        expenses.deleteBudget(id);
    }
}
