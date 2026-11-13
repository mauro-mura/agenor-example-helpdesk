package helpdesk;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Where tickets come in, on any node. Triage is asynchronous: the response is only the id. */
@RestController
public class TicketController {

    public record NewTicket(String sender, String subject, String body) {
    }

    private final TicketStore tickets;
    private final Triage triage;

    public TicketController(TicketStore tickets, Triage triage) {
        this.tickets = tickets;
        this.triage = triage;
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> receive(@RequestBody NewTicket incoming) {
        Ticket ticket = new Ticket(UUID.randomUUID(), incoming.sender(), incoming.subject(), incoming.body());
        triage.receive(ticket);
        return Map.of("id", ticket.id());
    }

    @GetMapping("/tickets/{id}/state")
    public Map<String, TicketState> state(@PathVariable UUID id) {
        return Map.of("state", tickets.state(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/tickets/{id}/outbox")
    public List<String> sent(@PathVariable UUID id) {
        return tickets.sent(id);
    }

    /** The manual queue: tickets no specialist takes, or that the LLM could not handle. */
    @GetMapping("/tickets/manual")
    public List<TicketStore.ManualTicket> manual() {
        return tickets.manual();
    }
}
