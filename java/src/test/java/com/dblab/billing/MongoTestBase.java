package com.dblab.billing;

import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The real MongoDB every MongoDB test runs against (Stage 6, lesson A8), beside the PostgreSQL of
 * {@link PostgresTestBase}, because the program uses both.
 *
 * <p>A plain container of the mongo:8.2 image, started as a one-member replica set: transactions and retryable
 * writes need one. (Testcontainers 2 also has a MongoDBContainer class, but it no longer starts a replica set by
 * itself; doing it here keeps every step visible, the same steps as the Python tests.) Stage 5's
 * mongo/practice/51-load-sample.js is then run inside it with mongosh, so the tests see exactly the lab's
 * collections, validators and indexes: the MongoDB counterpart of replaying Flyway's migrations.
 */
public abstract class MongoTestBase extends PostgresTestBase {

    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:8.2")
            .withCommand("mongod", "--replSet", "rs0", "--bind_ip_all")
            .withExposedPorts(27017);

    static {
        MONGO.start();
        try {
            // Make it a replica set of one member, then wait until that member is the writable primary.
            mongosh("rs.initiate({ _id: 'rs0', members: [{ _id: 0, host: 'localhost:27017' }] })");
            for (int i = 0; i < 120 && !mongosh("db.hello().isWritablePrimary").getStdout().contains("true"); i++) {
                Thread.sleep(500);
            }
            MONGO.copyFileToContainer(MountableFile.forHostPath("../mongo/practice/51-load-sample.js"),
                    "/tmp/51-load-sample.js");
            ExecResult loaded = MONGO.execInContainer("mongosh", "--quiet",
                    "mongodb://localhost:27017/utility_lab?directConnection=true", "-f", "/tmp/51-load-sample.js");
            if (loaded.getExitCode() != 0) {
                throw new IllegalStateException("51-load-sample.js failed: " + loaded.getStdout() + loaded.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not prepare the test MongoDB", e);
        }
    }

    /** Runs one mongosh command inside the container and returns what it printed. */
    private static ExecResult mongosh(String command) throws IOException, InterruptedException {
        return MONGO.execInContainer("mongosh", "--quiet", "mongodb://localhost:27017/?directConnection=true",
                "--eval", command);
    }

    /** For tests only: the MongoDB address replaces MONGO_URI's. Spring Boot 4 reads it from spring.mongodb.uri. */
    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        // 127.0.0.1 rather than localhost, as in PostgresTestBase. directConnection: talk to this one member,
        // whose replica-set name for itself (localhost:27017) is only valid inside the container.
        String host = "localhost".equals(MONGO.getHost()) ? "127.0.0.1" : MONGO.getHost();
        registry.add("spring.mongodb.uri",
                () -> "mongodb://" + host + ":" + MONGO.getMappedPort(27017) + "/utility_lab?directConnection=true");
    }
}