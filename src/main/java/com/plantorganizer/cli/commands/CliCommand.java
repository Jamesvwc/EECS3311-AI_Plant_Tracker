package com.plantorganizer.cli.commands;

import java.util.concurrent.Callable;

import com.plantorganizer.cli.AgentClient;
import com.plantorganizer.cli.Resolver;
import com.plantorganizer.cli.Session;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Base of every runnable command (the verb in {@code /noun verb ...}).
 * picocli fills in the annotated fields from the typed line, then calls
 * {@link #execute()}, which talks to the server through {@link #api()} and
 * prints through {@link #print}. Problems are thrown as CliException and
 * printed by the dispatcher.
 */
public abstract class CliCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show how to use this command.")
    boolean helpRequested;

    @Spec
    protected CommandSpec spec;

    protected final Session session;

    protected CliCommand(Session session) {
        this.session = session;
    }

    @Override
    public final Integer call() {
        execute();
        return 0;
    }

    protected abstract void execute();

    protected AgentClient api() {
        return session.api();
    }

    protected Resolver find() {
        return session.find();
    }

    protected void print(String text) {
        session.console().print(text);
    }

    /** A "working on it" line before a slow (AI or web) step. */
    protected void status(String text) {
        session.console().status(text);
    }

    /** Asks before an addition or deletion; prints "Cancelled." on no. */
    protected boolean confirm(String question) {
        boolean yes = session.confirm(question);
        if (!yes) {
            print("Cancelled.");
        }
        return yes;
    }
}
