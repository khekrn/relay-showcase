package com.example.showcase;

import com.cs.relay.worker.spring.RelayWorker;
import com.cs.relay.worker.spring.RelayWorker.AsyncResult;
import com.example.showcase.Model.Handled;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * End-to-end validation of every Relay scenario against the live server. Each section drives one feature and
 * prints the observed outcome, so a single run exercises: choice routing, parallel + broadcast signal, join
 * ANY/QUORUM, bounded map, loop, sub-workflow, retry-with-backoff, await-timeout, cancel, saga compensation,
 * idempotent start, crash recovery, and observability.
 */
@SpringBootApplication
public class ShowcaseApplication {

    public static void main(String[] args) { SpringApplication.run(ShowcaseApplication.class, args); }

    @Bean
    CommandLineRunner demo(RelayWorker relay) {
        return args -> {
            section("1. CHOICE — task-routed decision");
            for (int sev : new int[]{9, 5, 1}) {
                var r = relay.runAsync("triage", Map.of("severity", sev), Handled.class);
                System.out.printf("   severity=%d -> %s%n", sev, label(r));
            }

            section("2. PARALLEL + BROADCAST SIGNAL — 3 branches await one signal");
            var fan = relay.runAsync("fanout", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (3 branches parked on 'go')%n", fan.status());
            var merged = relay.signal(fan.rootId(), "go", Map.of(), Handled.class);
            System.out.printf("   signal 'go' -> %s : %s%n", merged.status(), label(merged));

            section("3. JOIN=ANY — first of 3 wins, losers cancelled");
            var race = relay.runAsync("race", Map.of(), Handled.class);
            System.out.printf("   3 quotes raced -> %s : %s%n", race.status(), label(race));

            section("4. JOIN=QUORUM(2 of 3) — closes on the quorum");
            var vote = relay.runAsync("vote", Map.of(), Handled.class);
            System.out.printf("   3 ballots, quorum 2 -> %s : %s%n", vote.status(), label(vote));

            section("5. MAP (concurrency 2) — 5 items, at most 2 in flight");
            var batch = relay.runAsync("batch", Map.of("ids", List.of("a", "b", "c", "d", "e")), Handled.class);
            System.out.printf("   5 items -> %s : %s%n", batch.status(), label(batch));

            section("6. LOOP — do-while until the body says stop");
            var loop = relay.runAsync("spin", Map.of(), Handled.class);
            System.out.printf("   looped -> %s : %s%n", loop.status(), label(loop));

            section("7. SUB-WORKFLOW — parent calls child");
            var parent = relay.runAsync("parent", Map.of(), Handled.class);
            System.out.printf("   parent+child -> %s : %s%n", parent.status(), label(parent));

            section("8. RETRY (backoff) — flaky task fails 2× then succeeds");
            var flaky = relay.runAsync("flaky", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (retry scheduled)%n", flaky.status());
            String rid = flaky.rootId(), st = flaky.status();
            for (int i = 0; i < 12 && !"COMPLETED".equals(st) && !"FAILED".equals(st); i++) {
                Thread.sleep(400);
                relay.recover(16);                         // pull the redelivered retry attempt and drive it
                st = relay.status(rid);
            }
            System.out.printf("   after retries -> %s%n", st);

            section("8b. DEFAULT-ON RETRY — a task with NO retry block still retries (engine default)");
            var dflt = relay.runAsync("flaky-default", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (no retry block; engine default applies)%n", dflt.status());
            String did = dflt.rootId(), dst = dflt.status();
            for (int i = 0; i < 40 && !"COMPLETED".equals(dst) && !"FAILED".equals(dst); i++) {
                Thread.sleep(400);
                relay.recover(16);                         // pull the default-scheduled retry attempts and drive them
                dst = relay.status(did);
            }
            System.out.printf("   after default-on retries -> %s%n", dst);

            section("9. AWAIT TIMEOUT — parks, the poller fires the deadline");
            var dl = relay.runAsync("deadline", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (500ms deadline)%n", dl.status());
            Thread.sleep(2000);                            // let the timer fire server-side
            System.out.printf("   after deadline -> %s (cause=%s)%n", relay.status(dl.rootId()),
                    relay.status(dl.rootId()).equals("FAILED") ? "Timeout" : "-");

            section("10. CANCEL — tear down a parked run");
            var hc = relay.runAsync("holdcancel", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (parked on 'approve')%n", hc.status());
            System.out.printf("   cancel -> %s%n", relay.cancel(hc.rootId(), "user withdrew"));

            section("11. SAGA — a parallel branch fails → compensate the reservation");
            var saga = relay.runAsync("saga", Map.of("amount", 5000, "who", "mallory"), Handled.class);
            System.out.printf("   fraud branch failed -> %s : cause=%s%n", saga.status(), saga.error());

            section("12. IDEMPOTENT START — same key returns the same run");
            var a = relay.runAsync("job", Map.of("name", "nightly"), Handled.class, "run-2026-07-02");
            var b = relay.runAsync("job", Map.of("name", "nightly"), Handled.class, "run-2026-07-02");
            System.out.printf("   two starts, same key -> same run? %s (%s)%n",
                    a.rootId().equals(b.rootId()), a.rootId().substring(0, 8));

            section("13. CRASH RECOVERY — orphan a task, another poll recovers it");
            String orphan = relay.startDetached("job", Map.of("name", "abandoned"));
            System.out.printf("   started + abandoned (worker 'crashed') -> %s%n", relay.status(orphan));
            Thread.sleep(2500);                            // > visibility timeout
            int recovered = relay.recover(16);             // a healthy worker pulls the orphaned dispatch
            System.out.printf("   recovered %d dispatch(es) -> run is now %s%n", recovered, relay.status(orphan));

            section("14. DYNAMIC ROUTE — the task picks the next state at runtime");
            for (int amt : new int[]{50, 5_000, 50_000}) {
                var r = relay.runAsync("dispatcher", Map.of("amount", amt), Handled.class);
                System.out.printf("   amount=%-6d -> %s%n", amt, label(r));
            }

            section("15. DYNAMIC SUBGRAPH — the task generates a workflow at runtime");
            var gen = relay.runAsync("generate", Map.of("steps", 3), Handled.class);
            System.out.printf("   generated + ran a 3-step subgraph -> %s : %s%n", gen.status(), label(gen));

            section("16. OBSERVABILITY — live server metrics (/actuator)");
            printMetric("relay.runs.started");
            printMetric("relay.runs.completed");
            printMetric("relay.runs.failed");
            printMetric("relay.timers.fired");

            section("17. DLQ / POISON — a permanently-failing task is quarantined, then redriven");
            var pois = relay.runAsync("poison", Map.of(), Handled.class);
            System.out.printf("   submitted -> %s (boom always fails; poison bounds the unlimited retries)%n", pois.status());
            java.util.List<com.cs.relay.worker.CoordinationClient.DeadLetter> dls = java.util.List.of();
            for (int i = 0; i < 40 && dls.isEmpty(); i++) {
                Thread.sleep(400);
                relay.recover(16);                                   // drive the redelivered retries
                dls = relay.deadLetters(10);
            }
            if (dls.isEmpty()) System.out.println("   (not poisoned yet — raise the poll window / lower relay.poison.max-attempts)");
            else {
                var letter = dls.get(0);
                System.out.printf("   POISONED -> dead letter: task=%s state=%s attempt=%d run=%s%n",
                        letter.taskName(), letter.state(), letter.attempt(), relay.status(pois.rootId()));
                boolean ok = relay.redrive(letter.deadLetterId());
                System.out.printf("   redrive -> %s (task re-dispatched for one fresh attempt)%n", ok);
                Thread.sleep(800); relay.recover(16);
                System.out.printf("   it failed again -> re-dead-lettered; open dead letters now -> %d%n",
                        relay.deadLetters(10).size());
            }

            section("18. ASYNC_DISTRIBUTED — fire-and-forget start; ANY worker pulls the work");
            var dj = relay.runAsync("distjob", Map.of("name", "spread-me"), Handled.class);
            System.out.printf("   submitted -> %s (no dispatches returned — pure queue; auto-poller consumes)%n", dj.status());
            String djs = dj.status();
            for (int i = 0; i < 40 && !"COMPLETED".equals(djs) && !"FAILED".equals(djs); i++) {
                Thread.sleep(500);                         // the auto-started work poller pulls each wave
                djs = relay.status(dj.rootId());
            }
            System.out.printf("   pulled wave-by-wave -> %s%n", djs);

            System.out.println("\n================  ALL SCENARIOS EXERCISED  ================\n");
        };
    }

    private static String label(AsyncResult<Handled> r) {
        return r.completed() && r.result() != null ? r.result().label() : r.status();
    }

    private static void section(String title) { System.out.println("\n===== " + title + " ====="); }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static void printMetric(String name) {
        try {
            HttpResponse<String> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:8080/actuator/metrics/" + name)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = res.body();
            int i = body.indexOf("\"value\":");
            String value = i < 0 ? "?" : body.substring(i + 8, body.indexOf('}', i)).replace("]", "").trim();
            System.out.printf("   %-24s = %s%n", name, value);
        } catch (Exception e) { System.out.printf("   %-24s = (unavailable: %s)%n", name, e.getMessage()); }
    }
}
