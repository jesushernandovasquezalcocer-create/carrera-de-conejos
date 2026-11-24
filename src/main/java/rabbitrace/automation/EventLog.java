package rabbitrace.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSONL event log: one object per line, {@code seq}
 * monotonic from 1, every record carrying {@code rngCursor}; {@code wallMs} is the
 * only field a diff may ignore.
 */
public final class EventLog implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Writer writer;
    private long seq;

    private EventLog(Writer writer) {
        this.writer = writer;
    }

    public static EventLog open(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return new EventLog(null);
        }
        Path file = Path.of(path).toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Append; each run gets a fresh path.
        Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return new EventLog(writer);
    }

    public long seq() {
        return seq;
    }

    public void event(String name, long rngCursor, Map<String, Object> fields) {
        if (writer == null) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("seq", ++seq);
        record.put("event", name);
        record.put("rngCursor", rngCursor);
        if (fields != null) {
            record.putAll(fields);
        }
        record.put("wallMs", System.currentTimeMillis());
        try {
            writer.write(GSON.toJson(record));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            System.err.println("automation log: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("automation log close: " + e.getMessage());
        }
    }
}
