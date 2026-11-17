package io.github.yidasanqian.llm.usage;

/** A null usage represents a terminal-only event; it never upgrades an earlier partial snapshot. */
public record StreamUsageUpdate(Usage usage, boolean authoritative, boolean terminal) {
    public StreamUsageUpdate {
        if (authoritative && usage == null) throw new IllegalArgumentException("Authoritative update requires usage");
    }
}
