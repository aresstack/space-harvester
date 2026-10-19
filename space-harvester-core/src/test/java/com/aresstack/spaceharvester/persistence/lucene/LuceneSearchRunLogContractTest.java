package com.aresstack.spaceharvester.persistence.lucene;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLogContract;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Protokollvertrag gegen den Lucene-Index auf einem temporären Verzeichnis. */
class LuceneSearchRunLogContractTest extends SearchRunLogContract {

    @TempDir
    Path base;

    private final List<LuceneSearchRunLog> opened = new ArrayList<>();
    private final Map<LuceneSearchRunLog, Object[]> settings = new HashMap<>();

    @Override
    protected SearchRunLog newLog(Duration maxAge, Clock clock) {
        LuceneSearchRunLog log = LuceneSearchRunLog.open(base.resolve("runs-" + opened.size()), maxAge, clock);
        settings.put(log, new Object[] {maxAge, clock});
        return track(log);
    }

    @Override
    protected SearchRunLog reopen(SearchRunLog log) {
        LuceneSearchRunLog lucene = (LuceneSearchRunLog) log;
        Object[] previous = settings.get(lucene);
        lucene.close();
        return track(LuceneSearchRunLog.open(lucene.path(), (Duration) previous[0], (Clock) previous[1]));
    }

    private LuceneSearchRunLog track(LuceneSearchRunLog log) {
        opened.add(log);
        return log;
    }

    @Test
    void aSecondProcessCannotOpenTheSameLog() {
        LuceneSearchRunLog first = track(LuceneSearchRunLog.open(base.resolve("locked")));

        assertThatThrownBy(() -> LuceneSearchRunLog.open(first.path())).isInstanceOf(PageCacheException.class);
    }

    @AfterEach
    void closeAll() {
        opened.forEach(LuceneSearchRunLog::close);
    }
}
