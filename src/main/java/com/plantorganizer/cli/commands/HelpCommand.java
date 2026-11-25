package com.plantorganizer.cli.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.plantorganizer.cli.CliException;
import com.plantorganizer.cli.Session;
import com.plantorganizer.cli.Text;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Parameters;

/**
 * /help lists every command, built from the command classes themselves so it
 * never goes out of date; /help plants or /help plants add shows one
 * command's full usage (picocli's generated help).
 */
@Command(name = "/help", separator = " ", description = "List all commands, or show how to use one.")
public class HelpCommand extends CliCommand {

    @Parameters(arity = "0..2", paramLabel = "<command>", description = "e.g. plants, or plants add.")
    List<String> topic = new ArrayList<>();

    HelpCommand(Session session) {
        super(session);
    }

    @Override
    protected void execute() {
        if (topic == null || topic.isEmpty()) {
            overview();
            return;
        }
        String name = topic.get(0).replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        Class<?> command = Commands.find(name);
        if (command == null) {
            throw new CliException("There is no /" + name + " command. Type /help to see all commands.");
        }
        CommandLine cl = session.dispatcher().commandLine(command);
        if (topic.size() == 2) {
            CommandLine sub = cl.getSubcommands().get(topic.get(1).toLowerCase(Locale.ROOT));
            if (sub == null) {
                throw new CliException("/" + name + " has no verb \"" + topic.get(1) + "\". Choose one of: "
                        + String.join(", ", cl.getSubcommands().keySet()) + ".");
            }
            cl = sub;
        }
        cl.usage(session.console().writer());
        session.console().writer().flush();
    }

    private void overview() {
        print("Plant Organizer agent - commands (details: /help <command>, e.g. /help plants add)");
        for (Class<?> command : Commands.all().values()) {
            CommandSpec spec = session.dispatcher().commandLine(command).getCommandSpec();
            print("");
            print(spec.name() + ": " + first(spec.usageMessage().description()));
            List<String[]> rows = new ArrayList<>();
            if (spec.subcommands().isEmpty() || !spec.positionalParameters().isEmpty()) {
                rows.add(new String[] {compact(spec), ""});
            }
            spec.subcommands().values().forEach(sub -> rows.add(new String[] {compact(sub.getCommandSpec()),
                    first(sub.getCommandSpec().usageMessage().description())}));
            print(Text.table(rows, "  "));
        }
        print("");
        print("""
                Rules:
                  - Every command starts with /. Anything else is not a command.
                  - Put values with spaces in quotes: /plants add "Monstera deliciosa".
                  - IDs: P = your plant (P3), W = wishlist item (W4), R = recommendation (R7).
                    Names work too: /plants water "Monty".
                  - Adding or deleting something asks you to confirm first.
                  - /recommend keep keeps a plant on your recommendations (the agent learns you like it).
                    /wishlist add puts a plant on your wishlist. They are separate lists.
                  - /chat answers questions but never changes your data. If you ask it for a change,
                    it suggests the command and runs it only if you confirm.""");
    }

    /** "/plants note <plant> <note>", with "[options]" when it has optional flags. */
    private static String compact(CommandSpec spec) {
        StringBuilder sb = new StringBuilder(spec.qualifiedName());
        for (PositionalParamSpec p : spec.positionalParameters()) {
            String label = p.paramLabel() + (p.arity().isVariable() ? "..." : "");
            sb.append(' ').append(p.arity().min() == 0 ? "[" + label + "]" : label);
        }
        boolean options = spec.options().stream().anyMatch((OptionSpec o) -> !o.usageHelp() && !o.versionHelp());
        return sb.append(options ? " [options]" : "").toString();
    }

    private static String first(String[] description) {
        if (description == null || description.length == 0) {
            return "";
        }
        // the first sentence: up to a full stop followed by a capital letter (so "e.g. /plants" doesn't end it)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.\\s+(?=[A-Z])").matcher(description[0]);
        return m.find() ? description[0].substring(0, m.start() + 1) : description[0];
    }
}
