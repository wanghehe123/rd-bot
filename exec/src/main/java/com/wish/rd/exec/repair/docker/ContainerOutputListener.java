package com.wish.rd.exec.repair.docker;

/** Receives bounded chunks from a running container without a control channel. */
public interface ContainerOutputListener {

    void onStdout(String chunk);

    default void onStderr(String chunk) {
        // Stderr is diagnostic-only for the Pi protocol; callers may ignore it.
    }

    static ContainerOutputListener noop() {
        return new ContainerOutputListener() {
            @Override
            public void onStdout(String chunk) {
            }
        };
    }
}
