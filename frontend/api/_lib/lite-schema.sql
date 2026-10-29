-- Extra table of the serverless-lite deployment, applied on top of V1__init.sql. The Docker Compose stack does not use it.
-- One row holds the GitHub poll state that the ingestion service keeps in memory in the full architecture,
-- plus an expiring claim so that exactly one visitor request polls GitHub at a time.
CREATE TABLE IF NOT EXISTS ingestion_state (
    id                   INTEGER PRIMARY KEY CHECK (id = 1),
    status               TEXT        NOT NULL DEFAULT 'STARTING',
    etags                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    next_poll_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token          TEXT,
    claim_expires_at     TIMESTAMPTZ,
    last_poll_attempt    TIMESTAMPTZ,
    last_successful_poll TIMESTAMPTZ,
    events_ingested      BIGINT      NOT NULL DEFAULT 0,
    last_error           TEXT        NOT NULL DEFAULT '',
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    poll_interval_seconds INTEGER    NOT NULL DEFAULT 0,
    rate_limit_remaining INTEGER     NOT NULL DEFAULT -1,
    first_window_start   TIMESTAMPTZ,
    last_cleanup_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ingestion_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
