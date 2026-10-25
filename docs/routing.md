# Route provider and progress module

Implemented in `app/src/main/java/org/sight/app/navigation`. Source review and public smoke retrieval: **2026-10-23**. This is provider-backed route planning and GPS-to-route estimation, with separate local visual perception. It does not infer a traversable route from the camera or certify accessibility.

## Public services and consent

`PublicWalkingRouteProvider` requires a `networkAllowed` callback; both request dispatch and result delivery are gated. `search(query)` sends the typed place query to Photon/komoot; `route(origin, destination)` sends the origin and selected destination to FOSSGIS. The host must obtain explicit consent, cancel its request coroutine on revocation and expose stop. No camera frames or detection observations are sent. Providers can log requests; the FOSSGIS service explicitly documents route request logging. A public service has no uptime or latency guarantee.

Photon's official documentation describes `/api?q=...`, result limits and GeoJSON points in longitude/latitude order. Its demo server permits reasonable usage and may throttle/ban heavy clients. SIGHT searches only after an explicit submitted query, with a five-result limit. [Photon API](https://github.com/komoot/photon/blob/master/docs/api-v1.md), [Photon demo usage](https://github.com/komoot/photon).

FOSSGIS's actual live frontend configuration selects `https://routing.openstreetmap.de/routed-foot/route/v1/foot/` for walking. The prefix matters: an arbitrary profile word in an OSRM URL does not change the server's prepared profile. SIGHT does not use the common car demo backend for walking. [Live service configuration](https://routing.openstreetmap.de/), [OSRM protocol/profile documentation](https://project-osrm.org/docs/v5.24.0/api/).

FOSSGIS requires attribution, a fix-map link, an identifying User-Agent, at most one request per second, and light use. The shared transport spaces request starts by at least 1.1 seconds, disables redirects, supplies a User-Agent, bounds input at 2 MB, and uses 8-second connect/read timeouts plus a bounded body-read deadline. Coroutine cancellation disconnects the active request. Production scale requires a suitable contracted or self-hosted provider. [Operator policy/privacy](https://routing.openstreetmap.de/about.html).

The host should display `PublicWalkingRouteProvider.ATTRIBUTION`, `MAP_FIX_URL` and `SERVICE_POLICY_URL`. Search labels and steps originate in map/provider data and are not observations by SIGHT.

## Typed contract

```kotlin
val provider = PublicWalkingRouteProvider(networkAllowed = { networkConsent })
val matches: List<Destination> = provider.search("CN Tower Toronto")
val route: WalkingRoute = provider.route(GeoPoint(latitude, longitude), selectedDestination)
val rehearsal: String = route.rehearsal()
val engine = RouteProgressEngine(route)
val progress = engine.update(GpsFix(point, horizontalAccuracyMeters, capturedElapsedMs), nowElapsedMs)
val confirmed: Boolean = engine.confirmArrival(nowElapsedMs)
```

Android GPS timestamps must be converted from `Location.elapsedRealtimeNanos / 1_000_000`; use `SystemClock.elapsedRealtime()` for `nowElapsedMs`. Wall-clock timestamps are not interchangeable with either. Reject locations without an accuracy field before constructing a fix. Recreate the engine for every recalculated route, discard late results for cancelled/older destinations, and keep route work in a lifecycle-owned coroutine.

The route contains provider name, request source URL, retrieval time, GeoJSON geometry, structured maneuver type/modifier/road labels, and provider distance/duration. Rehearsal describes the provider's listed turns and approximate total duration. It does not say a road is safe to cross or that a path is clear. Parsers reject missing/degenerate geometry, invalid coordinates, unavailable routes, missing steps and excessive result sizes. This slice bounds straight-line trip requests to 10 m–50 km and route geometry to 150 km.

## Progress and uncertainty

GPS fixes must be at most 10 seconds old, have finite positive accuracy no worse than 25 m, and have increasing capture timestamps. Invalid/duplicate/future fixes produce `GPS_UNCERTAIN`, clear pending arrival and reset the off-route streak. This module uses horizontal projection onto provider geometry; it has no floor, heading or map-matching model. Parallel paths, concourses, urban canyon GPS errors and route self-intersections remain ambiguous.

Three consecutive valid fixes outside `max(35 m, 2 × reportedAccuracy)` produce `OFF_ROUTE`; the first two outliers report `GPS_UNCERTAIN` and withhold progress. This suggests requesting a new route; the engine never silently sends a location for recalculation. The host may request one under existing explicit network consent and must announce the provider failure if unavailable. Remaining distance normalizes geometric progress to the provider's route length; it is an estimate, not a measured accessible walking distance.

Arrival requires proximity to both the selected destination and routed endpoint, little remaining route distance, and **explicit user confirmation using a still-fresh fix**. GPS proximity alone is `ARRIVAL_CONFIRMATION_REQUIRED`; it never identifies the correct entrance. Confirmed arrival is a user action, not a camera verification result.

## Executed public-data smoke

One public Toronto query and one public walking-route request were fetched successfully. Exact URLs, retrieval UTC, raw JSON, SHA-256 and workstation network timings are stored in `evaluation/results/route-provider-smoke/metadata.json`. No personal GPS trace was used.

The route request between public coordinates near CN Tower and Union Station returned `code=Ok`, provider distance **820.5 m**, duration **656.2 s**, **61** geometry points and **15** steps. These are provider estimates, not measured walking performance. The geometry includes names such as **PATH** and **York Concourse**, illustrating that a walking profile can include indoor passages. SIGHT has not inspected their access, operating hours, stairs, accessible entrances, traffic controls or current conditions. The example does not validate indoor GPS or a usable route for blind people.

`RouteProviderTest` parses the saved real responses, checks coordinate order, provider provenance, malformed/no-route handling, consent revocation and cancellation. `RouteProgressEngineTest` exercises stale/poor/duplicate fixes, off-route persistence, arrival confirmation, geometric projection and date-line behavior. Host test execution status is reported by the main build record; the module itself has not been field-tested on GPS hardware.
