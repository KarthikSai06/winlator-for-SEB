package com.winlator.compat;

import android.util.Log;

import com.winlator.core.EnvVars;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ChromiumCompatHelper — Phase 3 compatibility layer.
 *
 * Chromium-based applications (Chrome, Edge, Electron, WebView2, SEB's browser core)
 * rely on a multi-process sandbox model that is not compatible with the proot/Wine
 * environment inside Winlator. This helper detects those executables and:
 *
 * 1. Injects --no-sandbox and other required Chromium CLI flags.
 * 2. Adds required environment variables (e.g., DISPLAY, GPU flags).
 * 3. Disables GPU compositing inside Chromium if Vulkan is unavailable.
 */
public abstract class ChromiumCompatHelper {

    private static final String TAG = "ChromiumCompat";

    /**
     * Known Chromium-based executable names (lowercased).
     * Electron applications embed their own copy of Chromium and commonly
     * have application-specific names, but their helper processes are named
     * identically to the main EXE. We match by suffix for Electron apps.
     */
    private static final Set<String> CHROMIUM_EXECUTABLES = new HashSet<>(Arrays.asList(
            // Generic Chromium executables
            "chrome.exe",
            "chromium.exe",
            // Microsoft Edge
            "msedge.exe",
            "msedgewebview2.exe",
            // Safe Exam Browser (Chromium-based)
            "safeexambrowser.exe",
            "sebwindowsbrowser.exe",
            // Electron shell
            "electron.exe",
            // Common Electron apps that launch a chromium core
            "code.exe",          // Visual Studio Code
            "notion.exe",
            "discord.exe",
            "slack.exe",
            "teams.exe",
            "figma.exe",
            "obsidian.exe"
    ));

    /**
     * Suffixes that identify Electron helper / GPU / renderer subprocesses.
     */
    private static final String[] ELECTRON_HELPER_SUFFIXES = {
            " helper.exe",
            " helper (gpu).exe",
            " helper (renderer).exe",
            " helper (plugin).exe",
    };

    /**
     * Determines if the given executable path/name belongs to a Chromium-based app.
     *
     * @param executableName The executable filename (e.g., "SEB.exe" or full path).
     * @return true if this is a Chromium or Electron-based executable.
     */
    public static boolean isChromiumBased(String executableName) {
        if (executableName == null || executableName.isEmpty()) return false;
        String lower = executableName.toLowerCase(Locale.ENGLISH);

        // Extract just the filename in case it's a full path
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        if (slash >= 0) lower = lower.substring(slash + 1);

        // Direct match
        if (CHROMIUM_EXECUTABLES.contains(lower)) return true;

        // Electron helper subprocess pattern
        for (String suffix : ELECTRON_HELPER_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }

        return false;
    }

    /**
     * Injects Chromium-specific compatibility arguments into the Wine command line.
     *
     * These flags are required because:
     * - --no-sandbox: disables Chromium's multi-layer process sandbox which
     *   requires kernel namespaces not available inside proot.
     * - --disable-gpu-sandbox: specifically disables the GPU process sandbox.
     * - --in-process-gpu: runs the GPU compositor in the browser process, avoiding
     *   the spawning of a separate GPU helper process.
     * - --disable-software-rasterizer: prevents software Mesa fallback which is
     *   extremely slow on ARM.
     * - --disable-dev-shm-usage: /dev/shm is limited in Android, so Chrome should
     *   use /tmp instead.
     *
     * @param command  The command string to append flags to.
     * @param envVars  The environment variable map to add GPU-related settings.
     * @return The modified command string with injected flags.
     */
    public static String applyChromiumFlags(String command, EnvVars envVars) {
        Log.d(TAG, "Applying Chromium/Electron compatibility flags to: " + command);

        StringBuilder flags = new StringBuilder();

        // ── Sandbox removal (required in proot/Wine environment) ─────────────────
        flags.append(" --no-sandbox");
        flags.append(" --disable-gpu-sandbox");
        flags.append(" --no-zygote");

        // ── Renderer process model ────────────────────────────────────────────────
        // Run GPU and renderer in a single process to reduce subprocess spawning overhead
        flags.append(" --in-process-gpu");
        flags.append(" --disable-renderer-backgrounding");

        // ── Shared memory handling (Android /dev/shm is very limited) ─────────────
        flags.append(" --disable-dev-shm-usage");

        // ── GPU/Graphics backend ──────────────────────────────────────────────────
        // Force Vulkan/Angle backend for best compatibility with DXVK
        flags.append(" --use-angle=vulkan");
        flags.append(" --enable-features=Vulkan");

        // ── Stability improvements ────────────────────────────────────────────────
        flags.append(" --disable-background-networking");
        flags.append(" --disable-client-side-phishing-detection");
        flags.append(" --disable-breakpad");

        // ── Environment variables for GPU/OpenGL compatibility ────────────────────
        if (envVars != null) {
            // Tell Chromium's ANGLE layer to use system Vulkan
            envVars.put("ANGLE_DEFAULT_PLATFORM", "vulkan");
            // Avoid WGL pixel-format negotiation failures in Wine
            envVars.put("CHROMIUM_FLAGS", flags.toString().trim());
        }

        return command + flags;
    }

    /**
     * Applies SEB-specific Chromium startup flags.
     * SEB (Safe Exam Browser) additionally needs:
     * - Low-latency audio: SEB may use WebAudio for exam content.
     * - Permissive policy overrides (for controlled exam environments).
     *
     * @param command The SEB launch command.
     * @param envVars Environment variable map.
     * @return Modified SEB command string.
     */
    public static String applySEBChromiumFlags(String command, EnvVars envVars) {
        // First apply standard Chromium flags
        String result = applyChromiumFlags(command, envVars);

        // Additional SEB-specific flags
        result += " --allow-running-insecure-content";
        result += " --disable-web-security";
        result += " --ignore-certificate-errors-spki-list";

        if (envVars != null) {
            envVars.put("SEB_COMPAT_MODE", "1");
        }

        return result;
    }
}
