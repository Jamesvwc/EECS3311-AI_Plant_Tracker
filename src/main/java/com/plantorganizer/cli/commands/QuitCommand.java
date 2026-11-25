package com.plantorganizer.cli.commands;

import com.plantorganizer.cli.Session;

import picocli.CommandLine.Command;

/** Leaves the CLI. The server keeps running; your data is already saved there. */
@Command(name = "/quit", description = "Leave the agent (the server keeps running).")
public class QuitCommand extends CliCommand {

    QuitCommand(Session session) {
        super(session);
    }

    @Override
    protected void execute() {
        session.requestQuit();
    }
}
