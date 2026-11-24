package dev.flipper;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Public Hypixel endpoints, no API key needed. Both are cached server-side for ~60s. */
public final class AuctionApi {
    static final String BASE = "https://api.hypixel.net/v2/skyblock/";
    static final HttpClient H = HttpClient.newHttpClient();
    public record Sale(String uuid, long ts, long price, boolean bin) {}

    static JsonObject get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path)).header("User-Agent", "ahflipper")
                .timeout(Duration.ofSeconds(20)).build();
        return JsonParser.parseString(H.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
    }

    public static List<Auction> active() throws Exception {
        var first = get("auctions?page=0");
        int pages = first.get("totalPages").getAsInt();
        var out = new ArrayList<>(parse(first));
        for (int p = 1; p < pages; p += 10) {
            var fs = new ArrayList<CompletableFuture<JsonObject>>();
            for (int i = p; i < Math.min(pages, p + 10); i++) {
                int pg = i;
                fs.add(CompletableFuture.supplyAsync(() -> {
                    try { return get("auctions?page=" + pg); } catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
            for (var f : fs) out.addAll(parse(f.get()));
        }
        return out;
    }

    static List<Auction> parse(JsonObject o) {
        var l = new ArrayList<Auction>();
        for (var e : o.getAsJsonArray("auctions")) {
            var a = e.getAsJsonObject();
            l.add(new Auction(a.get("uuid").getAsString(), a.get("item_name").getAsString(),
                    a.get("tier").getAsString(), a.get("starting_bid").getAsLong(),
                    a.has("highest_bid_amount") ? a.get("highest_bid_amount").getAsLong() : 0,
                    a.has("bin") && a.get("bin").getAsBoolean(), a.get("end").getAsLong(),
                    a.get("claimed").getAsBoolean()));
        }
        return l;
    }

    public static List<Sale> ended() throws Exception {
        var l = new ArrayList<Sale>();
        for (var e : get("auctions_ended").getAsJsonArray("auctions")) {
            var a = e.getAsJsonObject();
            l.add(new Sale(a.get("auction_id").getAsString(), a.get("timestamp").getAsLong(),
                    a.get("price").getAsLong(), a.has("bin") && a.get("bin").getAsBoolean()));
        }
        return l;
    }
}
