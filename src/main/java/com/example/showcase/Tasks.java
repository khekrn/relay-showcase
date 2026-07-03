package com.example.showcase;

import com.cs.relay.worker.spring.RelayTask;
import com.cs.relay.worker.spring.TaskContext;
import com.example.showcase.Model.*;
import org.springframework.stereotype.Component;

/** All showcase task implementations — each a Spring {@code @Component} whose name matches the workflow taskName. */
public class Tasks {

    @Component("handle")
    static class Handle implements RelayTask<HandleIn, Handled> {
        public Handled run(HandleIn in, TaskContext c) {
            return new Handled(in.action() != null ? in.action() : "count=" + in.count());
        }
    }

    @Component("process")
    static class Process implements RelayTask<ProcOne, ProcResult> {
        public ProcResult run(ProcOne in, TaskContext c) { return new ProcResult(in.id(), true); }
    }

    @Component("quote")
    static class GetQuote implements RelayTask<QuoteIn, Quote> {
        public Quote run(QuoteIn in, TaskContext c) { return new Quote(in.provider(), 100 + in.provider().hashCode() % 50); }
    }

    @Component("tick")
    static class Tock implements RelayTask<Tick, Ticked> {
        public Ticked run(Tick in, TaskContext c) { return new Ticked(in.n() < 2, in.n()); }   // loop 3× (n=0,1,2)
    }

    @Component("flaky")
    static class Flaky implements RelayTask<FlakyIn, FlakyOut> {
        public FlakyOut run(FlakyIn in, TaskContext c) {
            if (c.attempt() < 3) throw new Boom("transient failure on attempt " + c.attempt());
            return new FlakyOut("OK", c.attempt());
        }
    }

    @Component("reserve")
    static class Reserve implements RelayTask<ReserveIn, Reservation> {
        public Reservation run(ReserveIn in, TaskContext c) { return new Reservation("RES-" + Math.abs(in.amount()), in.amount()); }
    }

    @Component("release")   // compensation for reserve
    static class Release implements RelayTask<Reservation, Released> {
        public Released run(Reservation in, TaskContext c) {
            System.out.println("        ↩ compensating: released " + in.ref() + " ($" + in.amount() + ")");
            return new Released(in.ref(), true);
        }
    }

    @Component("boom")
    static class BoomTask implements RelayTask<BoomIn, Handled> {
        public Handled run(BoomIn in, TaskContext c) { throw new Boom("hard failure for " + in.who()); }
    }

    @Component("job")
    static class Job implements RelayTask<JobIn, JobOut> {
        public JobOut run(JobIn in, TaskContext c) { return new JobOut(in.name(), "DONE"); }
    }

    @Component("classify")   // a Dynamic ROUTE task: picks the next state at runtime via ctx.routeTo(...)
    static class Classify implements RelayTask<Amount, Handled> {
        public Handled run(Amount in, TaskContext c) {
            String route = in.amount() < 100 ? "express" : in.amount() < 10_000 ? "standard" : "review";
            c.routeTo(route);
            return new Handled("routed:" + route);
        }
    }

    @Component("maybe")   // per-item batch task: fails deterministically for id == "bad"
    static class Maybe implements RelayTask<ItemIn, ItemOut> {
        public ItemOut run(ItemIn in, TaskContext c) {
            if ("bad".equals(in.id())) throw new Boom("cannot process item " + in.id());
            return new ItemOut(in.id(), true);
        }
    }

    @Component("plan")   // a Dynamic SUBGRAPH task: GENERATES a workflow at runtime (a Parallel of N steps)
    static class Plan implements RelayTask<PlanIn, Handled> {
        public Handled run(PlanIn in, TaskContext c) {
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
            c.runSubgraph(wf);
            return new Handled("generated:" + in.steps() + "-step plan");
        }
    }
}
