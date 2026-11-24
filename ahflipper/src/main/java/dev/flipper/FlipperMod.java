package dev.flipper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import java.util.Set;
import java.util.concurrent.*;

/** Commands: /flip on | off | stop | status | auto on|off | purse <n> */
public class FlipperMod implements ClientModInitializer {
    public static Config cfg;
    static final FlipEngine engine = new FlipEngine();
    static final Budget budget = new Budget();
    static final Bot bot = new Bot();
    static volatile boolean on = false;
    static final Set<String> tried = ConcurrentHashMap.newKeySet(), alerted = ConcurrentHashMap.newKeySet();
    static int cooldown = 0;

    @Override public void onInitializeClient() {
        cfg = Config.load();
        var pool = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "ahflipper-poll"); t.setDaemon(true); return t; });
        pool.scheduleWithFixedDelay(() -> {
            if (!on) return;
            try { engine.refresh(cfg); alert(); } catch (Exception e) { e.printStackTrace(); }
        }, 5, cfg.pollSeconds, TimeUnit.SECONDS);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!on || mc.player == null) return;
            bot.tick(cfg);
            if (bot.busy()) return;
            if (cooldown > 0) { cooldown--; return; }
            if (!cfg.autoExecute || mc.screen != null) return;
            pickNext();
        });
        ClientReceiveMessageEvents.GAME.register((m, overlay) -> {
            String s = m.getString();
            bot.onMessage(s);
            if (s.startsWith("[Auction]") && s.contains("bought")) budget.sold(s);
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            if (!cmd.equals("flip") && !cmd.startsWith("flip ")) return true;
            handle(cmd.length() > 4 ? cmd.substring(5).trim() : "status");
            return false;
        });
    }

    static void pickNext() {
        long now = System.currentTimeMillis(), purse = GameBridge.purse();
        if (cfg.autoRelist) for (var p : budget.open) {
            boolean ready = p.state == Budget.State.BOUGHT || (p.state == Budget.State.BID && now > p.endMs + 15_000);
            if (!ready) continue;
            var st = engine.stats.get(p.key);
            long fair = st != null ? st.fair() : Math.round(p.cost * 1.1);
            long price = Math.max(Math.round(fair * (1 - cfg.undercutPct / 100)), Math.round(p.cost * 1.03));
            bot.claimAndList(cfg, p, price, () -> { p.state = Budget.State.LISTED; p.listPrice = price; cooldown = 40; }, () -> {
                if (p.state == Budget.State.BID) budget.open.remove(p);       // bid probably lost
                else if (++p.failures >= 3) { p.state = Budget.State.LISTED; GameBridge.say("§eList " + p.name + " manually."); }
                cooldown = 60;
            });
            return;
        }
        for (var f : engine.flips) {
            if (!tried.add(f.a().uuid())) continue;
            if (!budget.allows(cfg, f, purse)) continue;
            bot.buy(cfg, f, budget);
            cooldown = 40;
            return;
        }
    }

    static void alert() {
        if (cfg.autoExecute) return;
        int n = 0;
        for (var f : engine.flips) {
            if (n++ >= 3) break;
            if (!alerted.add(f.a().uuid())) continue;
            GameBridge.say(String.format("%s%s: buy %,d -> sell ~%,d, profit %,d (%.0f%%), ~%.1f sales/h  §7/viewauction %s",
                    f.bid() ? "§6BID " : "§a", f.a().name(), f.buy(), f.fair(), f.profit(), f.margin(), f.salesPerHour(), f.a().uuid()));
        }
    }

    static void handle(String arg) {
        String[] a = arg.split(" ");
        switch (a[0]) {
            case "on" -> { on = true; GameBridge.say("Scanning. Sales history needs ~30 min before flips appear."); }
            case "off" -> { on = false; bot.stop(); GameBridge.say("Off."); }
            case "stop" -> { bot.stop(); GameBridge.say("Current task cancelled."); }
            case "auto" -> { cfg.autoExecute = a.length > 1 && a[1].equals("on"); cfg.save(); GameBridge.say("autoExecute = " + cfg.autoExecute); }
            case "purse" -> { if (a.length > 1) { cfg.purseOverride = Long.parseLong(a[1]); cfg.save(); } GameBridge.say("purse override = " + cfg.purseOverride); }
            default -> GameBridge.say(String.format("on=%s auto=%s purse=%,d committed=%,d open=%d flips=%d",
                    on, cfg.autoExecute, GameBridge.purse(), budget.committed(), budget.open.size(), engine.flips.size()));
        }
    }
}
