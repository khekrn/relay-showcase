package com.example.showcase;

/** Typed inputs/outputs for the showcase tasks (records, not Maps). */
public final class Model {

    public record HandleIn(String action, Integer count) {}
    public record Handled(String label) {}

    public record ProcOne(String id) {}
    public record ProcResult(String id, boolean ok) {}

    public record QuoteIn(String provider) {}
    public record Quote(String provider, int price) {}

    public record Tick(int n) {}
    public record Ticked(boolean again, int n) {}

    public record FlakyIn(String job) {}
    public record FlakyOut(String status, int attempt) {}

    public record ReserveIn(int amount) {}
    public record Reservation(String ref, int amount) {}
    public record Released(String ref, boolean released) {}

    public record BoomIn(String who) {}

    public record JobIn(String name) {}
    public record ItemIn(String id) {}
    public record ItemOut(String id, boolean ok) {}
    public record JobOut(String name, String status) {}

    public record Amount(int amount) {}   // input to the Dynamic ROUTE classifier
    public record PlanIn(int steps) {}    // input to the Dynamic SUBGRAPH generator

    /** thrown by tasks to exercise failure/retry paths (the type flows to the server as the error `type`). */
    public static final class Boom extends RuntimeException {
        public Boom(String m) { super(m); }
    }

    private Model() {}
}
