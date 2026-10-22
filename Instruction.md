# Build GitHubPulse: Real-Time GitHub Activity Intelligence Dashboard

You are a senior full-stack engineer specializing in distributed systems, event-driven architectures, and modern UI design.

Build a complete, production-quality, visually impressive application called **GitHubPulse**.

Do not ask me any clarifying questions. Make reasonable engineering decisions independently and implement the project end-to-end.

The application must be functional, not just a frontend mockup.

## 1. Project Overview

**GitHubPulse: The Internet's Open-Source Activity, Live.**

Build a publicly accessible, single-page analytics dashboard that monitors GitHub's public activity in near real time.

Users should be able to open the website without authentication and immediately see:

- Live GitHub activity statistics.
- The most active repositories.
- Pushes, commits, pull requests, forks, and stars.
- Activity trends over time.
- A continuously updating feed of recent GitHub events.
- Insights into which repositories are gaining momentum.

Use real GitHub public events whenever available.

The interface should be polished enough to showcase in a software engineering portfolio and demonstrate to recruiters.

## 2. Technology Stack

### Frontend
- React
- TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- Recharts
- Lucide React
- Framer Motion

### Backend
- Java 21
- Spring Boot 3
- Apache Kafka
- Kafka Streams
- Redis
- PostgreSQL
- WebSockets
- gRPC
- Spring Data JPA
- Gradle

### Infrastructure
- Docker
- Docker Compose
- Terraform
- AWS EC2
- GitHub Actions for CI/CD

Use compatible, maintained dependency versions.

## 3. Dashboard Design

Create a beautiful, modern, dark-themed dashboard inspired by professional observability platforms such as Grafana, Datadog, and Linear.

Design requirements:

- Dark navy or charcoal background.
- Electric blue, cyan, and violet accents.
- Subtle gradients and glassmorphism.
- Smooth animations.
- Responsive layout.
- Excellent typography.
- Clean cards with rounded corners.
- Professional charts and visualizations.
- Animated live indicators.
- Skeleton loaders while data loads.

Avoid generic dashboard templates.

The entire application must fit into **one scrollable page**.

No separate pages for analytics, repositories, or event history.

### Header

Display:

**GitHubPulse**

Subtitle:

"Real-time intelligence from GitHub's public activity."

Include:

- Animated green LIVE indicator.
- Last updated timestamp.
- Connection status.
- GitHub repository button labeled **View Source on GitHub**.

Make the GitHub repository URL configurable using an environment variable.

## 4. Six Live KPI Cards

At the top of the dashboard, create six prominent metric cards.

### Card 1: Total Events

Number of GitHub events observed during the selected time window.

### Card 2: Active Repositories

Number of unique repositories that generated events.

### Card 3: Push Events

Total number of push events.

### Card 4: Commits

Total commits reported by observed push events.

### Card 5: Pull Requests

Number of pull request events.

### Card 6: Forks

Number of fork events.

Each card must include:

- Relevant icon.
- Large animated number.
- Small activity sparkline.
- Percentage change compared with the previous equivalent time window, where historical data is available.
- Tooltip explaining the metric.

Do not invent metrics.

Display unavailable values appropriately.

Use a default 60-minute observation window.

## 5. Main Activity Chart

Create a large interactive chart titled:

**GitHub Activity Over Time**

Display:

- Total events per minute.
- Push events per minute.
- Pull request events per minute.

Use a visually attractive Recharts area or line chart.

Features:

- Smooth live updates.
- Interactive tooltips.
- Multiple colored series.
- Toggle individual series.
- Time range selector: 15 minutes, 1 hour, 6 hours, 24 hours.
- Clear time-axis labels.
- Loading and empty states.

For historical ranges, retrieve aggregated data from PostgreSQL.

For the live window, use Redis-backed counters.

Update the chart automatically through WebSockets.

## 6. Top Five Most Active Repositories

Create a section titled:

**Trending Repositories**

Display the five repositories with the highest observed activity during the selected time window.

Each repository card or table row should display:

- Rank.
- Repository name.
- GitHub owner.
- Repository link.
- Total observed events.
- Push events.
- Pull request events.
- Fork events.
- Activity trend.

Include horizontal activity bars or compact sparklines.

Make repository names clickable, opening GitHub in a new tab.

Rank repositories based on a documented activity score.

For example:

Activity Score =
Push Events × 2 +
Pull Request Events × 3 +
Fork Events × 2 +
Other Events × 1

Make scoring weights configurable.

Important: Clearly distinguish **most active repositories in the observed event stream** from GitHub's globally most popular repositories.

Do not claim these are the most popular repositories across all of GitHub.

## 7. Activity Breakdown

Create a visually engaging section titled:

**What Is Happening on GitHub?**

Display the distribution of event types:

- Push events.
- Pull requests.
- Fork events.
- Watch events, representing stars.
- Issues.
- Issue comments.
- Other supported events.

Use an interactive donut chart or horizontal bar chart.

Display percentages and absolute counts.

Allow users to hover over segments for details.

## 8. Live GitHub Event Feed

Create a section titled:

**Live Activity Feed**

Display the latest 20 processed events.

Each row should show:

- Event type icon.
- Repository name.
- GitHub username.
- Short activity description.
- Relative timestamp.
- Event type badge.
- Link to the relevant GitHub repository or event resource when available.

Examples:

"developer123 pushed 3 commits to facebook/react"

"contributor42 opened a pull request in microsoft/vscode"

"developer78 forked vercel/next.js"

"developer99 starred spring-projects/spring-boot"

Features:

- Automatically insert new events at the top.
- Keep only the latest 20 visible.
- Animate incoming rows.
- Pause and resume live updates.
- Filter by event type.
- Open repository links in a new tab.
- Display an empty state when no events exist.

Use WebSockets for live updates.

Avoid excessive animations that distract from readability.

## 9. Additional Standout Feature: Momentum Detector

Implement one distinctive feature called:

**Rising Repositories**

Detect repositories experiencing unusually high activity compared with their recent baseline.

For example:

A repository normally receives two events every five minutes but suddenly receives 20.

GitHubPulse should identify that spike.

Show:

- Repository name.
- Current event rate.
- Previous baseline.
- Percentage increase.
- Momentum indicator.

Use Kafka Streams windowed aggregations.

Avoid displaying misleading percentage changes when the baseline is zero or too small.

Label results as activity spikes in the observed event sample, not definitive global GitHub trends.

## 10. GitHub Data Ingestion

Use GitHub's public events API:

`https://api.github.com/events`

Implement a Spring Boot ingestion service.

Requirements:

1. Poll GitHub's public events endpoint.
2. Respect GitHub rate limits and polling guidance.
3. Use ETag conditional requests when supported.
4. Respect cache and polling interval headers.
5. Handle API failures gracefully.
6. Deduplicate events using GitHub event IDs.
7. Publish newly observed events to Kafka.
8. Record ingestion timestamps.
9. Monitor ingestion health.

Do not assume GitHub provides an unrestricted real-time WebSocket feed.

The dashboard should clearly communicate that events are near real time and subject to GitHub API sampling, pagination, and polling limitations.

Never hardcode GitHub credentials.

Support an optional GitHub token through environment variables.

## 11. Kafka Architecture

Create these Kafka topics:

`github.events.raw`

`github.events.dlq`

Use a single shared raw-event topic for the MVP.

### Event Processing

The ingestion service publishes GitHub events to Kafka.

A Java Kafka Streams application processes them.

Implement:

- Event type classification.
- Event deduplication.
- Per-minute event counts.
- Push event aggregation.
- Pull request aggregation.
- Repository activity ranking.
- Activity spike detection.
- Failed event handling.

Use event IDs for idempotency.

Configure retries and dead-letter handling.

Malformed events must not crash the entire stream processor.

Use Kafka event timestamps appropriately and document handling for late-arriving events.

### Reliability

Implement:

- At-least-once ingestion with deduplication.
- Idempotent database writes.
- Safe consumer offset management.
- Retry handling.
- Dead-letter topic.
- Graceful shutdown.
- Structured logging.

Do not claim end-to-end exactly-once delivery unless it is actually guaranteed.

## 12. Redis

Use Redis for:

- Live event counters.
- Recent repository rankings.
- Recent activity snapshots.
- Fast dashboard queries.

Use appropriate key expiration policies.

Redis must not be the only source of durable historical data.

## 13. PostgreSQL

Use PostgreSQL for persistent storage.

Create tables for:

- GitHub events.
- Repository metadata.
- Minute-level event aggregates.
- Repository activity aggregates.
- Processing failures, if useful.

Implement appropriate indexes.

Use Flyway for database migrations.

Store only necessary fields from GitHub event payloads.

Implement retention policies to avoid unlimited storage growth.

## 14. WebSocket Updates

Use Spring Boot WebSockets.

Broadcast:

- KPI updates.
- New events.
- Activity chart updates.
- Trending repository changes.
- Momentum alerts.

Avoid broadcasting every individual database operation.

Batch or throttle dashboard updates to approximately every 2–5 seconds.

Implement reconnection handling on the frontend.

On reconnection, fetch a fresh snapshot to recover missed updates.

## 15. gRPC Integration

Include gRPC for meaningful internal service communication.

Create two backend services:

**Ingestion Service**
- Polls GitHub.
- Publishes events to Kafka.
- Exposes ingestion status through gRPC.

**Analytics Service**
- Runs Kafka Streams processing.
- Maintains Redis counters.
- Persists historical data.
- Serves dashboard APIs and WebSocket updates.

Define a protobuf contract for:

`GetIngestionStatus`

Return:

- Last successful GitHub API poll.
- Number of events ingested.
- Current ingestion status.
- Last error, if any.

The analytics service should use gRPC to retrieve ingestion health and expose it to the dashboard.

Do not use gRPC to duplicate Kafka's asynchronous event delivery responsibilities.

Generate Java protobuf classes as part of the Gradle build.

## 16. Backend APIs

Expose REST endpoints for initial dashboard loading.

Examples:

`GET /api/metrics`

`GET /api/activity?range=1h`

`GET /api/repositories/trending`

`GET /api/events/recent`

`GET /api/activity/breakdown`

`GET /api/repositories/rising`

`GET /api/system/status`

Use WebSockets for subsequent live updates.

Document the API using OpenAPI and Swagger UI.

## 17. Interactive Demo Mode

Recruiters must be able to try the application immediately.

Provide a clearly labeled:

**Launch Demo Activity**

button.

When clicked, generate a realistic simulated GitHub event workload.

The simulator should support:

- Push events.
- Pull requests.
- Fork events.
- Stars.
- Activity spikes.
- Multiple repositories.

Simulated data must be visibly labeled **Demo Data**.

Never mix simulated activity into real GitHub metrics without clearly separating it.

The demo should work even if GitHub's API is temporarily unavailable.

Provide a **Return to Live Data** button.

The application should automatically load real data when available.

## 18. Deployment

Containerize all services.

Create Docker Compose configuration for:

- Frontend.
- Ingestion service.
- Analytics service.
- Kafka in KRaft mode.
- Redis.
- PostgreSQL.

Use Terraform to provision AWS infrastructure.

For the initial deployment, use:

- One appropriately sized EC2 instance.
- Docker Compose.
- Security groups.
- Required networking.
- Persistent storage.
- Environment-based configuration.

Do not introduce Kubernetes for the MVP.

Avoid exposing Kafka, Redis, PostgreSQL, or gRPC directly to the public internet.

Expose only the frontend and required HTTP/WebSocket endpoints through a reverse proxy.

Use HTTPS for public deployment.

Provide deployment instructions, cost considerations, and Terraform cleanup commands.

Do not provision paid cloud resources automatically without explicit approval.

## 19. Testing

Implement automated tests for:

- Event parsing.
- Kafka event processing.
- Deduplication.
- Minute-level aggregations.
- Repository ranking.
- Momentum detection.
- Database persistence.
- REST endpoints.
- gRPC service.
- WebSocket message formatting.

Use:

- JUnit 5.
- Mockito.
- Testcontainers where appropriate.

Include a simple load-testing script.

Measure:

- Events processed per second.
- Event processing latency.
- API response latency.
- WebSocket update delay.
- Consumer lag.

Report actual measurements rather than fabricated benchmarks.

## 20. GitHub Repository

Create a professional README containing:

- Project overview.
- Architecture diagram.
- Technology stack.
- Features.
- Dashboard screenshots.
- Local setup instructions.
- Docker Compose instructions.
- Terraform deployment instructions.
- API documentation.
- Testing instructions.
- Engineering tradeoffs.
- Known limitations.

Include:

- `.env.example`
- `.gitignore`
- `LICENSE` using MIT.
- GitHub Actions CI workflow.

Never commit credentials or secrets.

## 21. Project Structure

Use a clean monorepo structure:

```text
githubpulse/
├── frontend/
├── services/
│   ├── ingestion-service/
│   └── analytics-service/
├── proto/
├── infrastructure/
│   └── terraform/
├── docker/
├── scripts/
├── docs/
├── docker-compose.yml
├── .env.example
└── README.md
```

## 22. Implementation Priorities

Build in this order:

**Phase 1: Functional Backend**
- GitHub API ingestion.
- Kafka producer and consumer.
- PostgreSQL persistence.
- Redis counters.
- REST APIs.

**Phase 2: Real-Time Processing**
- Kafka Streams aggregations.
- WebSocket broadcasting.
- Repository rankings.
- Event deduplication.

**Phase 3: Frontend**
- Professional single-page dashboard.
- Six KPI cards.
- Activity chart.
- Top five repositories.
- Activity breakdown.
- Live event feed.
- Momentum detector.

**Phase 4: Distributed Systems**
- gRPC service communication.
- Dead-letter handling.
- Failure recovery.
- Automated tests.

**Phase 5: Deployment**
- Docker Compose.
- Terraform.
- Deployment documentation.
- CI/CD.

Prioritize a working end-to-end application over implementing every optional enhancement.

## 23. Acceptance Criteria

The project is complete when:

1. The dashboard loads without authentication.
2. Real GitHub events are ingested successfully.
3. Kafka processes events asynchronously.
4. Redis provides live metrics.
5. PostgreSQL stores historical activity.
6. WebSockets update the dashboard automatically.
7. The six KPI cards display accurate metrics.
8. The activity chart shows event rates over time.
9. The top five repositories update dynamically.
10. The live event feed displays the latest 20 events.
11. Repository activity spikes are detected.
12. Duplicate events do not inflate analytics.
13. Failed events are handled without crashing the pipeline.
14. gRPC successfully exposes ingestion health.
15. Demo mode works independently of GitHub API availability.
16. Docker Compose starts the complete local application.
17. Terraform configuration is valid.
18. The frontend is responsive on desktop and mobile.
19. Automated tests pass.
20. The README explains how to run and deploy everything.

## Final Instructions

Implement the complete project directly in the current working directory.

Do not stop after creating a project plan.

Do not ask me for confirmation between implementation phases.

Make reasonable technical decisions independently.

If credentials or cloud permissions are unavailable, implement everything that can run locally and provide exact deployment instructions.

Do not claim that cloud deployment succeeded unless it was actually performed and verified.

Run available builds and tests, fix errors, and verify the application works.

The final result should be a visually impressive, functional, real-time distributed systems project suitable for demonstrating Java backend engineering skills to recruiters.