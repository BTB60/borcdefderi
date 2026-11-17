package az.borcdefteri.admin;

import az.borcdefteri.admin.AdminCrmService.Note;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin notes / follow-ups / tags per store and the platform Excel export (SUPER_ADMIN, /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminCrmController {

    public record NoteRequest(@NotBlank @Size(max = 1000) String body, LocalDate followUpOn) {}

    public record DoneRequest(boolean done) {}

    public record TagsRequest(@Size(max = 8) List<@Size(max = 30) String> tags) {}

    private final AdminCrmService crm;

    public AdminCrmController(AdminCrmService crm) {
        this.crm = crm;
    }

    @GetMapping("/stores/{id}/notes")
    public List<Note> notes(@PathVariable UUID id) {
        return crm.notes(id);
    }

    @PostMapping("/stores/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public Note addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest req) {
        return crm.addNote(id, req.body(), req.followUpOn());
    }

    @PostMapping("/notes/{noteId}/done")
    public Note done(@PathVariable UUID noteId, @RequestBody DoneRequest req) {
        return crm.setDone(noteId, req.done());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID noteId) {
        crm.deleteNote(noteId);
    }

    @GetMapping("/follow-ups")
    public List<Note> followUps() {
        return crm.dueFollowUps();
    }

    @PutMapping("/stores/{id}/tags")
    public Map<String, Object> tags(@PathVariable UUID id, @Valid @RequestBody TagsRequest req) {
        return AdminCrmService.tagsResponse(crm.setTags(id, req.tags()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        AdminCrmService.ExcelFile f = crm.export();
        return az.borcdefteri.common.FileResponses.attachment(f.content(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", f.fileName());
    }
}
