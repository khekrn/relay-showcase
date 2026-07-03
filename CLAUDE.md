# CLAUDE.md

**relay-showcase** — 18 live end-to-end scenarios against a running relay-server (the engine repo is
`khekrn/relay`; READ ITS CLAUDE.md FIRST — it holds the architecture invariants, gotchas, and session handoff).

Run: start relay-server (see relay/CLAUDE.md; demo knobs `RELAY_VISIBILITY_MS=2000 RELAY_POLL_MS=250`), then
`mvn clean package -DskipTests && java -jar target/relay-showcase-1.0.0.jar`. Scenario 17 (poison/DLQ) only
demos fully with a low threshold server-side:
`SPRING_APPLICATION_JSON='{"relay":{"poison":{"max-attempts":3},"retry":{"default":{"interval":50,"interval-type":"FIXED","jitter":0.0}}}}'.

Gotchas: workflow JSON binds tasks via the `"task"` field (`taskName` is a deprecated alias); after ANY relay
SDK change, `mvn install` in relay then `mvn clean package` HERE (plain `package` keeps stale libs inside the
Boot jar). Tasks are typed records + separate @Component classes (user preference — keep it that way).
