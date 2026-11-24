package dev.flipper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Tick-driven step runner. One task at a time; each step is polled until it succeeds, aborts, or times out. */
public final class Bot {
    static final class Abort extends RuntimeException { Abort(String m) { super(m); } }
    interface Step { boolean run(); }
    record Named(String name, Step step) {}

    final Deque<Named> steps = new ArrayDeque<>();
    final Queue<String> msgs = new ConcurrentLinkedQueue<>();
    final Random rnd = new Random();
    int wait, stepTicks;
    String label = ""; Runnable onDone, onFail;

    public boolean busy() { return !steps.isEmpty(); }
    public void stop() { steps.clear(); }
    public void onMessage(String m) { msgs.add(m); while (msgs.size() > 60) msgs.poll(); }

    void start(String label, List<Named> s, Runnable done, Runnable fail) {
        this.label = label; steps.clear(); steps.addAll(s); onDone = done; onFail = fail; wait = 0; stepTicks = 0;
    }

    public void tick(Config c) {
        if (steps.isEmpty()) return;
        if (wait > 0) { wait--; return; }
        var s = steps.peek();
        try {
            if (s.step().run()) {
                steps.poll(); stepTicks = 0;
                wait = (c.actionDelayMs + rnd.nextInt(c.actionJitterMs + 1)) / 50;
                if (steps.isEmpty() && onDone != null) onDone.run();
            } else if (++stepTicks > c.stepTimeoutSec * 20) throw new Abort("timed out at " + s.name());
        } catch (Abort e) {
            steps.clear();
            GameBridge.say("§c" + label + " aborted: " + e.getMessage());
            GameBridge.closeScreen();
            if (onFail != null) onFail.run();
        }
    }

    // ---- step helpers ----
    Named cmd(String t) { return new Named("/" + t, () -> { GameBridge.cmd(t); return true; }); }
    Named title(String part) { return new Named("wait '" + part + "'", () -> GameBridge.title().contains(part)); }
    Named click(String n) { return new Named("click '" + n + "'", () -> GameBridge.clickByName(n)); }
    Named clearMsgs() { return new Named("clear", () -> { msgs.clear(); return true; }); }
    Named msg(String ok, String... bad) {
        return new Named("message '" + ok + "'", () -> {
            String m;
            while ((m = msgs.poll()) != null) {
                if (m.contains(ok)) return true;
                for (var b : bad) if (m.contains(b)) throw new Abort(m);
            }
            return false;
        });
    }

    /** Buy a BIN or place a bid via /viewauction. Verifies the price on screen before clicking. */
    public void buy(Config c, FlipEngine.Flip f, Budget b) {
        var a = f.a();
        var p = new Budget.Position(a.uuid(), a.key(), a.name(), f.buy(),
                f.bid() ? Budget.State.BID : Budget.State.BOUGHT, a.end());
        var s = new ArrayList<Named>();
        s.add(cmd("viewauction " + a.uuid()));
        s.add(title(c.viewTitle));
        if (!f.bid()) s.add(new Named("price check", () -> {
            long shown = GameBridge.slotCoins(c.buyBtn);
            if (shown == -2) return false;
            if (shown > f.buy() * 1.02) throw new Abort("price is now " + shown);
            return true;
        }));
        s.add(click(f.bid() ? c.bidBtn : c.buyBtn));
        s.add(title("Confirm"));
        s.add(clearMsgs());
        s.add(click(c.confirmBtn));
        s.add(f.bid() ? msg("Bid", "enough coins", "cannot") : msg("You purchased", "enough coins", "cannot afford", "already"));
        start((f.bid() ? "Bid " : "Buy ") + a.name(), s, () -> {
            if (!c.autoRelist) p.state = Budget.State.LISTED;
            b.open.add(p);
            GameBridge.say("§a" + (f.bid() ? "Bid placed on " : "Bought ") + a.name() + " for " + f.buy() + " (target " + f.fair() + ")");
        }, null);
    }

    /** Claim the item, then list it as a BIN. */
    public void claimAndList(Config c, Budget.Position p, long price, Runnable done, Runnable fail) {
        var s = new ArrayList<Named>();
        s.add(cmd("viewauction " + p.uuid));
        s.add(title(c.viewTitle));
        // if there's no claim button after ~1s, assume the item was already delivered
        s.add(new Named("claim", () -> GameBridge.clickByName(c.claimBtn) || stepTicks > 20));
        s.add(new Named("item in inventory", () -> {
            if (GameBridge.hasItem(p.name)) { GameBridge.closeScreen(); return true; }
            return false;
        }));
        s.add(cmd("ah"));
        s.add(title(c.ahTitle));
        s.add(click(c.createBtn));
        s.add(title(c.createTitle));
        s.add(new Named("select item", () -> GameBridge.clickInventoryItem(p.name)));
        s.add(title(c.binTitle));
        s.add(click(c.priceBtn));
        s.add(new Named("sign", () -> GameBridge.fillSign(Long.toString(price))));
        s.add(title(c.binTitle));
        s.add(click(c.submitBtn));
        s.add(title("Confirm"));
        s.add(clearMsgs());
        s.add(click(c.confirmBtn));
        s.add(msg("BIN Auction started", "enough coins", "cannot"));
        start("List " + p.name, s, () -> {
            GameBridge.say("§aListed " + p.name + " for " + price);
            done.run();
        }, fail);
    }
}
