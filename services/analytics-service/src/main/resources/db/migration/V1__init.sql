-- Only the fields the dashboard needs are stored, never the full GitHub payload.
CREATE TABLE github_events (
    event_id     BIGINT       PRIMARY KEY,
    event_type   VARCHAR(40)  NOT NULL,
    kind         VARCHAR(20)  NOT NULL,
    actor_login  VARCHAR(100) NOT NULL,
    repo_id      BIGINT       NOT NULL,
    repo_name    VARCHAR(200) NOT NULL,
    commits      INTEGER,
    action       VARCHAR(40),
    description  VARCHAR(400) NOT NULL,
    url          VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    ingested_at  TIMESTAMPTZ  NOT NULL,
    observed_at  TIMESTAMPTZ  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_observed_at ON github_events (observed_at DESC);
CREATE INDEX idx_events_repo_observed ON github_events (repo_name, observed_at DESC);
CREATE INDEX idx_events_kind_observed ON github_events (kind, observed_at DESC);

CREATE TABLE repositories (
    repo_id       BIGINT       PRIMARY KEY,
    full_name     VARCHAR(200) NOT NULL,
    owner         VARCHAR(100) NOT NULL,
    html_url      VARCHAR(300) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    event_count   BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_repositories_full_name ON repositories (full_name);
CREATE INDEX idx_repositories_last_seen ON repositories (last_seen_at DESC);

CREATE TABLE minute_aggregates (
    minute         TIMESTAMPTZ PRIMARY KEY,
    total          BIGINT NOT NULL DEFAULT 0,
    push           BIGINT NOT NULL DEFAULT 0,
    pull_request   BIGINT NOT NULL DEFAULT 0,
    fork           BIGINT NOT NULL DEFAULT 0,
    watch          BIGINT NOT NULL DEFAULT 0,
    issues         BIGINT NOT NULL DEFAULT 0,
    issue_comments BIGINT NOT NULL DEFAULT 0,
    other          BIGINT NOT NULL DEFAULT 0,
    commits        BIGINT NOT NULL DEFAULT 0,
    commit_reports BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE repo_minute_aggregates (
    minute         TIMESTAMPTZ  NOT NULL,
    repo_name      VARCHAR(200) NOT NULL,
    total          BIGINT NOT NULL DEFAULT 0,
    push           BIGINT NOT NULL DEFAULT 0,
    pull_request   BIGINT NOT NULL DEFAULT 0,
    fork           BIGINT NOT NULL DEFAULT 0,
    watch          BIGINT NOT NULL DEFAULT 0,
    issues         BIGINT NOT NULL DEFAULT 0,
    issue_comments BIGINT NOT NULL DEFAULT 0,
    other          BIGINT NOT NULL DEFAULT 0,
    commits        BIGINT NOT NULL DEFAULT 0,
    commit_reports BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (minute, repo_name)
);
CREATE INDEX idx_repo_minute_repo ON repo_minute_aggregates (repo_name, minute);

CREATE TABLE rising_repositories (
    repo_name      VARCHAR(200) NOT NULL,
    window_start   TIMESTAMPTZ  NOT NULL,
    window_minutes INTEGER      NOT NULL,
    current_count  BIGINT       NOT NULL,
    baseline       DOUBLE PRECISION NOT NULL,
    pct_increase   DOUBLE PRECISION,
    level          VARCHAR(20)  NOT NULL,
    detected_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (repo_name, window_start)
);
CREATE INDEX idx_rising_window ON rising_repositories (window_start DESC);

CREATE TABLE processing_failures (
    id         BIGSERIAL    PRIMARY KEY,
    topic      VARCHAR(100) NOT NULL,
    record_key VARCHAR(200),
    stage      VARCHAR(40)  NOT NULL,
    payload    TEXT,
    failed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_failures_failed_at ON processing_failures (failed_at DESC);
