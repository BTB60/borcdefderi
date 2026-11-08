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

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
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
