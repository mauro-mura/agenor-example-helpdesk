package helpdesk;

import java.util.Locale;
import java.util.Optional;

/** The four triage categories. {@code OTHER} has no specialist. */
public enum Category {
    BILLING, TECHNICAL, ACCOUNT, OTHER;

    /** The id of the specialist agent, empty for {@link #OTHER}. */
    public Optional<String> specialist() {
        return this == OTHER ? Optional.empty() : Optional.of("specialist-" + key());
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Category fromKey(String key) {
        return valueOf(key.toUpperCase(Locale.ROOT));
    }

    /**
     * Reads the model's reply: the first category it names, {@link #OTHER} if none. A local model
     * often adds punctuation or a sentence around the word.
     */
    public static Category fromReply(String reply) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT);
        for (Category c : values()) {
            if (c != OTHER && text.contains(c.key())) {
                return c;
            }
        }
        return OTHER;
    }
}
