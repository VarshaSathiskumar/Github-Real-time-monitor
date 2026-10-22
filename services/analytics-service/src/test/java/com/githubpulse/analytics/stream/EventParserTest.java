package com.githubpulse.analytics.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.githubpulse.analytics.TestProps;
import com.githubpulse.analytics.model.EventKind;
import com.githubpulse.analytics.model.ParsedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventParserTest {

    private static final String TS = "2026-03-04T05:06:07Z";

    @ParameterizedTest
    @CsvSource({
        "PushEvent,PUSH",
        "PullRequestEvent,PULL_REQUEST",
        "ForkEvent,FORK",
        "WatchEvent,WATCH",
        "IssuesEvent,ISSUES",
        "IssueCommentEvent,ISSUE_COMMENT",
        "CreateEvent,OTHER",
        "ReleaseEvent,OTHER"
    })
    void classifiesEventTypes(String type, EventKind expected) {
        ParsedEvent e = EventParser.parse(TestProps.eventJson(1, type, "octo/repo", "alice", TS, "{}"));
        assertThat(e.kind()).isEqualTo(expected);
        assertThat(e.repoName()).isEqualTo("octo/repo");
        assertThat(e.owner()).isEqualTo("octo");
        assertThat(e.actor()).isEqualTo("alice");
    }

    @Test
    void pushWithCommitCountProducesDescription() {
        ParsedEvent e = EventParser.parse(TestProps.eventJson(
                7, "PushEvent", "facebook/react", "developer123", TS, "{\"size\":3,\"ref\":\"refs/heads/main\"}"));

        assertThat(e.commits()).isEqualTo(3);
        assertThat(e.description()).isEqualTo("developer123 pushed 3 commits to facebook/react");
        assertThat(e.url()).isEqualTo("https://github.com/facebook/react/commits/main");
    }

    @Test
    void pushWithoutCommitCountIsReportedAsUnknownNotZero() {
        ParsedEvent e = EventParser.parse(TestProps.pushJson(8, "octo/repo"));

        assertThat(e.commits()).isNull();
        assertThat(e.description()).isEqualTo("dev8 pushed to octo/repo");
    }

    @Test
    void usesPullRequestAndForkLinks() {
        ParsedEvent pr = EventParser.parse(TestProps.eventJson(
                2, "PullRequestEvent", "microsoft/vscode", "contributor42", TS,
                "{\"action\":\"opened\",\"pull_request\":{\"html_url\":\"https://github.com/microsoft/vscode/pull/9\"}}"));
        ParsedEvent fork = EventParser.parse(TestProps.eventJson(
                3, "ForkEvent", "vercel/next.js", "developer78", TS,
                "{\"forkee\":{\"html_url\":\"https://github.com/developer78/next.js\"}}"));

        assertThat(pr.description()).isEqualTo("contributor42 opened a pull request in microsoft/vscode");
        assertThat(pr.url()).endsWith("/pull/9");
        assertThat(fork.description()).isEqualTo("developer78 forked vercel/next.js");
        assertThat(fork.url()).isEqualTo("https://github.com/developer78/next.js");
    }

    @Test
    void watchEventIsAStar() {
        ParsedEvent e = EventParser.parse(TestProps.eventJson(4, "WatchEvent", "spring-projects/spring-boot", "developer99", TS, "{}"));
        assertThat(e.description()).isEqualTo("developer99 starred spring-projects/spring-boot");
    }

    @Test
    void readsTimestamps() {
        ParsedEvent e = EventParser.parse(TestProps.eventJson(5, "WatchEvent", "a/b", "c", TS, "{}"));
        assertThat(e.createdAt()).hasToString(TS);
        assertThat(e.ingestedAt()).hasToString(TS);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "not json at all",
                "[]",
                "{}",
                "{\"id\":\"abc\",\"type\":\"PushEvent\",\"actor\":{\"login\":\"a\"},\"repo\":{\"name\":\"o/r\"},\"created_at\":\"2026-01-01T00:00:00Z\"}",
                "{\"id\":\"1\",\"type\":\"PushEvent\",\"actor\":{},\"repo\":{\"name\":\"o/r\"},\"created_at\":\"2026-01-01T00:00:00Z\"}",
                "{\"id\":\"1\",\"type\":\"PushEvent\",\"actor\":{\"login\":\"a\"},\"repo\":{\"name\":\"norepo\"},\"created_at\":\"2026-01-01T00:00:00Z\"}",
                "{\"id\":\"1\",\"type\":\"PushEvent\",\"actor\":{\"login\":\"a\"},\"repo\":{\"name\":\"o/r\"},\"created_at\":\"yesterday\"}"
            })
    void rejectsMalformedPayloads(String json) {
        assertThatThrownBy(() -> EventParser.parse(json)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> EventParser.parse("  ")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> EventParser.parse(null)).isInstanceOf(MalformedEventException.class);
    }
}
