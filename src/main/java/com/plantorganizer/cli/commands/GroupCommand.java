package com.plantorganizer.cli.commands;

import com.plantorganizer.cli.UsageException;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Base of a noun ({@code /plants}, {@code /wishlist}...): it only groups its
 * verbs. Typed on its own it explains which verbs exist.
 */
public abstract class GroupCommand implements Runnable {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show how to use this command.")
    boolean helpRequested;

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        throw new UsageException(spec.commandLine(), spec.qualifiedName() + " needs a verb. Choose one of: "
                + String.join(", ", spec.subcommands().keySet()) + ".");
    }
}
