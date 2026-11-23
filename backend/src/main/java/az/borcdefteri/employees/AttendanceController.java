package az.borcdefteri.employees;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** İşçi davamiyyəti: Gəldim / Getdim, the records, the owner's summary and corrections, work start time. */
@RestController
@RequestMapping("/api")
public class AttendanceController {

    private final AttendanceService attendance;

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
    }

    /** The caller's open record; 204 when they are not checked in. */
    @GetMapping("/attendance/current")
    public ResponseEntity<AttendanceService.Record> current() {
        AttendanceService.Record r = attendance.current();
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    @PostMapping("/attendance/check-in")
    public AttendanceService.Record checkIn(@Valid @RequestBody(required = false) AttendanceService.Note req) {
        return attendance.checkIn(req);
    }

    @PostMapping("/attendance/check-out")
    public AttendanceService.Record checkOut(@Valid @RequestBody(required = false) AttendanceService.Note req) {
        return attendance.checkOut(req);
    }

    @GetMapping("/attendance")
    public List<AttendanceService.Record> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) UUID userId) {
        return attendance.list(from, to, userId);
    }

    @GetMapping("/attendance/summary")
    public List<AttendanceService.PersonSummary> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendance.summary(from, to);
    }

    @PutMapping("/attendance/{id}")
    public AttendanceService.Record edit(@PathVariable UUID id, @Valid @RequestBody AttendanceService.Edit req) {
        return attendance.edit(id, req);
    }

    @GetMapping("/settings/attendance")
    public AttendanceService.Settings settings() {
        return attendance.settings();
    }

    @PutMapping("/settings/attendance")
    public AttendanceService.Settings updateSettings(@RequestBody AttendanceService.Settings req) {
        return attendance.updateSettings(req);
    }
}
