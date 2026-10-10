package com.writingagent.v2.agent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Keeps the diagnostic snapshot while publishing each observable event as it happens. */
public final class LiveTraceList<T> extends CopyOnWriteArrayList<T> {
    private final Consumer<T> observer;

    public LiveTraceList(Consumer<T> observer) {
        this.observer = observer;
    }

    @Override
    public boolean add(T event) {
        boolean added = super.add(event);
        observer.accept(event);
        return added;
    }
}
