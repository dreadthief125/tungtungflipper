package dev.flipper;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;

/** Edit config/ahflipper.json. Every threshold lives here. */
public class Config {
    // behaviour
    public boolean autoExecute = false;   // false = only alert in chat. true = the mod clicks GUIs for you.
    public boolean autoRelist = true;
    public boolean enableBids = false;    // bid on auctions about to end
    // budget (fractions of "portfolio" = purse + coins tied up in open flips)
    public double maxFractionPerItem = 0.20;
    public double maxCommittedFraction = 0.60;
    public double reserveFraction = 0.10;  // purse never drops below this share of portfolio
    public int maxOpenPositions = 4;
    public int maxPerItemKey = 1;
    public long purseOverride = 0;         // fallback if the scoreboard can't be parsed
    // flip quality
    public long minProfit = 100_000;
    public double minMarginPct = 5;
    public double maxMarginPct = 50;       // "too good to be true" guard (stars/enchants not in the item key)
    public long minBuyPrice = 100_000;
    public double minSalesPerDay = 15;     // liquidity floor
    public double maxHoursToClear = 3;     // listings / sales-per-hour: skips saturated markets
    public double undercutPct = 0.5;
    public double extraFeeCoins = 0;
    public int bidWindowSec = 40, minBidWindowSec = 12;
    public List<String> ignoredNames = new ArrayList<>(List.of("Enchanted Book", "Rune", "[Lvl"));
    // timing
    public int pollSeconds = 60, actionDelayMs = 250, actionJitterMs = 150, stepTimeoutSec = 6;
    // GUI text: verify these on the server
    public String viewTitle = "Auction View", buyBtn = "Buy Item Now", bidBtn = "Submit Bid",
            confirmBtn = "Confirm", claimBtn = "Claim", ahTitle = "Auction House",
            createBtn = "Create Auction", createTitle = "Create Auction", binTitle = "Create BIN Auction",
            priceBtn = "Starting bid", submitBtn = "Create BIN Auction";

    static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("ahflipper.json"); }
    public static Config load() {
        try { if (Files.exists(path())) return G.fromJson(Files.readString(path()), Config.class); }
        catch (Exception ignored) {}
        Config c = new Config(); c.save(); return c;
    }
    public void save() { try { Files.writeString(path(), G.toJson(this)); } catch (Exception ignored) {} }
}
