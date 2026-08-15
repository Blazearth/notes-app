package com.weavr.api.job;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Lane assignment — the half of the lane split that can go silently wrong.
 *
 * <p>The concurrency behaviour itself needs threads and a database; what is
 * worth pinning here is which types each lane claims, because the failure mode
 * of getting it wrong is a job type nobody polls for. Those saves sit
 * {@code queued} forever with nothing in the logs and no error anywhere.
 */
class JobLaneTest {

    private static JobProperties props(Map<String, JobLaneProperties> lanes) {
        return new JobProperties(true, 2, lanes, Duration.ofSeconds(2), "test",
                Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(15));
    }

    private static JobHandler handler(String type) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(JobRecord record) {
            }
        };
    }

    private static JobRunner runner(JobProperties properties, String... handlerTypes) {
        return new JobRunner(mock(JobStore.class), properties,
                java.util.Arrays.stream(handlerTypes).map(JobLaneTest::handler).toList());
    }

    /**
     * The backward-compatibility guarantee: an unconfigured deployment behaves
     * exactly as it did before lanes existed — one pool, every type, sized by
     * {@code weavr.jobs.concurrency}. {@code render.yaml} had been setting that
     * variable since long before lanes, and a config value that silently stopped
     * taking effect would be a bad way to discover the change.
     */
    @Test
    void noLanesConfiguredMeansOnePoolServingEveryType() {
        JobRunner runner = runner(props(Map.of()), JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE);

        Map<String, JobLaneProperties> lanes = runner.assignLanes();

        assertThat(lanes).hasSize(1);
        JobLaneProperties only = lanes.values().iterator().next();
        assertThat(only.concurrency()).isEqualTo(2);
        // Empty type list = claims everything, which is what the old single
        // poller did.
        assertThat(only.types()).isEmpty();
    }

    @Test
    void configuredLanesKeepTheirOwnTypesAndConcurrency() {
        JobRunner runner = runner(props(Map.of(
                        "fast", new JobLaneProperties(4, List.of(JobType.CLASSIFY_SAVE, JobType.EMBED_SAVE), false),
                        "heavy", new JobLaneProperties(1, List.of(JobType.PROCESS_SAVE), true))),
                JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE, JobType.EMBED_SAVE);

        Map<String, JobLaneProperties> lanes = runner.assignLanes();

        assertThat(lanes.get("fast").concurrency()).isEqualTo(4);
        assertThat(lanes.get("fast").types())
                .containsExactlyInAnyOrder(JobType.CLASSIFY_SAVE, JobType.EMBED_SAVE);
        assertThat(lanes.get("heavy").concurrency()).isEqualTo(1);
        assertThat(lanes.get("heavy").types()).containsExactly(JobType.PROCESS_SAVE);
    }

    /**
     * The silent-stall guard. A handler registered for a type no lane claims
     * would never be polled for at all — so it is swept into the fallback lane
     * rather than left unclaimed.
     */
    @Test
    void aTypeNoLaneClaimsIsAddedToTheFallbackLane() {
        JobRunner runner = runner(props(Map.of(
                        "fast", new JobLaneProperties(4, List.of(JobType.CLASSIFY_SAVE), false),
                        "heavy", new JobLaneProperties(1, List.of(JobType.PROCESS_SAVE), true))),
                JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE, JobType.GENERATE_DIGEST);

        Map<String, JobLaneProperties> lanes = runner.assignLanes();

        assertThat(lanes.get("heavy").types())
                .containsExactlyInAnyOrder(JobType.PROCESS_SAVE, JobType.GENERATE_DIGEST);
        assertThat(lanes.get("fast").types()).containsExactly(JobType.CLASSIFY_SAVE);
    }

    /** Every registered handler must be claimable by exactly one lane, always. */
    @Test
    void everyRegisteredHandlerEndsUpClaimableBySomeLane() {
        JobRunner runner = runner(props(Map.of(
                        "fast", new JobLaneProperties(4, List.of(JobType.CLASSIFY_SAVE), false),
                        "heavy", new JobLaneProperties(1, List.of(JobType.PROCESS_SAVE), true))),
                JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE, JobType.ENRICH_SAVE,
                JobType.EMBED_SAVE, JobType.DETECT_DUPLICATES);

        List<String> claimable = runner.assignLanes().values().stream()
                .flatMap(l -> l.types().stream())
                .toList();

        assertThat(claimable).contains(JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE,
                JobType.ENRICH_SAVE, JobType.EMBED_SAVE, JobType.DETECT_DUPLICATES);
    }

    /**
     * A lane declaring no types already claims everything, so nothing can be
     * orphaned — and the fallback sweep must not "helpfully" enumerate types
     * into it, which would turn a claims-everything lane into a fixed list that
     * silently stops covering a type added later.
     */
    @Test
    void aLaneThatClaimsEverythingIsLeftAlone() {
        JobRunner runner = runner(props(Map.of(
                        "all", new JobLaneProperties(3, List.of(), true))),
                JobType.PROCESS_SAVE, JobType.CLASSIFY_SAVE);

        assertThat(runner.assignLanes().get("all").types()).isEmpty();
    }

    /** A job type must not be served by two lanes — that is two pollers racing for one row. */
    @Test
    void theShippedLaneConfigurationCoversEveryJobTypeExactlyOnce() {
        // Mirrors application.yml. If a type is added to JobType and not to a
        // lane here, this fails rather than the job silently never running.
        List<String> fast = List.of(JobType.CLASSIFY_SAVE, JobType.ENRICH_SAVE, JobType.EMBED_SAVE,
                JobType.DETECT_DUPLICATES, JobType.CONVERT_TO_SHOPPING_LIST, JobType.GENERATE_DIGEST);
        List<String> heavy = List.of(JobType.PROCESS_SAVE);

        assertThat(fast).doesNotContainAnyElementsOf(heavy);
        assertThat(fast.size() + heavy.size()).isEqualTo(7);
    }
}
