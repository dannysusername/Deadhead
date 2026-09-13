package deadhead;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends calendar events Claude's way when pattern-matching couldn't read them.
 * Structured outputs guarantee the response matches the schema — no fragile
 * text parsing on our side. Each event is numbered so results map back to
 * their source event (that's what makes caching possible).
 *
 * Every call is billed to the pilot's own key, passed in per request. There is
 * deliberately no fallback to an operator key or to ANTHROPIC_API_KEY: a bug
 * that quietly bills the wrong account is worse than a parse that doesn't run.
 * Without a key the airport-code fallback carries the whole import.
 */
@Service
public class CalendarParser {

    private static final String MODEL = "claude-opus-5";

    /** Anthropic keys look like sk-ant-… — enough to catch a paste of the wrong string. */
    private static final String KEY_PREFIX = "sk-ant-";

    public record ParsedFlight(
        @JsonPropertyDescription("The number of the calendar event this flight came from")
        int event,
        @JsonPropertyDescription("Departure airport code (ICAO like KTMB preferred; local or IATA ok)")
        String from,
        @JsonPropertyDescription("Arrival airport code")
        String to
    ) {}

    public record ParsedFlights(
        @JsonPropertyDescription("One entry per event that is a flight. Omit events that are not flights.")
        List<ParsedFlight> flights
    ) {}

    public static boolean looksLikeKey(String key) {
        return key != null && key.trim().startsWith(KEY_PREFIX) && key.trim().length() > 20;
    }

    private static AnthropicClient clientFor(String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    /**
     * One cheap round-trip so a typo is caught while the pilot is still looking
     * at the Settings panel, instead of silently eating their next import.
     */
    public void verify(String apiKey) {
        if (!looksLikeKey(apiKey)) {
            throw new IllegalArgumentException("That doesn't look like an Anthropic API key (they start with sk-ant-).");
        }
        try {
            clientFor(apiKey).models().list();
        } catch (Exception rejected) {
            throw new IllegalArgumentException(
                "Anthropic wouldn't accept that key. Check it was copied whole, and that it's still active.");
        }
    }

    /** @param numberedEvents lines like "3 | 2026-07-15T09:00 | fly the Rosens to Key West" */
    public ParsedFlights parse(String apiKey, List<String> numberedEvents, Airport home) {
        String prompt = """
            These are a private pilot's calendar events, one per line: "number | start | title".
            The pilot's home base is %s.

            For each event that is a FLIGHT, return its number and the departure/arrival
            airport codes. Rules:
            - City or place names ("Key West", "Naples") -> that city's main GA-friendly airport code.
            - Ignore events that aren't flights (dinners, maintenance, birthdays...).
            - If a flight names only a destination, the departure is unknowable here - still
              return it with your best inference from the title alone.

            Events:
            %s
            """.formatted(home.code(), String.join("\n", numberedEvents));

        StructuredMessageCreateParams<ParsedFlights> params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(ParsedFlights.class)
            .addUserMessage(prompt)
            .build();

        return clientFor(apiKey).messages().create(params).content().stream()
            .flatMap(block -> block.text().stream())
            .map(typed -> typed.text())   // typed.text() is a ParsedFlights, not a String
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("model returned no structured content"));
    }
}
