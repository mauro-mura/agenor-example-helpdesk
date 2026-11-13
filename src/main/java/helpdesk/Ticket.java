package helpdesk;

import java.util.UUID;

/** A support ticket as it arrives from the REST endpoint. */
public record Ticket(UUID id, String sender, String subject, String body) {
}
