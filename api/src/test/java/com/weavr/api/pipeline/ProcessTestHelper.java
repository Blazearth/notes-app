package com.weavr.api.pipeline;

/**
 * A real child process for {@link ExternalProcessTest} to drive.
 *
 * <p>Spawning a JVM is heavier than shelling out to {@code echo}, but it is the
 * only way to reproduce the hazards identically on Windows and Linux — and these
 * particular hazards (a full pipe buffer, a process that never exits) cannot be
 * faked with a mock, because the thing under test *is* the OS interaction.
 */
public final class ProcessTestHelper {

    /** Comfortably past the ~64 KB OS pipe buffer on every platform. */
    static final int FLOOD_BYTES = 1_000_000;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "quiet";

        switch (mode) {
            // Fills BOTH pipes. If the parent drains them one after the other,
            // this deadlocks and the test times out.
            case "flood" -> {
                StringBuilder chunk = new StringBuilder();
                chunk.append("x".repeat(1000));
                for (int written = 0; written < FLOOD_BYTES; written += 1000) {
                    System.out.print(chunk);
                    System.err.print(chunk);
                }
                System.out.flush();
                System.err.flush();
            }

            // Never exits on its own; the parent must kill it.
            case "hang" -> Thread.sleep(Long.MAX_VALUE);

            case "fail" -> {
                System.err.print("ERROR: Unsupported URL: https://example.com/nope");
                System.exit(3);
            }

            case "both" -> {
                System.out.print("out-content");
                System.err.print("err-content");
            }

            default -> {
                // exit 0, no output
            }
        }
    }

    private ProcessTestHelper() {
    }
}
