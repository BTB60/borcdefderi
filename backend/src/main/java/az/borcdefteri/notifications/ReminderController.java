package az.borcdefteri.notifications;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Avtomatik borc xatırlatmaları: today's list, mark sent / skip, and the store's settings. */
@RestController
@RequestMapping("/api")
public class ReminderController {

    private final ReminderService reminders;

    public ReminderController(ReminderService reminders) {
        this.reminders = reminders;
    }

    @GetMapping("/reminders")
    public List<ReminderService.Reminder> list(@RequestParam(required = false) String status) {
        return reminders.list(status);
    }

    @PostMapping("/reminders/{id}/sent")
    public Map<String, Boolean> sent(@PathVariable UUID id, @RequestBody(required = false) ReminderService.Handle req) {
        reminders.markSent(id, req);
        return Map.of("ok", true);
    }

    /** Sends every pending reminder by SMS now (needs the platform's SMS provider). */
    @PostMapping("/reminders/send-sms")
    public Map<String, Integer> sendSms() {
        return Map.of("sent", reminders.sendAllBySms());
    }

    @PostMapping("/reminders/{id}/skip")
    public Map<String, Boolean> skip(@PathVariable UUID id) {
        reminders.skip(id);
        return Map.of("ok", true);
    }

    @GetMapping("/settings/reminders")
    public ReminderService.Settings settings() {
        return reminders.settings();
    }

    @PutMapping("/settings/reminders")
    public ReminderService.Settings update(@RequestBody ReminderService.Settings req) {
        return reminders.updateSettings(req);
    }
}
