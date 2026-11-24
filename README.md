# agenor-example-helpdesk

A helpdesk built on [Agenor](https://github.com/mauro-mura/agenor) 0.42.0 and Spring Boot 4. A
**triager** agent classifies each incoming ticket with an LLM and hands it to the **specialist**
for its category; the specialist writes a draft reply from a knowledge base searched by meaning,
and the draft reaches the customer only after a human operator approves, corrects or rejects it.

```
POST /tickets ─▶ Triage ─▶ Triager (LLMAgent) ──REQUEST──▶ Specialist (billing | technical | account)
                    │          │ classify                    │ search knowledge (pgvector)
                    │          ▼                             │ write draft (LLM)
                    │       OTHER → manual queue             ├──INFORM "awaiting approval"──▶ request ends
                    ▼                                        ▼
             RETRY / MANUAL                          ApprovalGate (JDBC) ◀── operator.html
                                                             │ approve / modify / reject
                                                             ▼
                                                      ReplySender ─▶ outbox
```

What it shows of Agenor:

- `LLMAgent` and the request protocol (`DialogueCapability`) between agents;
- a `GuardrailChain` with `PiiRedactionGuardrail`, so neither the LLM nor the embedding provider
  sees the email addresses and IBANs it recognises;
- `JdbcApprovalGate` for human approval, and the JDBC directory, from the Spring Boot starter;
- the same agents in one process or on two nodes over Redis, with no code change.

The operator's wait is **detached from the conversation**: the request between triager and
specialist ends as soon as the draft is queued, and sending starts from the decision. A dialogue
is for exchanges of seconds; an approval can take a working day (`Specialist.APPROVAL_TIMEOUT`).

Every ticket reaches a state from which someone picks it up: `AWAITING_APPROVAL`, `RETRY` when
the LLM was down (up to `helpdesk.retry.attempts`), or `MANUAL` when no specialist takes it or the
error will not pass by itself (`NextStep`). Each change of state names the states it starts from,
so a late step cannot move a ticket back: a draft that outlasts the triager's wait is queued once,
and a reply is sent once even if the ticket is retried afterwards.

## How it differs from the examples in Agenor

Agenor's own [`agenor-examples`](https://github.com/mauro-mura/agenor/tree/main/agenor-examples)
module has a support chatbot too (`SupportChatbotExample`), and `CustomerSupportExample` and
`PersistentHitlExample` show intent routing and JDBC approval on their own. Those are programs
started from a `main` method, each built to show part of Agenor's API. This is an application:
it runs under Spring Boot, keeps tickets, knowledge and approvals in PostgreSQL, comes through a
restart with decisions still pending, and runs unchanged on one node or two.

## What the application writes because Agenor 0.42 does not have it

None of these classes knows about tickets: any application with the same needs would write them.

| Class | Why it exists |
|---|---|
| `OrphanDecisions`, `DecisionExecutor` | The future from `ApprovalGate.requestApproval` lives only in the JVM that created it. After a restart, a decision on a pending request is recorded and nothing else happens; this reads the gate's table and carries each decision out, once |
| `ApprovalController` | The console has no view of approvals |
| `PgVectorKnowledgeStore` | The only `KnowledgeStore` is in memory and matches keywords: none searches by meaning, and none outlives the process |
| `KnowledgeConfig.embeddings` | The starter builds an `LLMProvider` but no `EmbeddingProvider` |
| `FakeEmbeddings` (test) | There is a scripted `LLMProvider` for tests, but no fake `EmbeddingProvider` |

`AgentConfig` registers the agents as Spring beans rather than letting the starter discover them,
because they need the `ApprovalGate` and application beans; that is the way
`spring-boot-starter.md` describes under "Giving an agent anything else".

## Try it

Needs Java 21, Maven, and podman or docker with their compose.

```bash
podman compose up -d postgres ollama
curl localhost:11434/api/pull -d '{"model":"qwen3:4b-instruct","stream":false}'
curl localhost:11434/api/pull -d '{"model":"nomic-embed-text","stream":false}'
mvn spring-boot:run
```

Send a ticket, then open <http://localhost:8081/operator.html> to decide on the draft:

```bash
curl -s localhost:8081/tickets -H 'Content-Type: application/json' \
  -d '{"sender":"jane@example.com","subject":"Charged twice","body":"My card was charged twice this month."}'
curl -s localhost:8081/tickets/<id>/state      # AWAITING_APPROVAL once the draft is queued
curl -s localhost:8081/tickets/<id>/outbox     # the reply, after approval
curl -s localhost:8081/tickets/manual          # what nobody could triage, and why
```

The knowledge base is `src/main/resources/knowledge.json`, loaded at start and every
`helpdesk.knowledge.reload`; only documents that changed are embedded again. If Ollama is down at
start the application starts anyway, and the next pass loads it.

| Endpoint | |
|---|---|
| `POST /tickets` | `{sender, subject, body}` → `202 {id}` |
| `GET /tickets/{id}/state`, `/tickets/{id}/outbox`, `/tickets/manual` | |
| `GET /operator/drafts` | pending drafts next to the ticket they answer |
| `GET /approvals`, `POST /approvals/{requestId}` | `{choice: APPROVE \| MODIFY \| REJECT, text}` |

To stop, press Ctrl+C where `mvn spring-boot:run` is running, then:

```bash
podman compose down       # removes the containers, Valkey too if you started it for two nodes
podman compose down -v    # also deletes the tickets, the knowledge base and the downloaded models
```

After the first, `podman compose up -d postgres ollama` starts again with the data and the models
where they were. After the second, pull the two models again.

## A decision after a restart

`OrphanDecisions` can be turned off, to see what it is there for. Send a ticket and wait for
`AWAITING_APPROVAL`, as above. Stop the application with Ctrl+C, then start it again with the
recovery off, and approve the draft that was waiting:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--helpdesk.orphan-decisions.enabled=false"
curl -s localhost:8081/approvals                 # the requestId of the draft
curl -s localhost:8081/approvals/<requestId> -H 'Content-Type: application/json' -d '{"choice":"APPROVE"}'
curl -s localhost:8081/tickets/<id>/state        # still AWAITING_APPROVAL
curl -s localhost:8081/tickets/<id>/outbox       # empty
```

The decision is recorded in the gate's table, and nothing else happens: the code that would have
sent the reply was waiting in the process you stopped. Start the application again without the
flag: within `helpdesk.orphan-decisions.interval` the reply is in the outbox, and the log says
`carried out by the recovery`. It can be carried out by a process that never saw the draft
because the request holds all it needs: the action, `send-reply`, and the whole draft as its
payload.

## Ollama and PostgreSQL on another machine

Nothing in the code points at `localhost`: each address is a property, so the application can use
an Ollama and a PostgreSQL elsewhere on the network, with nothing started locally.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="\
  --agenor.llm.base-url=http://ai-server:11434 \
  --helpdesk.embedding.base-url=http://ai-server:11434 \
  --spring.datasource.url=jdbc:postgresql://ai-server:5432/helpdesk \
  --spring.datasource.username=helpdesk --spring.datasource.password=helpdesk"
```

- The LLM and the embeddings are separate properties, and can be on two machines. Pull both
  models on the one that serves them, with the `curl` above aimed at it.
- PostgreSQL needs pgvector: the migration runs `CREATE EXTENSION vector`, and fails on a plain
  `postgres` image. `compose.yml` runs `pgvector/pgvector:pg16`; the same image on the server will
  do.
- Give the application a database of its own. In one shared with other Agenor runtimes, it would
  share with them Flyway's `flyway_schema_history`, the agent directory, and the approvals that
  `GET /approvals` lists.
- Ollama has no authentication, and the credentials in `compose.yml` are for a laptop: on a
  network you do not trust, reach both through a firewall or an SSH tunnel
  (`ssh -L 11434:localhost:11434 ai-server` keeps the defaults). Whatever the guardrail does not
  recognise in a ticket travels to that machine.

For two nodes, `agenor.messaging.redis.uri` points at the server's Redis or Valkey in the same way.

## Two nodes

`helpdesk.agents` chooses which agents a node runs. With the triager on one node and the
specialists on another, the dialogue between them goes through Redis, and an approval given on
one node reaches the future waiting on the other through PostgreSQL's `NOTIFY`.

Any node accepts tickets. One without the triager saves them, and a node with it claims them on
its next pass (`helpdesk.retry.interval`), together with the retries that are due. A claim lasts
`helpdesk.retry.abandoned-after` and is taken with `FOR UPDATE SKIP LOCKED`, so several nodes with
the triager never triage the same ticket, and a ticket left halfway by a stopped node is taken
again when its claim runs out.

```bash
podman compose up -d valkey
mvn spring-boot:run -Dspring-boot.run.arguments="--helpdesk.agents=triager \
  --agenor.messaging.provider=redis --agenor.messaging.redis.uri=redis://localhost:6379"
mvn spring-boot:run -Dspring-boot.run.arguments="--helpdesk.agents=specialists --server.port=8082 \
  --agenor.runtime.name=node-b --agenor.messaging.provider=redis --agenor.messaging.redis.uri=redis://localhost:6379"
```

To have a node read back after a restart what it had left unacknowledged, give it a fixed
`agenor.messaging.redis.node-id`, unique per instance.

## Tests

```bash
mvn test                                              # unit tests, no containers
mvn verify -Dintegration.tests.enabled=true           # + integration tests on PostgreSQL and Valkey
```

The integration tests use a scripted LLM and fake embeddings, so they need neither Ollama nor a
network. `TriageContract` runs the whole flow, including the failures (LLM down, missing model,
embeddings down, a decision taken after a restart, a draft slower than the triager's wait, two
nodes claiming at once), once in memory and once with Redis messaging; `TwoNodeRedisIT` runs it
across two nodes, and checks that a ticket received where there is no triager waits for the node
that has one. `RecoveryOffIT` runs with `helpdesk.orphan-decisions.enabled=false`: a decision
taken after a restart stays recorded and unsent, one whose future is still waiting is sent.

They start their containers with Testcontainers; with podman, point them at its socket:

```bash
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true
```

## What it does not do

- No email in or out: tickets arrive over REST, replies stop at the outbox table.
- No authentication on the operator page, and no validation of a new ticket: a missing field is a
  500.
- An approval that expires unanswered leaves its ticket in `AWAITING_APPROVAL`.
- The knowledge base only grows: a document removed from `knowledge.json` stays, and after a change
  of embedding model the store refuses to search until its table is emptied and loaded again.
- Only a local LLM has been tried: with a cloud provider, personal data the guardrail does not
  recognise leaves the machine.
- The drafts come from a 4B model, on the CPU unless Ollama finds a GPU: expect the odd
  placeholder (`[Customer Name]`), or a detail taken from a source rather than from the ticket.
  Catching them is the operator's job, which is why nothing is sent without approval.

## License

[Apache License 2.0](LICENSE).
