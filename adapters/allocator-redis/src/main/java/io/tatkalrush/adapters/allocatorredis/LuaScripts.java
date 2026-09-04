package io.tatkalrush.adapters.allocatorredis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads the Lua scripts and runs them by digest.
 *
 * <p><b>Why EVALSHA rather than EVAL.</b> {@code allocate.lua} is roughly five
 * kilobytes. Sending it on every attempt during a spike would put megabytes per
 * second of script text on the wire for no reason, and inflate the very
 * measurement §9.4 exists to take — Strategy A would look slower than it is, and
 * the comparison would be measuring the transport rather than the strategy.
 * EVALSHA sends a 40-character digest instead.
 *
 * <p><b>Why the NOSCRIPT fallback is mandatory.</b> Redis's script cache is not
 * durable. It is emptied by {@code SCRIPT FLUSH}, by a restart, and by chaos
 * scenario C2's {@code FLUSHALL}. A cached digest is therefore a hint, never a
 * guarantee, and an implementation that assumed otherwise would work perfectly
 * until the first chaos run and then fail every allocation.
 */
final class LuaScripts {

    private final RedisCommands<String, String> redis;
    private final ConcurrentHashMap<String, String> digests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sources = new ConcurrentHashMap<>();

    /** Counts reloads after a NOSCRIPT. Non-zero after a chaos run is expected. */
    private final AtomicLong reloads = new AtomicLong();

    LuaScripts(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    long reloadCount() {
        return reloads.get();
    }

    @SuppressWarnings("unchecked")
    <T> T run(String name, ScriptOutputType outputType, String[] keys, String... args) {
        String digest = digests.computeIfAbsent(name, this::load);
        try {
            return (T) redis.evalsha(digest, outputType, keys, args);
        } catch (RedisNoScriptException e) {
            // The cache was flushed underneath us. Reload and retry once; a
            // second NOSCRIPT would mean something is wrong beyond a flush, and
            // retrying forever would turn that into a hot loop.
            reloads.incrementAndGet();
            digests.remove(name);
            String reloaded = digests.computeIfAbsent(name, this::load);
            return (T) redis.evalsha(reloaded, outputType, keys, args);
        }
    }

    private String load(String name) {
        return redis.scriptLoad(sources.computeIfAbsent(name, LuaScripts::read));
    }

    private static String read(String name) {
        String resource = "/lua/" + name + ".lua";
        try (var in = LuaScripts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing Lua script on the classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
