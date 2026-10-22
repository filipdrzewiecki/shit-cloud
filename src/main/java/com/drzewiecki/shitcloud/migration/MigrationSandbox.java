package com.drzewiecki.shitcloud.migration;

import jakarta.annotation.PreDestroy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the model's transform, {@code function migrate(doc, collection)}, over documents. The code is untrusted (the
 * model can be steered by what it reads), so it runs in GraalJS under the {@code CONSTRAINED} sandbox: no Java, no
 * files, no network, no threads. Documents cross as JSON text only, so no host object ever reaches the script.
 *
 * <p>Each document may execute at most {@code migration.statement-limit} statements; a run over all documents is
 * cancelled after {@code migration.timeout}. GraalJS Community has no heap limit: input size (one document) and the
 * statement limit are what bound it.
 */
@Component
public class MigrationSandbox {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** Calls the transform with a JSON text and returns its result as JSON text, or null to remove the document. */
    private static final String CALL = """
            (function (text, collection) {
              const result = migrate(JSON.parse(text), collection);
              if (result === null) return null;
              if (typeof result !== 'object' || Array.isArray(result)) {
                throw new TypeError('migrate must return an object or null, not '
                    + (Array.isArray(result) ? 'an array' : typeof result));
              }
              return JSON.stringify(result);
            })""";

    private final MigrationProperties properties;
    private final JsonMapper json;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "migration-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private Engine engine;

    public MigrationSandbox(MigrationProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /**
     * Compiles the transform and starts the run's clock.
     *
     * @throws MigrationException when the code does not compile or defines no {@code migrate} function
     */
    public Run open(String code) {
        return new Run(code, properties.timeout());
    }

    /** One document's fate. */
    public sealed interface Outcome {
    }

    /** The document's new fields, before the data rules are applied. */
    public record Converted(Map<String, Object> data) implements Outcome {
    }

    /** {@code migrate} returned null. */
    public record Removed() implements Outcome {
    }

    public record Failed(String error) implements Outcome {
    }

    /**
     * A run over many documents with one compiled transform. Not thread-safe: one caller at a time.
     */
    public final class Run implements AutoCloseable {

        private final String code;
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final ScheduledFuture<?> deadline;
        private volatile Context context;
        private Value call;

        private Run(String code, Duration timeout) {
            this.code = code;
            start();
            this.deadline = watchdog.schedule(() -> {
                timedOut.set(true);
                Context running = context;
                if (running != null) {
                    running.close(true);
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * @param doc the document as apps see it: its fields plus {@code id}, {@code _version}, {@code _updatedBy},
         *            {@code _updatedAt}
         * @throws MigrationException when the run is out of time; the whole run has failed then
         */
        public Outcome migrate(Map<String, Object> doc, String collection) {
            if (timedOut.get()) {
                throw outOfTime();
            }
            try {
                context.resetLimits();
                Value result = call.execute(json.writeValueAsString(doc), collection);
                if (result.isNull()) {
                    return new Removed();
                }
                return new Converted(json.readValue(result.asString(), MAP));
            } catch (PolyglotException e) {
                if (timedOut.get() || e.isCancelled()) {
                    throw outOfTime();
                }
                if (e.isResourceExhausted()) {
                    // The context cannot be used after hitting a limit; the next document gets a fresh one.
                    restart();
                    return new Failed("ran more than " + properties.statementLimit() + " statements (endless loop?)");
                }
                if (!e.isGuestException()) {
                    throw e;
                }
                return new Failed(describe(e));
            } catch (JacksonException e) {
                return new Failed("the result is not JSON: " + e.getOriginalMessage());
            } catch (IllegalStateException e) {
                // The watchdog closed the context between two documents.
                if (timedOut.get()) {
                    throw outOfTime();
                }
                throw e;
            }
        }

        @Override
        public void close() {
            deadline.cancel(false);
            closeContext();
        }

        private void start() {
            context = Context.newBuilder("js")
                    .engine(engine())
                    .sandbox(SandboxPolicy.CONSTRAINED)
                    .allowHostAccess(HostAccess.CONSTRAINED)
                    .allowHostClassLookup(name -> false)
                    .allowIO(IOAccess.NONE)
                    .allowCreateThread(false)
                    .allowNativeAccess(false)
                    .out(OutputStream.nullOutputStream())
                    .err(OutputStream.nullOutputStream())
                    .resourceLimits(ResourceLimits.newBuilder().statementLimit(properties.statementLimit(), null).build())
                    .build();
            try {
                context.eval("js", "delete globalThis.load; delete globalThis.loadWithNewGlobal;");
                context.eval("js", code);
                if (!context.eval("js", "typeof migrate === 'function'").asBoolean()) {
                    throw new MigrationException("The code must define function migrate(doc, collection)");
                }
                call = context.eval("js", CALL);
                context.resetLimits();
            } catch (PolyglotException e) {
                closeContext();
                if (timedOut.get() || e.isCancelled()) {
                    throw outOfTime();
                }
                if (e.isResourceExhausted()) {
                    throw new MigrationException("The code ran more than " + properties.statementLimit()
                            + " statements before migrate was even called");
                }
                throw new MigrationException("The code does not run: " + describe(e));
            } catch (MigrationException e) {
                closeContext();
                throw e;
            }
        }

        private void restart() {
            closeContext();
            start();
        }

        private void closeContext() {
            Context closing = context;
            context = null;
            if (closing != null) {
                try {
                    closing.close(true);
                } catch (PolyglotException ignored) {
                    // A context stopped by a limit or the watchdog reports that again when closed.
                }
            }
        }

        private MigrationException outOfTime() {
            return new MigrationException("The migration took longer than " + properties.timeout().toSeconds()
                    + " s and was stopped");
        }
    }

    /** The script's error, e.g. "TypeError: Cannot read properties of undefined (reading 'x') (line 3)". */
    private static String describe(PolyglotException e) {
        SourceSection location = e.getSourceLocation();
        String message = e.getMessage();
        return location == null || !location.isAvailable() ? message : message + " (line " + location.getStartLine() + ")";
    }

    private synchronized Engine engine() {
        if (engine == null) {
            // Built on first use: most API instances never run a migration. Without the Graal JIT GraalJS runs
            // interpreted, which is fast enough for a transform and not worth a warning on every start.
            engine = Engine.newBuilder("js")
                    .sandbox(SandboxPolicy.CONSTRAINED)
                    .option("engine.WarnInterpreterOnly", "false")
                    .out(OutputStream.nullOutputStream())
                    .err(OutputStream.nullOutputStream())
                    .build();
        }
        return engine;
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        synchronized (this) {
            if (engine != null) {
                engine.close(true);
            }
        }
    }
}
