package helpdesk;

import java.util.List;
import java.util.UUID;

/**
 * The reply a specialist proposes: the payload of the approval request.
 *
 * @param sources the ids of the knowledge base documents used to write it
 */
public record Draft(UUID ticketId, String category, String recipient, String subject, String body,
                    List<String> sources) {
}
