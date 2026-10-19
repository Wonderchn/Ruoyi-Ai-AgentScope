package com.nageoffer.ai.ragent.coding.tool;

import java.util.List;

/** Raw result of one sandboxed command. */
public record CommandExecutionResult(String executable, List<String> argv, int exitCode,
                                     String stdout, String stderr, boolean timedOut) {

    public CommandExecutionResult {
        argv = argv == null ? List.of() : List.copyOf(argv);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
