package com.bwell.conventions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
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

    private enum Settings { WITH_CREDENTIALS, NULL_CREDENTIALS, NO_EXT_AT_ALL }

    @TempDir Path project;

    private HttpServer server;
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, String> redirectByPath = new ConcurrentHashMap<>();
    private final Map<String, Long> delayMillisByPath = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try {
                Thread.sleep(delayMillisByPath.getOrDefault(path, 0L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String redirect = redirectByPath.get(path);
            if (redirect != null) {
                exchange.getResponseHeaders().add("Location", redirect);
            }
            exchange.sendResponseHeaders(statusByPath.getOrDefault(path, 404), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    // ── decisions: published / unpublished / unknown ─────────────────────────

    @Test
    void failsBuildAndNamesTheEntryWhenJarIsPublished() throws IOException {
        // GIVEN an ignore entry whose jar is now published
        publish(ZSTD_PATH);
        writeIgnoreFile("# header", "", ZSTD);

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN the build fails and tells the developer exactly what to do
        String out = result.getOutput();
        assertLine(out, "STALE", 3);
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
        assertLine(result.getOutput(), "still needed", 1);
        assertFalse(result.getOutput().contains("--write-locks"), result.getOutput());
    }

    @Test
    void warnOnlyPropertyReportsStaleEntriesWithoutFailing() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs with the opt-out property
        BuildResult result = run(false, "checkRootioIgnores", "-ProotioIgnoreWarnOnly=true");

        // THEN it is still classified and explained as stale, but the build passes
        assertLine(result.getOutput(), "STALE", 1);
        assertTrue(result.getOutput().contains("--write-locks"), result.getOutput());
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
        assertLine(result.getOutput(), "STALE", 2);
        assertLine(result.getOutput(), "still needed", 4);
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
        assertLine(result.getOutput(), "could not verify", 1);
        assertTrue(result.getOutput().contains("HTTP " + status), result.getOutput());
    }

    @Test
    void warnsLoudlyWhenNoEntryCouldBeVerified() throws IOException {
        // GIVEN every probe is rejected (e.g. expired token)
        statusByPath.put(KAFKA_PATH + ".jar", 401);
        statusByPath.put(ZSTD_PATH + ".jar", 401);
        writeIgnoreFile(KAFKA, ZSTD);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN a summary warns that nothing was verified instead of silently passing
        assertTrue(result.getOutput().contains("could not verify any"), result.getOutput());
    }

    @Test
    void unreachableRepositoryDoesNotFailTheBuildAndSaysWhy() throws IOException {
        // GIVEN the repository is down (the stopped server's port stays assigned to the closed socket)
        server.stop(0);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it is reported as unknown with the exception type, not swallowed silently
        assertLine(result.getOutput(), "could not verify", 1);
        assertTrue(result.getOutput().contains("ConnectException"), result.getOutput());
    }

    @Test
    void hungRepositoryTimesOutAsUnknown() throws IOException {
        // GIVEN Artifactory accepts the connection but never answers within the timeout
        delayMillisByPath.put(KAFKA_PATH + ".jar", 3000L);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs with a short timeout
        BuildResult result = run(false, "checkRootioIgnores", "-ProotioIgnoreTimeoutMs=500");

        // THEN the entry is unknown and the build passes
        assertLine(result.getOutput(), "could not verify", 1);
    }

    @Test
    void doesNotFollowRedirectsSoCredentialsStayOnTheConfiguredHost() throws IOException {
        // GIVEN the jar URL redirects to another URL that would answer 200
        String target = BASE + "/elsewhere/kafka-clients.jar";
        redirectByPath.put(KAFKA_PATH + ".jar", "http://127.0.0.1:" + server.getAddress().getPort() + target);
        statusByPath.put(KAFKA_PATH + ".jar", 302);
        statusByPath.put(target, 200);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN the redirect is not followed and the entry is unknown rather than stale
        assertFalse(requests.contains("HEAD " + target), requests.toString());
        assertLine(result.getOutput(), "could not verify", 1);
    }

    // ── request shape and secrets ────────────────────────────────────────────

    @Test
    void probesTheMavenLayoutWithHeadAndBasicAuthWithoutLeakingTheToken() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN the jar is probed with HEAD, authenticated, and the token is never logged
        assertTrue(requests.contains("HEAD " + KAFKA_PATH + ".jar"), requests.toString());
        String expectedAuth = "Basic "
                + Base64.getEncoder().encodeToString(("user:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertTrue(authHeaders.stream().allMatch(expectedAuth::equals), authHeaders.toString());
        assertFalse(result.getOutput().contains(TOKEN), result.getOutput());
        assertFalse(result.getOutput().contains(expectedAuth), result.getOutput());
    }

    @Test
    void toleratesATrailingSlashOnTheRepositoryUrl() throws IOException {
        // GIVEN a base URL ending in a slash
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        writeBuild(Settings.WITH_CREDENTIALS, baseUrl() + "/");

        // WHEN the check runs
        run(true, "checkRootioIgnores");

        // THEN the request path has no doubled slash
        assertTrue(requests.contains("HEAD " + KAFKA_PATH + ".jar"), requests.toString());
    }

    @Test
    void refusesToSendCredentialsOverPlainHttpToARemoteHost() throws IOException {
        // GIVEN a non-loopback http:// repository URL
        writeIgnoreFile(KAFKA);
        writeBuild(Settings.WITH_CREDENTIALS, "http://artifacts.example.invalid/artifactory/virtual-maven");

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it skips with an explanation instead of sending Basic credentials in clear text
        assertTrue(result.getOutput().contains("plain http"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    void probesMoreEntriesThanTheWorkerPoolSize() throws IOException {
        // GIVEN 12 published entries (more than the 8 worker threads)
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String path = BASE + "/org/example/lib" + i + "/1.0." + i + "/lib" + i + "-1.0." + i;
            publish(path);
            lines.add("org.example:lib" + i + "@1.0." + i);
        }
        writeIgnoreFile(lines.toArray(new String[0]));

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN every entry is probed and reported stale
        assertEquals(12, result.getOutput().lines().filter(l -> l.contains("STALE")).count(), result.getOutput());
    }

    @Test
    void parsesFilesWithWindowsLineEndings() throws IOException {
        // GIVEN a CRLF .rootioignore with a published entry
        publish(KAFKA_PATH);
        Files.writeString(project.resolve(".rootioignore"), "# header\r\n" + KAFKA + "\r\n");
        writeBuild(Settings.WITH_CREDENTIALS, baseUrl());

        // WHEN the check runs
        BuildResult result = run(true, "checkRootioIgnores");

        // THEN the entry is still recognised
        assertLine(result.getOutput(), "STALE", 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-coordinate",
        "org.foo:bar@",
        "org/evil:bar@1.0",
        "org.foo:bar@1.0/../../x",
        "org.foo:bar@1.0?x=1",
        "org.foo:..@1.0",
        "org.foo:bar@1 .0"
    })
    void malformedLinesAreWarnedAboutAndNeverProbed(String line) throws IOException {
        // GIVEN an entry that is not a strict group:artifact@version
        writeIgnoreFile(line);

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN the line is called out and no request is made
        assertTrue(result.getOutput().contains(".rootioignore:1"), result.getOutput());
        assertTrue(result.getOutput().contains("malformed"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void acceptsUnderscoreAndPlusInCoordinates() throws IOException {
        // GIVEN real-world shaped coordinates (kafka_2.13, build metadata)
        String path = BASE + "/org/apache/kafka/kafka_2.13/3.8.1+build.1/kafka_2.13-3.8.1+build.1";
        publish(path);
        writeIgnoreFile("org.apache.kafka:kafka_2.13@3.8.1+build.1");

        // WHEN the check runs
        run(true, "checkRootioIgnores");

        // THEN the exact Maven path is probed
        assertTrue(requests.contains("HEAD " + path + ".jar"), requests.toString());
    }

    // ── skip conditions ──────────────────────────────────────────────────────

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
        // GIVEN credentials that are defined but null
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        writeBuild(Settings.NULL_CREDENTIALS, baseUrl());

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it skips and makes no requests
        assertTrue(result.getOutput().contains("JFrog credentials"), result.getOutput());
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void skipsWhenTheHostBuildNeverDefinedTheJfrogSettings() throws IOException {
        // GIVEN a host build with no gradle.ext.jfrog* keys at all
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        writeBuild(Settings.NO_EXT_AT_ALL, baseUrl());

        // WHEN the check runs
        BuildResult result = run(false, "checkRootioIgnores");

        // THEN it skips instead of failing on a missing property
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

    // ── wiring ───────────────────────────────────────────────────────────────

    @Test
    void isWiredIntoCheckSoBuildFailsOnStaleEntries() throws IOException {
        // GIVEN a published entry
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the regular check lifecycle task runs
        BuildResult result = run(true, "check");

        // THEN the stale entry fails it
        assertLine(result.getOutput(), "STALE", 1);
    }

    @Test
    void wiresIntoCheckEvenWhenAppliedBeforeTheJavaPlugin() throws IOException {
        // GIVEN the script is applied before the java plugin creates the check task
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);
        Files.writeString(project.resolve("build.gradle"),
                "apply from: '" + scriptPath() + "'\napply plugin: 'java'\n");

        // WHEN the regular check lifecycle task runs
        BuildResult result = run(true, "check");

        // THEN it is still wired in and fails on the stale entry
        assertLine(result.getOutput(), "STALE", 1);
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

    // ── configuration cache ──────────────────────────────────────────────────

    @Test
    void isCompatibleWithTheConfigurationCacheAndReusesIt() throws IOException {
        // GIVEN a still-needed entry (build passes) and the configuration cache enabled
        writeIgnoreFile(ZSTD);

        // WHEN the task runs twice
        BuildResult first = run(false, "checkRootioIgnores", "--configuration-cache");
        BuildResult second = run(false, "checkRootioIgnores", "--configuration-cache");

        // THEN the entry is stored without problems and then reused
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertFalse(first.getOutput().contains("problem"), first.getOutput());
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertLine(second.getOutput(), "still needed", 1);
    }

    @Test
    void stillFailsOnStaleEntriesWhenRunWithTheConfigurationCache() throws IOException {
        // GIVEN a published entry and the configuration cache enabled
        publish(KAFKA_PATH);
        writeIgnoreFile(KAFKA);

        // WHEN the regular check task runs
        BuildResult result = run(true, "check", "--configuration-cache");

        // THEN it fails because of the stale entry, not because of configuration cache problems
        assertLine(result.getOutput(), "STALE", 1);
        assertFalse(result.getOutput().contains("configuration cache problem"), result.getOutput());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void publish(String pathWithoutExtension) {
        statusByPath.put(pathWithoutExtension + ".jar", 200);
        statusByPath.put(pathWithoutExtension + ".pom", 200);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + BASE;
    }

    private void writeIgnoreFile(String... lines) throws IOException {
        Files.write(project.resolve(".rootioignore"), List.of(lines));
        writeBuild(Settings.WITH_CREDENTIALS, baseUrl());
    }

    private void writeBuild(Settings settings, String url) throws IOException {
        String ext = switch (settings) {
            case WITH_CREDENTIALS -> "gradle.ext.jfrogUrl = '" + url + "'\n"
                    + "gradle.ext.jfrogUser = 'user'\ngradle.ext.jfrogToken = '" + TOKEN + "'\n";
            case NULL_CREDENTIALS -> "gradle.ext.jfrogUrl = '" + url + "'\n"
                    + "gradle.ext.jfrogUser = null\ngradle.ext.jfrogToken = null\n";
            case NO_EXT_AT_ALL -> "";
        };
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\n" + ext);
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'java' }\napply from: '" + scriptPath() + "'\n");
    }

    private static Path scriptPath() {
        return Path.of("..", "gradle", "check-rootio-ignores.gradle").toAbsolutePath().normalize();
    }

    private static void assertLine(String output, String state, int ignoreFileLine) {
        Pattern pattern = Pattern.compile("(?m)^.*" + Pattern.quote(state) + "\\s+\\.rootioignore:" + ignoreFileLine + "\\b.*$");
        assertTrue(pattern.matcher(output).find(),
                "expected a '" + state + "' line for .rootioignore:" + ignoreFileLine + " in:\n" + output);
    }

    private BuildResult run(boolean expectFailure, String... args) throws IOException {
        if (!Files.exists(project.resolve("build.gradle"))) {
            writeBuild(Settings.WITH_CREDENTIALS, baseUrl());
        }
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments(args)
                .forwardOutput();
        return expectFailure ? runner.buildAndFail() : runner.build();
    }
}
