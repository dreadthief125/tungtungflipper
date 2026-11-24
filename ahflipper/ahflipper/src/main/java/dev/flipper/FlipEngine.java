package dev.flipper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Finds flips. "Does it sell" is measured from real sales: ended BIN auctions are matched back to the
 * listing we saw earlier (the ended-auctions feed only has item bytes, so we map uuid -> key ourselves)
 * and kept for 24h. Sales history is in memory, so it needs a short warm-up after each launch.
 */
public final class FlipEngine {
    public record Flip(Auction a, long buy, long fair, long profit, double margin,
                       double salesPerHour, double hoursToClear, double score, boolean bid) {}
    public record Stats(long fair, double perHour, double hoursToClear) {}

    final Map<String, String> seen = new ConcurrentHashMap<>();            // uuid -> key for live BINs
    final Map<String, ArrayDeque<long[]>> sales = new HashMap<>();         // key -> [ts, price]
    final long startedAt = System.currentTimeMillis();
    public volatile List<Flip> flips = List.of();
    public volatile Map<String, Stats> stats = Map.of();

    public void refresh(Config c) throws Exception {
        long now = System.currentTimeMillis();
        for (var s : AuctionApi.ended()) {
            if (!s.bin()) continue;
            String k = seen.remove(s.uuid());
            if (k != null) sales.computeIfAbsent(k, x -> new ArrayDeque<>()).add(new long[]{s.ts(), s.price()});
        }
        var list = AuctionApi.active();
        Set<String> ids = new HashSet<>();
        for (var a : list) if (a.bin() && !a.claimed()) { ids.add(a.uuid()); seen.putIfAbsent(a.uuid(), a.key()); }
        seen.keySet().retainAll(ids);
        for (var d : sales.values()) while (!d.isEmpty() && now - d.peekFirst()[0] > 86_400_000L) d.pollFirst();
        evaluate(c, list, now);
    }

    void evaluate(Config c, List<Auction> list, long now) {
        double obsH = Math.min(24, (now - startedAt) / 3.6e6);
        var bins = list.stream().filter(a -> a.bin() && !a.claimed() && a.end() > now && !ignored(c, a))
                .collect(Collectors.groupingBy(Auction::key));
        var st = new HashMap<String, Stats>();
        var out = new ArrayList<Flip>();
        for (var en : bins.entrySet()) {
            var d = sales.get(en.getKey());
            if (d == null || d.size() < 3 || obsH < 0.5) continue;
            double perHour = d.size() / obsH;
            if (perHour * 24 < c.minSalesPerDay) continue;
            long[] prices = d.stream().mapToLong(x -> x[1]).sorted().toArray();
            long median = prices[prices.length / 2];
            var l = en.getValue().stream().sorted(Comparator.comparingLong(Auction::price)).toList();
            if (l.size() < 2) continue;
            long fair = Math.min(median, l.get(1).price());      // conservative: lower of median sale / 2nd-lowest BIN
            double htc = l.size() / perHour;
            st.put(en.getKey(), new Stats(fair, perHour, htc));
            if (htc > c.maxHoursToClear) continue;                 // market too saturated to resell quickly
            consider(c, l.get(0), l.get(0).price(), fair, perHour, htc, false, out);
        }
        if (c.enableBids) for (var a : list) {
            if (a.bin() || a.claimed() || ignored(c, a)) continue;
            long left = (a.end() - now) / 1000;
            if (left > c.bidWindowSec || left < c.minBidWindowSec) continue;
            var s = st.get(a.key());
            if (s == null || s.hoursToClear() > c.maxHoursToClear) continue;
            consider(c, a, a.nextBid(), s.fair(), s.perHour(), s.hoursToClear(), true, out);
        }
        out.sort(Comparator.comparingDouble(Flip::score).reversed());
        stats = st; flips = out;
    }

    void consider(Config c, Auction a, long buy, long fair, double ph, double htc, boolean bid, List<Flip> out) {
        if (buy < c.minBuyPrice) return;
        long profit = fair - Math.round(fair * 0.01) - listFee(fair) - (long) c.extraFeeCoins - buy;
        double margin = 100.0 * profit / buy;
        if (profit < c.minProfit || margin < c.minMarginPct || margin > c.maxMarginPct) return;
        double score = profit * Math.min(1, ph / 2.0) / (1 + htc);   // profit, weighted by how fast it sells
        out.add(new Flip(a, buy, fair, profit, margin, ph, htc, score, bid));
    }

    /** Creation fee tiers; verify against current game values. Claim tax is taken as 1%. */
    public static long listFee(long p) { return Math.round(p * (p < 10_000_000 ? 0.01 : p < 100_000_000 ? 0.02 : 0.025)); }
    static boolean ignored(Config c, Auction a) { return c.ignoredNames.stream().anyMatch(a.name()::contains); }
}
