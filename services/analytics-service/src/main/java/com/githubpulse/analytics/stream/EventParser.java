package com.githubpulse.analytics.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.ParsedEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Turns a raw GitHub event JSON document into a {@link ParsedEvent}, rejecting malformed input. */
public final class EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GITHUB = "https://github.com/";

    private EventParser() {}

    public static ParsedEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedEventException("Empty payload");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new MalformedEventException("Invalid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("Payload is not a JSON object");
        }

        long id = parseId(root.path("id"));
        String type = requireText(root, "type");
        String actor = requireText(root.path("actor"), "login");
        String repoName = requireText(root.path("repo"), "name");
        if (repoName.indexOf('/') < 1) {
            throw new MalformedEventException("repo.name is not owner/name: " + repoName);
        }
        long repoId = root.path("repo").path("id").asLong(-Math.abs((long) repoName.hashCode()));
        Instant createdAt = parseInstant(requireText(root, "created_at"), "created_at");
        Instant ingestedAt = root.hasNonNull("ingested_at")
                ? parseInstant(root.get("ingested_at").asText(), "ingested_at")
                : createdAt;

        EventKind kind = EventKind.fromGithubType(type);
        JsonNode payload = root.path("payload");
        String action = payload.path("action").asText(null);
        String repoUrl = GITHUB + repoName;
        Integer commits = null;
        String url = repoUrl;
        String description;

        switch (kind) {
            case PUSH -> {
                commits = commitCount(payload);
                String branch = payload.path("ref").asText("").replaceFirst("^refs/heads/", "");
                url = branch.isBlank() ? repoUrl : repoUrl + "/commits/" + branch;
                description = commits != null && commits > 0
                        ? actor + " pushed " + commits + (commits == 1 ? " commit" : " commits") + " to " + repoName
                        : actor + " pushed to " + repoName;
            }
            case PULL_REQUEST -> {
                url = textOr(payload.path("pull_request").path("html_url"), repoUrl + "/pulls");
                description = actor + " " + pastTense(action, "opened") + " a pull request in " + repoName;
            }
            case FORK -> {
                url = textOr(payload.path("forkee").path("html_url"), repoUrl + "/forks");
                description = actor + " forked " + repoName;
            }
            case WATCH -> {
                url = repoUrl + "/stargazers";
                description = actor + " starred " + repoName;
            }
            case ISSUES -> {
                url = textOr(payload.path("issue").path("html_url"), repoUrl + "/issues");
                description = actor + " " + pastTense(action, "opened") + " an issue in " + repoName;
            }
            case ISSUE_COMMENT -> {
                url = textOr(payload.path("comment").path("html_url"), repoUrl + "/issues");
                description = actor + " commented on an issue in " + repoName;
            }
            default -> description = actor + " triggered " + humanize(type) + " in " + repoName;
        }

        return new ParsedEvent(
                id, type, kind, actor, repoId, repoName, repoUrl, commits, action, description, url, createdAt,
                ingestedAt, ingestedAt);
    }

    /** GitHub's events API stopped including commit counts in push payloads; null means not reported. */
    private static Integer commitCount(JsonNode payload) {
        if (payload.has("size") && payload.get("size").canConvertToInt()) {
            return Math.max(0, payload.get("size").asInt());
        }
        if (payload.has("distinct_size") && payload.get("distinct_size").canConvertToInt()) {
            return Math.max(0, payload.get("distinct_size").asInt());
        }
        if (payload.path("commits").isArray()) {
            return payload.get("commits").size();
        }
        return null;
    }

    private static long parseId(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw new MalformedEventException("Missing event id");
        }
        try {
            return Long.parseLong(node.asText().trim());
        } catch (NumberFormatException e) {
            throw new MalformedEventException("Event id is not numeric: " + node.asText());
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) {
            throw new MalformedEventException("Missing field: " + field);
        }
        return n.asText();
    }

    private static Instant parseInstant(String s, String field) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new MalformedEventException("Bad timestamp in " + field + ": " + s, e);
        }
    }

    private static String textOr(JsonNode n, String fallback) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : fallback;
    }

    private static String pastTense(String action, String fallback) {
        if (action == null || action.isBlank()) {
            return fallback;
        }
        return switch (action) {
            case "opened", "closed", "reopened", "edited", "assigned", "labeled" -> action;
            case "synchronize" -> "updated";
            default -> action.replace('_', ' ');
        };
    }

    private static String humanize(String type) {
        String base = type.endsWith("Event") ? type.substring(0, type.length() - 5) : type;
        return base.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase() + " activity";
    }
}
