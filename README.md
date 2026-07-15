# Deadhead ✈️

*"Deadhead" (aviation): flying an empty plane just to reposition it — the expensive habit this app exists to question.*

A web app for a private pilot flying client trips in a Cirrus SR22. Drop in a
week's calendar (`.ics`); after every leg the optimizer weighs the real choices —
fly the plane home, **leave it there and Uber back**, hotel overnight — and
returns the provably cheapest plan for the week.

## Try it live

https://deadhead-planner-71dc1c210944.herokuapp.com/login

## Run it locally

```bash
mvn spring-boot:run
```

Opens http://localhost:8787. Set **Home base** (KTMB) and, if the plane is
stranded somewhere from last week, **Plane is at** — the optimizer plans the
fetch too. Drop an `.ics` exported from Apple Calendar (File → Export →
Export…). A `from,to,date,time` `.csv` works too.

- **With `ANTHROPIC_API_KEY` set**, Claude reads free-text events
  ("fly the Hendersons to Love Field, wheels up 9").
- **Without a key**, event titles must contain airport codes (ADS, KDAL, AUS...).
  Non-flight events (dentist, birthdays) are skipped either way.

Tune `data/costs.properties` — fuel/hr, hotel, uber rates, what an hour of your
time is worth. Change a number, re-drop the file, the plan reorganizes.

## How it works

**AI parses at the edges, search decides in the middle.**

```
.ics  ──►  IcsParser  ──►  CalendarParser (Claude, structured outputs)
                            or airport-code fallback
                    ▼
      trips ──► Planner (rules) ──► RoutePlanner (Dijkstra) ──► browser
                    ▲
     costs.properties + OurAirports db (85k airports) via GeoCostModel
```

A **node** (`State`) is `(planeAt, dadAt, time, tripsRemaining)`. An **edge**
(`Action`) is one decision — fly a trip, deadhead empty, Uber (plane stays!),
overnight — priced by the `CostModel`. The optimal plan is literally the
shortest path from `(home, home, week start)` to `(home, home, all trips done)`.

```
src/main/java/deadhead/
  Airport, Trip, State, Action    the data model (records)
  CostModel / GeoCostModel        search decides, cost model estimates
  AirportDb                       OurAirports lookup: TMB / KTMB / IATA all work
  IcsParser                       .ics -> events (start time + human text)
  CalendarParser                  Claude turns event text into trips
  WeekFile                        csv fallback
  Planner                         legal moves from a state + their effects
  RoutePlanner                    Dijkstra + plan reconstruction
  HabitPlan                       the "always fly home" baseline
  PlanService                     file -> trips -> plan (the domain service)
  web/PlanController              POST /api/plan (Spring Boot REST)
  web/GlobalExceptionHandler      RFC-7807 problem responses
  DeadheadApplication             Spring Boot entry point + beans
src/main/resources/
  static/index.html               the page
  application.properties          port 8787, upload limits
```

Rules worth knowing (they were bugs once):

- Waiting for a **same-day** departure is free; waiting across a night must go
  through `Overnight` so hotels get charged.
- Uber moves only dad. Deadheading moves plane *and* dad.
- Calendar times with a timezone are kept **as written** — the pilot's
  wall-clock schedule is the truth; only pure-UTC stamps get converted.
- A state where a remaining trip's departure already passed is dead — pruned.

## Next ideas

- [ ] Real numbers from dad (fuel $/hr, hotel prices, his time value)
- [ ] Edit costs in the web page instead of the properties file
- [ ] Per-airport overnight hangar/tie-down fees
- [ ] Weather/cancellation risk in the cost model
