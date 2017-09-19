package org.wikimedia.cassandra.metrics.service;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

public class WatchFile implements Runnable {

    public static enum EventType {
        CREATE, DELETE;
    }

    public static interface Task {
        void execute(EventType type) throws Exception;
    }

    private final File watched;
    private final Task task;

    public WatchFile(File watched, Task task) {
        this.watched = watched;
        this.task = task;
    }

    @Override
    public void run() {
        try {
            WatchService watcher = java.nio.file.FileSystems.getDefault().newWatchService();
            Path dir = Paths.get(this.watched.getParent());
            dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE);
            WatchKey key;
            while (true) {
                try {
                    key = watcher.take();
                }
                catch (InterruptedException e) {
                    return;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();

                    if (fileName.toString().equals(this.watched.getName())) {
                        if (kind == ENTRY_CREATE) {
                            this.task.execute(EventType.CREATE);
                        }
                        else if (kind == ENTRY_DELETE) {
                            this.task.execute(EventType.DELETE);
                        }
                        else {
                            throw new RuntimeException("Captured event of unexpected type!");
                        }
                    }
                }
                if (!key.reset())
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return;
        }
    }
}