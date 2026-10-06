package com.boshys.bteutils.console;

import com.boshys.bteutils.BoshysBTEUtils;
import com.boshys.bteutils.config.BoshysBTEUtilsConfig;
import net.minecraft.client.Minecraft;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Detects teleport messages from console/log output.
 *
 * Uses TWO mechanisms:
 * 1. Log4j2 appender - intercepts ALL Minecraft log output (including chat plugin messages)
 * 2. System.out interception - fallback for direct stdout writes
 *
 * When a configured pattern is found, triggers the same movement-based teleport
 * detection that the TPLL keybind uses.
 */
public class ConsoleMessageDetector {

    private static ConsoleMessageDetector INSTANCE;
    private final ConsoleMessageConfig config;

    /** The original System.out stream */
    private final PrintStream originalOut;
    /** The original System.err stream */
    private final PrintStream originalErr;

    /** Prevent duplicate triggers from the same log output burst */
    private long lastTriggerTime = 0;
    private static final long TRIGGER_COOLDOWN_MS = 500;

    public ConsoleMessageDetector(ConsoleMessageConfig config) {
        this.config = config;
        this.originalOut = System.out;
        this.originalErr = System.err;
        INSTANCE = this;
    }

    /**
     * Installs the interceptor on System.out and System.err.
     * Also installs the Log4j2 appender.
     * Call this during mod initialization.
     */
    public void install() {
        // Install System.out/err interception (fallback)
        InterceptingPrintStream interceptingOut = new InterceptingPrintStream(originalOut, this);
        InterceptingPrintStream interceptingErr = new InterceptingPrintStream(originalErr, this);
        System.setOut(interceptingOut);
        System.setErr(interceptingErr);

        // Install Log4j2 appender (primary detection method)
        installLog4jAppender();

        originalOut.println("[Boshys-bt-utils] ConsoleMessageDetector installed!");
        originalOut.println("[Boshys-bt-utils] Monitoring for patterns: " + config.getPatterns());
    }

    /**
     * Installs a Log4j2 appender to intercept all Minecraft log messages.
     * This catches messages that bypass System.out (which is most of them in 1.21.10).
     */
    private void installLog4jAppender() {
        try {
            org.apache.logging.log4j.core.LoggerContext ctx =
                    (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();

            org.apache.logging.log4j.core.appender.AbstractAppender appender =
                    new org.apache.logging.log4j.core.appender.AbstractAppender(
                            "BoshysBTEUtilsConsoleDetector",
                            null,
                            null,
                            false
                    ) {
                        @Override
                        public void append(org.apache.logging.log4j.core.LogEvent event) {
                            if (event.getMessage() != null) {
                                checkLine(event.getMessage().getFormattedMessage());
                            }
                        }
                    };

            appender.start();
            config.addAppender(appender);

            // Attach to the root logger so we catch everything
            org.apache.logging.log4j.core.config.LoggerConfig rootConfig = config.getRootLogger();
            rootConfig.addAppender(appender, org.apache.logging.log4j.Level.ALL, null);
            ctx.updateLoggers();

            originalOut.println("[Boshys-bt-utils] Log4j2 appender installed successfully");
        } catch (Exception e) {
            originalOut.println("[Boshys-bt-utils] Failed to install Log4j2 appender: " + e.getMessage());
            originalOut.println("[Boshys-bt-utils] Falling back to System.out interception only");
        }
    }

    /**
     * Checks a line of text against all configured patterns.
     * Called from both the Log4j2 appender and System.out interceptor.
     */
    void checkLine(String line) {
        if (BoshysBTEUtils.INSTANCE == null) return;
        if (!BoshysBTEUtils.getConfig().enableMarkers) return;
        if (BoshysBTEUtils.markersHidden) return;

        if (line == null || line.isEmpty()) return;

        // Check all configured patterns
        for (String pattern : config.getPatterns()) {
            if (line.contains(pattern)) {
                triggerTeleportDetection(pattern);
                break; // Only trigger once per line
            }
        }
    }

    /**
     * Triggers the same movement-based teleport detection that the keybind uses.
     * This saves the current player position and sets waitingForTeleport = true,
     * so handleTpllTeleportDetection() will place the marker when the player actually moves.
     */
    private void triggerTeleportDetection(String matchedPattern) {
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
            return; // Too soon, likely duplicate
        }
        lastTriggerTime = now;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        // Check TPLL marker mode - only trigger if manual mode is enabled
        BoshysBTEUtilsConfig.TpllMarkerMode mode = BoshysBTEUtils.getConfig().tpllMarkerMode;
        if (mode == BoshysBTEUtilsConfig.TpllMarkerMode.DISABLED ||
                mode == BoshysBTEUtilsConfig.TpllMarkerMode.KEYBIND_ONLY) {
            return;
        }

        originalOut.println("[Boshys-bt-utils] Manual TPLL message detected! Pattern: '" + matchedPattern + "'");

        // Trigger the SAME movement-based detection that the keybind uses
        BoshysBTEUtils.INSTANCE.triggerConsoleTeleportDetection(client);
    }

    /**
     * Legacy no-op method. Previously processed a queue of pending markers,
     * but now detection is synchronous via triggerConsoleTeleportDetection.
     * Kept for backward compatibility with BoshysBTEUtils tick loop.
     */
    public void processPendingMarkers(Minecraft client) {
        // Detection is now handled synchronously in triggerTeleportDetection.
        // This method remains so BoshysBTEUtils.tick() doesn't need changes.
    }

    /**
     * Gets the singleton instance.
     */
    public static ConsoleMessageDetector getInstance() {
        return INSTANCE;
    }

    /**
     * A PrintStream that intercepts all output and checks it for patterns,
     * then forwards to the original stream.
     */
    private static class InterceptingPrintStream extends PrintStream {
        private final ConsoleMessageDetector detector;
        private final StringBuilder lineBuffer = new StringBuilder();

        public InterceptingPrintStream(OutputStream out, ConsoleMessageDetector detector) {
            super(out, true);
            this.detector = detector;
        }

        @Override
        public void write(int b) {
            super.write(b);
            char c = (char) b;
            if (c == '\n' || c == '\r') {
                flushLineBuffer();
            } else {
                lineBuffer.append(c);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            for (int i = off; i < off + len; i++) {
                char c = (char) buf[i];
                if (c == '\n' || c == '\r') {
                    flushLineBuffer();
                } else {
                    lineBuffer.append(c);
                }
            }
        }

        private void flushLineBuffer() {
            if (lineBuffer.length() > 0) {
                String line = lineBuffer.toString();
                detector.checkLine(line);
                lineBuffer.setLength(0);
            }
        }
    }
}