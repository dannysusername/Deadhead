package deadhead;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Sends calendar events Claude's way when pattern-matching couldn't read them.
 * Structured outputs guarantee the response matches the schema — no fragile
 * text parsing on our side. Each event is numbered so results map back to
 * their source event (that's what makes caching possible).
 */
public class CalendarParser {

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

    /** @param numberedEvents lines like "3 | 2026-07-15T09:00 | fly the Rosens to Key West" */
    public static ParsedFlights parse(List<String> numberedEvents, Airport home) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();   // reads ANTHROPIC_API_KEY

        String prompt = """
            These are a private pilot's calendar events, one per line: "number | start | title".
            The pilot's home base is %s (Miami area).

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
            .model("claude-opus-4-8")
            .maxTokens(16000L)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(ParsedFlights.class)
            .addUserMessage(prompt)
            .build();

        return client.messages().create(params).content().stream()
            .flatMap(block -> block.text().stream())
            .map(typed -> typed.text())   // typed.text() is a ParsedFlights, not a String
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("model returned no structured content"));
    }
}
