package com.bwell.conventions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives gradle/check-rootio-ignores.gradle against a throwaway project whose jfrogUrl points at a
 * local stub HTTP server, so only the external boundary (Artifactory) is faked.
 */
class CheckRootioIgnoresTest {

    private static final String TOKEN = "SECRET-TOKEN-VALUE";
    private static final String BASE = "/artifactory/virtual-maven";
    private static final String ZSTD = "com.github.luben:zstd-jni@1.5.6-10-aikido.1";
    private static final String ZSTD_PATH =
            BASE + "/com/github/luben/zstd-jni/1.5.6-10-aikido.1/zstd-jni-1.5.6-10-aikido.1";
    private static final String KAFKA = "org.apache.kafka:kafka-clients@3.8.1-aikido.3";
    private static final String KAFKA_PATH =
            BASE + "/org/apache/kafka/kafka-clients/3.8.1-aikido.3/kafka-clients-3.8.1-aikido.3";

    @TempDir Path project;

    private HttpServer server;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            int status = statusByPath.getOrDefault(exchange.getRequestURI().getPath(), 404);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void failsBuildAndNamesTheEntryWhenJarIsPublished() throws IOException {
        // GIVEN an ignore entry whose jar is now published
        publish(ZSTD_PATH);
        writeIgnoreFile("# header", "", ZSTD);

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN the build fails and tells the developer exactly what to do
        String out = result.getOutput();
        assertTrue(out.contains(".rootioignore:3"), out);
        assertTrue(out.contains(ZSTD), out);
        assertTrue(out.contains("./gradlew dependencies --write-locks"), out);
    }

    @Test
    void passesWhenJarIsStillUnpublishedEvenIfPomIsPublished() throws IOException {
        // GIVEN the zstd-jni situation: pom 200, jar 404
        statusByPath.put(ZSTD_PATH + ".pom", 200);
        writeIgnoreFile(ZSTD);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN the entry is reported as still needed and the build passes
        assertTrue(result.getOutput().contains("still needed"), result.getOutput());
        assertFalse(result.getOutput().contains("--write-locks"), result.getOutput());
    }

    @Test
    void warnOnlyPropertyReportsStaleEntriesWithoutFailing() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs with the opt-out property
        BuildResult result = run(false, "checkRootioIgnores", "-ProotioIgnoreWarnOnly=true");

        // THEN it is still reported but the build passes
        assertTrue(result.getOutput().contains(KAFKA), result.getOutput());
    }

    @Test
    void reportsOnlyThePublishedEntriesInAMixedFile() throws IOException {
        // GIVEN one published and one unpublished entry
        publish(KAFKA_PATH);
        writeIgnoreFile("# comment", KAFKA, "", ZSTD);

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN only the published one is flagged as stale
        List<String> staleLines = result.getOutput().lines().filter(l -> l.contains("STALE")).toList();
        assertEquals(1, staleLines.size(), result.getOutput());
        assertTrue(staleLines.get(0).contains(".rootioignore:2"), staleLines.toString());
        assertTrue(result.getOutput().contains("still needed   .rootioignore:4"), result.getOutput());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503})
    void unexpectedHttpStatusIsUnknownAndNeverFailsTheBuild(int status) throws IOException {
        // GIVEN Artifactory answers with an error instead of 200/404
        statusByPath.put(KAFKA_PATH + ".jar", status);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN the entry is reported as unknown and the build passes
        assertTrue(result.getOutput().contains("could not verify"), result.getOutput());
    }

    @Test
    void unreachableRepositoryDoesNotFailTheBuild() throws IOException {
        // GIVEN the repository is down
        server.stop(0);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it is reported as unknown and the build passes
        assertTrue(result.getOutput().contains("could not verify"), result.getOutput());
    }

    @Test
    void probesTheMavenLayoutWithHeadAndBasicAuthWithoutLeakingTheToken() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN the jar is probed with HEAD, authenticated, and the token is never logged
        assertTrue(requests.contains("HEAD " + KAFKA_PATH + ".jar"), requests.toString());
        String expectedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString(("user:" + TOKEN).getBytes());
        assertTrue(authHeaders.stream().allMatch(expectedAuth::equals), authHeaders.toString());
        assertFalse(result.getOutput().contains(TOKEN), result.getOutput());
        assertFalse(result.getOutput().contains(expectedAuth), result.getOutput());
    }

    @Test
    void malformedLinesAreWarnedAboutAndDoNotFailTheBuild() throws IOException {
        // GIVEN an entry that is not group:artifact@version
        writeIgnoreFile("not-a-coordinate", "org.foo:bar@");

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN each bad line is called out with its line number
        String out = result.getOutput();
        assertTrue(out.contains(".rootioignore:1"), out);
        assertTrue(out.contains(".rootioignore:2"), out);
        assertTrue(out.contains("malformed"), out);
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void skipsWhenThereIsNoIgnoreFile() throws IOException {
        // GIVEN no .rootioignore
        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN nothing is probed
        assertTrue(result.getOutput().contains("No .rootioignore entries"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void skipsWhenIgnoreFileHasOnlyComments() throws IOException {
        // GIVEN a comments-only file
        writeIgnoreFile("# nothing here", "");

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN nothing is probed
        assertTrue(result.getOutput().contains("No .rootioignore entries"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void skipsWithoutCredentialsInsteadOfFailing() throws IOException {
        // GIVEN no JFrog credentials
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        writeBuild(false);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it skips and makes no requests
        assertTrue(result.getOutput().contains("JFrog credentials"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void skipPropertyDisablesTheCheck() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs with the skip property
        BuildResult result = run(false, "checkRootioIgnores", "-ProotioIgnoreSkip=true");

        // THEN nothing is probed
        assertTrue(requests.isEmpty(), requests.toString());
        assertTrue(result.getOutput().contains("skipped"), result.getOutput());
    }

    @Test
    void offlineModeSkipsTheCheck() throws IOException {
        // GIVEN Gradle runs offline with a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores", "--offline");

        // THEN nothing is probed
        assertTrue(requests.isEmpty(), requests.toString());
        assertTrue(result.getOutput().contains("offline"), result.getOutput());
    }

    @Test
    void isWiredIntoCheckSoBuildFailsOnStaleEntries() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the regular check lifecycle task runs
        BuildResult result = run(true, "check");

        // THEN the stale entry fails it
        assertTrue(result.getOutput().contains(KAFKA), result.getOutput());
    }

    @Test
    void wiresIntoCheckEvenWhenAppliedBeforeTheJavaPlugin() throws IOException {
        // GIVEN the script is applied before the java plugin creates the check task
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        Path script = Path.of("..", "gradle", "check-rootio-ignores.gradle").toAbsolutePath().normalize();
        Files.writeString(project.resolve("build.gradle"),
                "apply from: '" + script + "'\napply plugin: 'java'\n");

        // WHEN the regular check lifecycle task runs
        BuildResult result = run(true, "check");

        // THEN it is still wired in and fails on the stale entry
        assertTrue(result.getOutput().contains(KAFKA), result.getOutput());
    }

    @Test
    void isNotRunByTheTestTask() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN only the test task runs
        BuildResult result = run(false, "test");

        // THEN the network check does not run
        assertEquals(0, requests.size(), requests.toString());
        assertFalse(result.getOutput().contains("checkRootioIgnores"), result.getOutput());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void publish(String pathWithoutExtension) {
        statusByPath.put(pathWithoutExtension + ".jar", 200);
        statusByPath.put(pathWithoutExtension + ".pom", 200);
    }

    private void writeIgnoreFile(String... lines) throws IOException {
        Files.write(project.resolve(".rootioignore"), List.of(lines));
        writeBuild(true);
    }

    private void writeBuild(boolean withCredentials) throws IOException {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + BASE;
        String creds = withCredentials
                ? "gradle.ext.jfrogUser = 'user'\ngradle.ext.jfrogToken = '" + TOKEN + "'\n"
                : "gradle.ext.jfrogUser = null\ngradle.ext.jfrogToken = null\n";
        Files.writeString(project.resolve("settings.gradle"),
                "rootProject.name = 'sample'\ngradle.ext.jfrogUrl = '" + url + "'\n" + creds);
        Path script = Path.of("..", "gradle", "check-rootio-ignores.gradle").toAbsolutePath().normalize();
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'java' }\napply from: '" + script + "'\n");
    }

    private BuildResult run(boolean expectFailure, String... args) throws IOException {
        if (!Files.exists(project.resolve("build.gradle"))) {
            writeBuild(true);
        }
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments(args)
                .forwardOutput();
        return expectFailure ? runner.buildAndFail() : runner.build();
    }
}
