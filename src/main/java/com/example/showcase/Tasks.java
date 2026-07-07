package com.example.showcase;

import com.cs.relay.worker.spring.RelayContext;
import com.cs.relay.worker.spring.Step;
import com.example.showcase.Model.*;
import org.springframework.stereotype.Component;

/**
 * All showcase task implementations — each a {@code @Step("name")} method whose name matches the workflow
 * taskName. The single record parameter binds the whole node input; the injected {@link RelayContext} carries
 * ambient metadata (attempt, execution/state ids) and the Dynamic-state hooks {@code routeTo}/{@code runSubgraph}.
 */
@Component
public class Tasks {

    private final RelayContext relay;

    public Tasks(RelayContext relay) { this.relay = relay; }

    @Step("handle")
    Handled handle(HandleIn in) {
        return new Handled(in.action() != null ? in.action() : "count=" + in.count());
    }

    @Step("process")
    ProcResult process(ProcOne in) { return new ProcResult(in.id(), true); }

    @Step("quote")
    Quote quote(QuoteIn in) { return new Quote(in.provider(), 100 + in.provider().hashCode() % 50); }

    @Step("tick")
    Ticked tick(Tick in) { return new Ticked(in.n() < 2, in.n()); }   // loop 3× (n=0,1,2)

    @Step("flaky")
    FlakyOut flaky(FlakyIn in) {
        if (relay.attempt() < 3) throw new Boom("transient failure on attempt " + relay.attempt());
        return new FlakyOut("OK", relay.attempt());
    }

    @Step("reserve")
    Reservation reserve(ReserveIn in) { return new Reservation("RES-" + Math.abs(in.amount()), in.amount()); }

    @Step("release")   // compensation for reserve
    Released release(Reservation in) {
        System.out.println("        ↩ compensating: released " + in.ref() + " ($" + in.amount() + ")");
        return new Released(in.ref(), true);
    }

    @Step("boom")
    Handled boom(BoomIn in) { throw new Boom("hard failure for " + in.who()); }

    @Step("job")
    JobOut job(JobIn in) { return new JobOut(in.name(), "DONE"); }

    @Step("classify")   // a Dynamic ROUTE task: picks the next state at runtime via routeTo(...)
    Handled classify(Amount in) {
        String route = in.amount() < 100 ? "express" : in.amount() < 10_000 ? "standard" : "review";
        relay.routeTo(route);
        return new Handled("routed:" + route);
    }

    @Step("maybe")   // per-item batch task: fails deterministically for id == "bad"
    ItemOut maybe(ItemIn in) {
        if ("bad".equals(in.id())) throw new Boom("cannot process item " + in.id());
        return new ItemOut(in.id(), true);
    }

    @Step("plan")   // a Dynamic SUBGRAPH task: GENERATES a workflow at runtime (a Parallel of N steps)
    Handled plan(PlanIn in) {
        StringBuilder branches = new StringBuilder();
        for (int i = 0; i < in.steps(); i++) {
            if (i > 0) branches.append(',');
            branches.append("{\"start\":\"s").append(i).append("\",\"states\":{\"s").append(i)
                    .append("\":{\"type\":\"TASK\",\"task\":\"process\",\"input\":{\"id\":\"step-").append(i)
                    .append("\"},\"end\":true}}}");
        }
        String wf = "{\"name\":\"gen-plan\",\"version\":1,\"mode\":\"ASYNC_STICKY\",\"output\":\"${collect.output}\","
                + "\"start\":\"run\",\"states\":{"
                + "\"run\":{\"type\":\"PARALLEL\",\"next\":\"collect\",\"branches\":[" + branches + "]},"
                + "\"collect\":{\"type\":\"TASK\",\"task\":\"handle\",\"input\":{\"count\":\"${len(run.output)}\"},\"end\":true}}}";
        relay.runSubgraph(wf);
        return new Handled("generated:" + in.steps() + "-step plan");
    }
}
