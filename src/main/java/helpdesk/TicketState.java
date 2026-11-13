package helpdesk;

/** Where a ticket is; what each state means is in {@code V1__schema.sql}. */
public enum TicketState {
    RECEIVED, AWAITING_APPROVAL, RETRY, MANUAL, SENT, REJECTED
}
