package com.paicli.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainStartupCommandTest {
    @Test
    void helpAndVersionAreHandledBeforeCredentialDependentStartup() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output);

        assertTrue(Main.handleInformationalCommand(new String[]{"--help"}, stream));
        assertTrue(Main.handleInformationalCommand(new String[]{"--version"}, stream));
        assertFalse(Main.handleInformationalCommand(new String[]{"serve", "--http"}, stream));

        String rendered = output.toString();
        assertTrue(rendered.contains("PaiCLI v16.1.1"));
        assertTrue(rendered.contains("Usage:"));
        assertTrue(rendered.contains("serve --http"));
    }
}
