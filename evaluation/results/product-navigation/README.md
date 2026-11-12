# Navigation implementation and evaluation

SIGHT uses explicit spoken destination choices, permission-gated foreground location, real provider geometry, drift-resistant local matching, turn-distance cues, pause/resume/cancel/repeat, bounded automatic rerouting and explicit arrival confirmation. Camera-relative visual tasks retain separate provenance; a mapped route does not establish current sidewalk access or crossing clearance.

## Executed public-provider sample

The recorded [provider sample](provider-sample.json) made six fixed public-landmark searches and three pedestrian-route requests. All six queries returned at least one result and all three routes returned HTTP200 / provider `Ok`. These are three selected cases, not a route reliability estimate or an independent judgement of destination correctness.

| Fixed public trip | Provider distance | Geometry points | Steps | Selected map point to routed endpoint |
|---|---:|---:|---:|---:|
| Toronto Union Station → CN Tower |761.0m|43|13|33.1m|
| Ottawa City Hall → National Gallery |1368.7m|100|19|44.1m|
| Kingston City Hall → Queen's University |1630.6m|116|24|2.8m|

The first geocoder result was selected only for these predetermined public-provider checks. In the application the user hears numbered results and chooses explicitly, including when only one result is returned. No private user coordinates were collected. Request timing includes an intentional1.2-second policy delay and is not labelled HTTP-only latency.

The centroid-to-endpoint offsets exposed an actual arrival problem. Guidance now requires accurate GPS near the routed endpoint, bounded map-point snapping and explicit confirmation of the actual place; larger centroid offsets are spoken and entrances remain unverified. It never marks arrival from location alone.

## Constructed GNSS-policy replay

[Six scenarios](constructed-replays.json) contain217 constructed fix samples on the pinned, previously retrieved public Toronto route. They cover an exact mapped walk, alternating ±8m noise with12m reported accuracy, an endpoint teleport, inaccurate/stale endpoint fixes and persistent lateral disagreement. These are synthetic location-policy inputs over real map geometry, not recorded GNSS or physical walking outcomes.

The JVM `NavigationProductReplayTest` executes the actual production `RouteProgressEngine`, records every state/instruction and fails on false arrival suggestions in labelled negative cases, unsupported clearance statements, wrong terminal states or false off-route events in the route/drift scenarios. Its raw output is generated at `app/build/reports/product-navigation/evaluation.json`. Until that test executes, no replay result is claimed. The collection script refuses to publish a stale-source replay summary.

```powershell
# Root coordinates Gradle separately from emulators.
./gradlew.bat :app:testDebugUnitTest --tests 'org.sight.app.navigation.*' --max-workers=1 --console=plain
python evaluation/navigation_product.py report
# Recollect public HTTP sample only into a new directory, respecting service policies.
python evaluation/navigation_product.py all --output evaluation/results/navigation-reproduction
```

## Provider decision and evidence

| Candidate | Relevant capability | Credential/operation constraint | Current conclusion |
|---|---|---|---|
| Photon + FOSSGIS OSRM foot | Real worldwide place search and foot-route geometry/steps; already integrated | Low-volume community services, no service-level guarantee; route requests can be logged | Working default; no accessibility-informed or offline-route claim |
| Valhalla pedestrian `type: blind` | Documents additional mapped instructions; SIGHT retains typed stairs/elevator/escalator/entrance/exit and gate/rough-surface/ferry fields | Public service or self-hosted regional graph; missing map data stays missing | Implemented as an explicit optional provider; public Toronto probe returned14steps including an escalator; no silent fallback |
| OpenRouteService | Explicit step avoidance and wheelchair kerb/incline/width restrictions | Hosted key/quotas or self-hosted service required; missing OSM tags remain missing | Useful configured alternative; not silently treated as step-free data |
| GraphHopper | Turn instructions, path details and custom routing models | Hosted API key and credit-based service, or self-hosted engine | No credential is bundled; no paid account or deployment created |
| Mapbox walking | Walking profile, route geometry and turn instructions | Requires an access token and usage account | No token is bundled; routing output alone cannot certify pedestrian accessibility |

FOSSGIS documents worldwide foot coverage, required attribution/map-fix link and a maximum of one request per second. SIGHT's shared transport serializes requests at a1.1-second minimum interval; the sample used1.2seconds. Public route requests can be logged. [FOSSGIS policy](https://routing.openstreetmap.de/about.html). Photon provides location bias and an Apache2-licensed self-hostable geocoder; its demo explicitly makes no availability guarantee. [Photon documentation](https://github.com/komoot/photon).

Valhalla's current documentation describes pedestrian `type: blind`, sidewalk/walkway factors and a step penalty. These are mapped-data options, not evidence of present physical conditions. [Official Valhalla reference](https://valhalla.github.io/valhalla/api/route/api-reference/). The [actual probe request and response](valhalla-investigation/request.json) are retained.

### Optional mapped route details

Say `enable mapped route details` or enable **Request additional mapped mobility details** in Routes before planning. SIGHT uses the native Valhalla format, pedestrian costing and `type: blind` at the actual public FOSSGIS endpoint. A provider change pauses existing guidance and retains the chosen destination. Say `start navigation` to request a new route, or use route rehearsal from an explicitly selected origin. `use standard routing` selects the ordinary OSRM foot provider. A failed Valhalla request remains a failure; the app offers this explicit alternative instead of silently switching providers.

The [native response](valhalla-investigation/toronto-blind-native.json) and [independently serialized GeoJSON response](valhalla-investigation/toronto-blind-osrm.json) describe the same public Toronto request. They contain34geometrypoints and14maneuvers; native maneuver41 identifies one mapped escalator, and nine maneuvers have an explicit rough-surface flag. The new JVM test checks native latitude/longitude polyline6 decoding against the actual GeoJSON coordinates, distance units, typed map features and attribution language. It rejects non-pedestrian maneuvers, unsupported units, malformed shapes and untyped feature flags. Failure/consent tests verify that no secondary provider is contacted. The first test execution exposed an incorrect assertion that assumed the escalator was the only mapped feature in the summary; the corrected assertion verifies both actual feature types and the nine rough flags. Execution is claimed only after the versioned validation manifest and `valhalla-parser.json` record its pass.

Known typed features come from native provider fields, never guessed from street names. SIGHT does not forward arbitrary provider prose as movement authorization. The current adapter does not retain every additional instruction exposed by the `blind` profile, and requesting it does not establish complete sidewalk/crossing coverage, step-free travel or present accessibility. An actual escalator map instruction is map evidence, not camera detection or proof that the equipment operates. The public service has no application-specific availability guarantee; deployments serving many users need appropriate provider capacity.

OpenRouteService documents step avoidance and wheelchair restrictions; those options depend on underlying map tags. [Official routing options](https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/routing-options). GraphHopper documents API-key authentication, credit-based pricing and custom models. [Official API reference](https://docs.graphhopper.com/openapi). Mapbox documents the walking profile and access-token requirement. [Official Directions reference](https://docs.mapbox.com/api/navigation/directions/).

The current application does not bundle offline routing graphs or promise complete accessibility metadata. The OSRM engine is separately available under its [upstream two-clause license](https://github.com/Project-OSRM/osrm-backend/blob/master/LICENSE.TXT); consuming a public service does not embed that engine. All saved response data retains OpenStreetMap / Photon / FOSSGIS attribution. A production deployment serving many users needs an appropriately provisioned provider rather than heavy use of community demo endpoints.
