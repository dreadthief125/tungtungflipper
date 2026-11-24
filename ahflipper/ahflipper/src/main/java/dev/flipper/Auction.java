package dev.flipper;

public record Auction(String uuid, String name, String tier, long startingBid, long highestBid,
                      boolean bin, long end, boolean claimed) {
    /** Items are grouped by name + rarity. Enchants/stars/pet level are NOT part of the key, hence the margin cap. */
    public String key() { return name.replaceAll("§.", "").trim() + "|" + tier; }
    public long price() { return bin ? startingBid : Math.max(startingBid, highestBid); }
    public long nextBid() { return highestBid > 0 ? (long) Math.ceil(highestBid * 1.05) + 1 : startingBid; }
}
