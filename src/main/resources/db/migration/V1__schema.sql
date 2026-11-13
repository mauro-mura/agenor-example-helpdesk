-- The application's schema, under Spring Boot's Flyway (history table flyway_schema_history).
-- Agenor's directory and approval gate keep histories of their own in the same database.

-- Where each ticket is; none is left without a way forward:
--   RECEIVED           just saved: being triaged, or waiting for a node with the triager
--   AWAITING_APPROVAL  a draft is in the operator's queue
--   RETRY              the LLM was not there: try again after retry_after
--   MANUAL             no specialist, or an error that will not pass by itself: a person triages it
--   SENT / REJECTED    the operator's decision has been carried out
CREATE TABLE ticket (
    id          UUID         PRIMARY KEY,
    sender      VARCHAR(255) NOT NULL,
    subject     VARCHAR(500) NOT NULL,
    body        TEXT         NOT NULL,
    category    VARCHAR(20),
    state       VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    reason      TEXT,
    attempts    INT          NOT NULL DEFAULT 0,
    retry_after TIMESTAMP,
    -- A node triaging the ticket holds it until then; past it, any node with the triager takes it
    claimed_until TIMESTAMP,
    received_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX ticket_state ON ticket (state);

-- "Sending" a reply: approved drafts only (no SMTP).
CREATE TABLE outbox (
    id          BIGSERIAL    PRIMARY KEY,
    ticket_id   UUID         NOT NULL REFERENCES ticket (id),
    recipient   VARCHAR(255) NOT NULL,
    subject     VARCHAR(500) NOT NULL,
    body        TEXT         NOT NULL,
    approval_id VARCHAR(255) NOT NULL,
    sent_at     TIMESTAMP    NOT NULL DEFAULT now()
);

-- Approval decisions already carried out: each one once, whether it arrives through the gate's
-- future or through the recovery after a restart (OrphanDecisions).
CREATE TABLE executed_decision (
    request_id  VARCHAR(255) PRIMARY KEY,
    executed_at TIMESTAMP    NOT NULL DEFAULT now()
);

-- Knowledge base on pgvector (PgVectorKnowledgeStore). The vector has no fixed dimension: the
-- model decides it, and model_id keeps vectors of different models from being mixed.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_document (
    id          VARCHAR(255) PRIMARY KEY,
    title       TEXT         NOT NULL,
    content     TEXT         NOT NULL,
    category    VARCHAR(255) NOT NULL,
    keywords    TEXT[]       NOT NULL,
    related_ids TEXT[]       NOT NULL,
    model_id    VARCHAR(255) NOT NULL,
    embedding   vector       NOT NULL
);

CREATE INDEX knowledge_document_category ON knowledge_document (category);
