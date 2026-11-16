package az.borcdefteri.announcements;

import az.borcdefteri.announcements.AnnouncementService.AnnouncementInput;
import az.borcdefteri.announcements.AnnouncementService.AnnouncementView;
import az.borcdefteri.subscriptions.PromoService;
import az.borcdefteri.subscriptions.PromoService.PromoInput;
import az.borcdefteri.subscriptions.PromoService.PromoView;
import az.borcdefteri.subscriptions.PromoService.TrialSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Announcements (every signed-in user reads, the admin writes), promo codes and the free trial (admin). */
@RestController
public class AnnouncementController {

    public record TrialUpdate(@Min(0) @Max(90) int days, String planCode) {}

    private final AnnouncementService announcements;
    private final PromoService promos;

    public AnnouncementController(AnnouncementService announcements, PromoService promos) {
        this.announcements = announcements;
        this.promos = promos;
    }

    @GetMapping("/api/announcements")
    public List<AnnouncementView> current() {
        return announcements.current();
    }

    @GetMapping("/api/admin/announcements")
    public List<AnnouncementView> all() {
        return announcements.all();
    }

    @PostMapping("/api/admin/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementView create(@RequestBody AnnouncementInput in) {
        return announcements.create(in);
    }

    @PutMapping("/api/admin/announcements/{id}")
    public AnnouncementView update(@PathVariable UUID id, @RequestBody AnnouncementInput in) {
        return announcements.update(id, in);
    }

    @DeleteMapping("/api/admin/announcements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        announcements.delete(id);
    }

    @GetMapping("/api/admin/promo-codes")
    public List<PromoView> promoCodes() {
        return promos.list();
    }

    @PostMapping("/api/admin/promo-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public PromoView createPromo(@RequestBody PromoInput in) {
        return promos.create(in);
    }

    @PutMapping("/api/admin/promo-codes/{id}")
    public PromoView updatePromo(@PathVariable UUID id, @RequestBody PromoInput in) {
        return promos.update(id, in);
    }

    @GetMapping("/api/admin/trial")
    public TrialSettings trial() {
        return promos.trial();
    }

    @PutMapping("/api/admin/trial")
    public TrialSettings updateTrial(@Valid @RequestBody TrialUpdate in) {
        return promos.updateTrial(in.days(), in.planCode());
    }
}
