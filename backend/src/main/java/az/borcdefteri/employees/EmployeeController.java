package az.borcdefteri.employees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Owner-only employee management. Employees log in with the same /api/auth/login endpoint. */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    public record EmployeeCreate(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(min = 8, max = 72, message = "Şifrə ən azı 8 simvol olmalıdır") String password,
            @NotBlank @Size(max = 150) String fullName,
            @jakarta.validation.constraints.Pattern(regexp = "^[+0-9 ()-]{0,30}$", message = "Telefon nömrəsi düzgün deyil") String phone,
            @NotNull List<String> permissions) {}

    public record EmployeeUpdate(@NotNull List<String> permissions, boolean active) {}

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    public List<EmployeeService.EmployeeView> list() {
        return employees.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeService.EmployeeView create(@Valid @RequestBody EmployeeCreate req) {
        return employees.create(req.email(), req.password(), req.fullName(), req.phone(), req.permissions());
    }

    @PutMapping("/{memberId}")
    public EmployeeService.EmployeeView update(@PathVariable UUID memberId, @Valid @RequestBody EmployeeUpdate req) {
        return employees.update(memberId, req.permissions(), req.active());
    }
}
