package az.borcdefteri.dashboard;

import az.borcdefteri.dashboard.DashboardService.Dashboard;
import az.borcdefteri.dashboard.DashboardService.Notification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboard;
    private final DashboardListService lists;

    public DashboardController(DashboardService dashboard, DashboardListService lists) {
        this.dashboard = dashboard;
        this.lists = lists;
    }

    /** HesabPro 2.0 card figures (each equals the total of its list). */
    @GetMapping("/dashboard/overview")
    public DashboardListService.Overview overview() {
        return lists.overview();
    }

    /** The list behind a dashboard card, e.g. /api/dashboard/lists/active-debts?q=&from=&to=&partyKind=&page=&size= */
    @GetMapping("/dashboard/lists/{kind}")
    public DashboardListService.Page<?> list(
            @org.springframework.web.bind.annotation.PathVariable String kind,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String partyKind,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size) {
        return lists.list(kind, q, from, to, partyKind, page, size);
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/notifications")
    public List<Notification> notifications() {
        return dashboard.notifications();
    }
}
