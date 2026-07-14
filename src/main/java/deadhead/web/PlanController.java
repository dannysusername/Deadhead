package deadhead.web;

import deadhead.Airport;
import deadhead.FlightStore;
import deadhead.PlanService;
import deadhead.Trip;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class PlanController {

    private final PlanService planner;
    private final FlightStore store;
    private final deadhead.PlaneLocator locator;
    private final deadhead.SettingsService settingsService;
    private final deadhead.CalendarSyncService syncService;

    public PlanController(PlanService planner, FlightStore store, deadhead.PlaneLocator locator,
                          deadhead.SettingsService settingsService,
                          deadhead.CalendarSyncService syncService) {
        this.planner = planner;
        this.store = store;
        this.locator = locator;
        this.settingsService = settingsService;
        this.syncService = syncService;
    }

    /** "Find my plane": live ADS-B if flying, otherwise the schedule's last arrival. */
    @GetMapping("/api/locate")
    public deadhead.PlaneLocator.Location locate() {
        return locator.locate();
    }

    /** Editable knobs: costs, plane, calendar link. */
    @GetMapping("/api/settings")
    public Map<String, String> settings() {
        return settingsService.all();
    }

    @PostMapping(path = "/api/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> saveSettings(@RequestBody Map<String, String> changes) throws IOException {
        return settingsService.update(changes);
    }

    /** Re-fetch the published calendar now. */
    @PostMapping("/api/sync")
    public deadhead.CalendarSyncService.SyncResult sync() {
        return syncService.syncNow();
    }

    /** Everything the page needs on load. */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of(
            "home", planner.defaultHome(),
            "flights", store.flightCount(),
            "weeks", store.weeksWithFlights());
    }

    /** Import a calendar once; every event is parsed and saved. */
    @PostMapping(path = "/api/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FlightStore.ImportStats importCalendar(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "home", defaultValue = "") String home) throws IOException {
        Airport homeBase = planner.airport(home.isBlank() ? planner.defaultHome() : home);
        return store.importIcs(new String(file.getBytes(), StandardCharsets.UTF_8), homeBase);
    }

    /** Flights in a date range, for the picker. Conflicts arrive pre-flagged. */
    @GetMapping("/api/flights")
    public List<FlightStore.FlightView> flights(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to) {
        return store.flightsBetween(from, to);
    }

    public record PlanRequest(List<String> ids, String home, String planeAt) {}

    /** Plan exactly the flights the user checked. */
    @PostMapping(path = "/api/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlanService.Result plan(@RequestBody PlanRequest req) {
        String home = req.home() == null ? "" : req.home();
        String planeAt = req.planeAt() == null ? "" : req.planeAt();
        Airport homeBase = planner.airport(home.isBlank() ? planner.defaultHome() : home);
        Airport plane = planeAt.isBlank() ? homeBase : planner.airport(planeAt);

        List<Trip> trips = store.tripsForIds(req.ids() == null ? List.of() : req.ids());
        if (trips.isEmpty()) throw new IllegalArgumentException("No flights selected.");

        planner.precheck(trips, store::label, homeBase);
        return planner.plan(trips, homeBase, plane);
    }
}
