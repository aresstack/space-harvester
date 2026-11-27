package com.aresstack.askai.research.runtime.inference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;

/**
 * The filesystem watcher fires the change signal when the WATCHED descriptor file is written, and ignores an
 * unrelated file. Best-effort timing (the JDK WatchService can lag, especially on Windows): the watched file
 * is rewritten in a loop until the signal arrives or a generous timeout elapses.
 */
public class ModelDescriptorWatcherTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void signalsOnAChangeToTheWatchedDescriptor() throws Exception {
        File dir = folder.newFolder("session");
        File descriptor = new File(dir, "inference-config.json");
        Files.write(descriptor.toPath(), "{}".getBytes(UTF_8));

        final CountDownLatch fired = new CountDownLatch(1);
        ModelDescriptorWatcher watcher = ModelDescriptorWatcher.start(dir.toPath(),
                Collections.singleton("inference-config.json"), new Runnable() {
                    public void run() {
                        fired.countDown();
                    }
                });
        try {
            boolean signalled = false;
            for (int i = 0; i < 50 && !signalled; i++) {
                Files.write(descriptor.toPath(), ("{\"rev\":" + i + "}").getBytes(UTF_8));
                signalled = fired.await(200, TimeUnit.MILLISECONDS);
            }
            assertTrue("the watcher must signal a change to the watched descriptor", signalled);
        } finally {
            watcher.close();
        }
    }

    @Test
    public void ignoresChangesToUnrelatedFiles() throws Exception {
        File dir = folder.newFolder("session");
        final AtomicInteger signals = new AtomicInteger();
        ModelDescriptorWatcher watcher = ModelDescriptorWatcher.start(dir.toPath(),
                Collections.singleton("inference-config.json"), new Runnable() {
                    public void run() {
                        signals.incrementAndGet();
                    }
                });
        try {
            File unrelated = new File(dir, "something-else.txt");
            for (int i = 0; i < 10; i++) {
                Files.write(unrelated.toPath(), ("x" + i).getBytes(UTF_8));
                Thread.sleep(50);
            }
            // Give the watch thread a moment to (not) deliver anything.
            Thread.sleep(300);
            assertTrue("an unrelated file must not signal an inference reload", signals.get() == 0);
        } finally {
            watcher.close();
        }
    }
}
