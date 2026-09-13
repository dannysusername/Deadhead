package deadhead.web;

import deadhead.Airport;
import deadhead.CalendarParser;
import deadhead.CalendarSyncService;
import deadhead.FlightStore;
import deadhead.PlanService;
import deadhead.PlaneLocator;
import deadhead.Trip;
import deadhead.user.CurrentUser;
import deadhead.user.UserSettings;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Everything the page calls. Each handler starts by asking who is signed in
 * and passes that id down — no request can reach another account's schedule,
 * because no method below can be called without one.
 */
@RestController
public class PlanController {

    /** An .ics is text; this bounds what one upload can cost us before parsing. */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final PlanService planner;
    private final FlightStore store;
    private final PlaneLocator locator;
    private final UserSettings settings;
    private final CalendarSyncService syncService;
    private final CalendarParser claude;
    private final CurrentUser current;

    public PlanController(PlanService planner, FlightStore store, PlaneLocator locator,
                          UserSettings settings, CalendarSyncService syncService,
                          CalendarParser claude, CurrentUser current) {
        this.planner = planner;
        this.store = store;
        this.locator = locator;
        this.settings = settings;
        this.syncService = syncService;
        this.claude = claude;
        this.current = current;
    }

    /** "Find my plane": live ADS-B if flying, otherwise the schedule's last arrival. */
    @GetMapping("/api/locate")
    public PlaneLocator.Location locate() {
        long userId = current.id();
        return locator.locate(userId, settings.forUser(userId));
    }

    /** Editable knobs: costs, plane, calendar link. */
    @GetMapping("/api/settings")
    public Map<String, String> settings() {
        return settings.all(current.id());
    }

    @PostMapping(path = "/api/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> saveSettings(@RequestBody Map<String, String> changes) {
        return settings.update(current.id(), changes);
    }

    public record ApiKeyRequest(String apiKey) {}

    /**
     * Save this pilot's own Anthropic key. It is verified against Anthropic
     * before being stored, encrypted, and it is never readable back — the
     * response carries only a hint of the last four characters.
     */
    @PostMapping(path = "/api/apikey", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveApiKey(@RequestBody ApiKeyRequest body) {
        long userId = current.id();
        String key = body.apiKey() == null ? "" : body.apiKey().trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Paste your Anthropic API key.");

        claude.verify(key);
        settings.setApiKey(userId, key);
        return Map.of("ai", true, "apiKeyHint", settings.apiKeyHint(userId));
    }

    /** Forget it. The next import falls back to airport-code parsing. */
    @DeleteMapping("/api/apikey")
    public Map<String, Object> deleteApiKey() {
        long userId = current.id();
        settings.setApiKey(userId, null);
        return Map.of("ai", false, "apiKeyHint", "");
    }

    /** Re-fetch the published calendar now. */
    @PostMapping("/api/sync")
    public CalendarSyncService.SyncResult sync() {
        return syncService.syncNow(current.id());
    }

    /** Everything the page needs on load. */
    @GetMapping("/api/config")
    public Map<String, Object> config() {
        long userId = current.id();
        Properties cfg = settings.forUser(userId);
        return Map.of(
            "email", current.account().email(),
            "home", planner.defaultHome(cfg),
            "flights", store.flightCount(userId),
            "weeks", store.weeksWithFlights(userId),
            // "does free-text parsing work for me" — i.e. have I added my own key
            "ai", settings.apiKey(userId).isPresent(),
            "apiKeyHint", settings.apiKeyHint(userId));
    }

    /** Import a calendar once; every event is parsed and saved. */
    @PostMapping(path = "/api/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FlightStore.ImportStats importCalendar(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "home", defaultValue = "") String home) throws java.io.IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("That file is empty.");
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("That calendar is too large (10 MB max).");
        }
        long userId = current.id();
        Airport homeBase = homeBase(home, userId);
        return store.importIcs(userId, new String(file.getBytes(), StandardCharsets.UTF_8),
                               homeBase, settings.apiKey(userId).orElse(null));
    }

    /** Flights in a date range, for the picker. Conflicts arrive pre-flagged. */
    @GetMapping("/api/flights")
    public List<FlightStore.FlightView> flights(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("That date range runs backwards.");
        return store.flightsBetween(current.id(), from, to);
    }

    /** Start over: forget every imported event for this account. */
    @DeleteMapping("/api/flights")
    public Map<String, Object> clearFlights() {
        long userId = current.id();
        store.clear(userId);
        return Map.of("flights", 0);
    }

    public record PlanRequest(List<String> ids, String home, String planeAt) {}

    /** Plan exactly the flights the user checked. */
    @PostMapping(path = "/api/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlanService.Result plan(@RequestBody PlanRequest req) {
        long userId = current.id();
        Properties cfg = settings.forUser(userId);

        Airport homeBase = homeBase(req.home() == null ? "" : req.home(), userId);
        String planeAt = req.planeAt() == null ? "" : req.planeAt();
        Airport plane = planeAt.isBlank() ? homeBase : planner.airport(planeAt);

        List<Trip> trips = store.tripsForIds(userId, req.ids() == null ? List.of() : req.ids());
        if (trips.isEmpty()) throw new IllegalArgumentException("No flights selected.");

        Map<String, String> labels = store.labels(userId);
        planner.precheck(trips, id -> labels.getOrDefault(id, id), homeBase, cfg);
        return planner.plan(trips, homeBase, plane, cfg);
    }

    /**
     * The typed box wins; otherwise the saved setting. A brand-new account has
     * neither, and saying so beats resolving a home base they never chose.
     */
    private Airport homeBase(String typed, long userId) {
        String code = typed.isBlank()
            ? planner.defaultHome(settings.forUser(userId))
            : typed.trim();
        if (code.isBlank()) {
            throw new IllegalArgumentException("Set your home base first (the box at the top).");
        }
        return planner.airport(code);
    }
}
