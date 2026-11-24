package dev.flipper;

import java.util.*;

public final class Budget {
    public enum State { BOUGHT, BID, LISTED }
    public static final class Position {
        public final String uuid, key, name; public final long cost, endMs;
        public State state; public long listPrice; public int failures;
        public Position(String uuid, String key, String name, long cost, State s, long endMs) {
            this.uuid = uuid; this.key = key; this.name = name; this.cost = cost; this.state = s; this.endMs = endMs;
        }
    }
    public final List<Position> open = new ArrayList<>();

    public long committed() { return open.stream().mapToLong(p -> p.cost).sum(); }

    /** Portfolio = purse + coins sitting in open flips, so a big purchase can't hide by shrinking the purse. */
    public boolean allows(Config c, FlipEngine.Flip f, long purse) {
        if (purse <= 0) return false;
        long committed = committed(), port = purse + committed, price = f.buy();
        if (price > port * c.maxFractionPerItem) return false;
        if (purse - price < port * c.reserveFraction) return false;
        if (committed + price > port * c.maxCommittedFraction) return false;
        if (open.size() >= c.maxOpenPositions) return false;
        return open.stream().filter(p -> p.key.equals(f.a().key())).count() < c.maxPerItemKey;
    }

    public void sold(String msg) {
        open.removeIf(p -> p.state == State.LISTED && msg.contains(p.name));
    }
}
