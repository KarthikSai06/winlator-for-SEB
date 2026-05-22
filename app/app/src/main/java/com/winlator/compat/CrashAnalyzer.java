package com.winlator.compat;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CrashAnalyzer — Phase 6: Runtime Log Analysis Tool.
 *
 * Parses Wine / Box64 log output in real time to detect common failure patterns
 * and produce actionable diagnostic reports. Outputs structured CrashReport
 * objects that can be surfaced in the DiagnosticOverlay or exported to a file.
 */
public class CrashAnalyzer {

    private static final String TAG = "CrashAnalyzer";

    // ── Regex patterns for common failure modes ───────────────────────────────
    private static final Pattern DLL_NOT_FOUND    = Pattern.compile("could not load module '([^']+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern DLL_MISSING_WINE = Pattern.compile("err:module:import_dll Loading library ([\\w.]+) .* failed", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOX64_MISSING    = Pattern.compile("Cannot find library ([\\w.]+) in path", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINE_CRASH       = Pattern.compile("Unhandled exception.*in thread", Pattern.CASE_INSENSITIVE);
    private static final Pattern VK_ERROR         = Pattern.compile("vkResult:\\s*(-?\\d+|VK_ERROR_[A-Z_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DXVK_NOT_FOUND   = Pattern.compile("d3d(9|10|11|12).*dll.*not found", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCESS_DENIED    = Pattern.compile("Access is denied\\.?|ERROR_ACCESS_DENIED", Pattern.CASE_INSENSITIVE);
    private static final Pattern SANDBOX_FAILURE  = Pattern.compile("sandbox|SIGSYS|seccomp", Pattern.CASE_INSENSITIVE);

    // ── Known DLLs that can be auto-resolved ─────────────────────────────────
    private static final Set<String> VCREDIST_DLLS = new HashSet<>(Arrays.asList(
            "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll",
            "msvcp140_1.dll", "msvcp140_2.dll", "concrt140.dll", "vcomp140.dll"
    ));

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    public enum Severity { INFO, WARNING, ERROR, CRITICAL }

    /**
     * A single diagnostic finding from the log stream.
     */
    public static class Finding {
        public final Severity severity;
        public final String   title;
        public final String   detail;
        public final String   suggestion;

        public Finding(Severity severity, String title, String detail, String suggestion) {
            this.severity   = severity;
            this.title      = title;
            this.detail     = detail;
            this.suggestion = suggestion;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + title + ": " + detail
                    + (suggestion != null ? " → " + suggestion : "");
        }
    }

    /**
     * A structured report produced from a log session.
     */
    public static class CrashReport {
        public final List<Finding> findings = new ArrayList<>();
        public final Set<String>   missingDlls = new HashSet<>();
        public boolean             hasCritical  = false;

        /** Append a finding and update hasCritical flag. */
        public void add(Finding finding) {
            findings.add(finding);
            if (finding.severity == Severity.CRITICAL) hasCritical = true;
        }

        /** Human-readable summary for log export. */
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Winlator Crash Report ===\n");
            sb.append("Findings: ").append(findings.size()).append("\n");
            if (!missingDlls.isEmpty()) {
                sb.append("Missing DLLs: ").append(String.join(", ", missingDlls)).append("\n");
            }
            sb.append("---\n");
            for (Finding f : findings) sb.append(f).append("\n");
            return sb.toString();
        }
    }

    /**
     * Analyzes a block of log lines and returns a structured CrashReport.
     *
     * @param logLines Lines of Wine/Box64 output to analyze.
     * @return A CrashReport containing all detected findings.
     */
    public static CrashReport analyze(List<String> logLines) {
        CrashReport report = new CrashReport();

        for (String line : logLines) {
            analyzeLine(line, report);
        }

        // Post-process: if VCRedist DLLs are missing, suggest auto-installer
        if (!report.missingDlls.isEmpty()) {
            boolean hasVcDll = false;
            for (String dll : report.missingDlls) {
                if (VCREDIST_DLLS.contains(dll.toLowerCase())) {
                    hasVcDll = true;
                    break;
                }
            }
            if (hasVcDll) {
                report.add(new Finding(
                        Severity.WARNING,
                        "Visual C++ Redistributable Missing",
                        "One or more vcruntime/msvcp DLLs are missing.",
                        "Enable DependencyInstaller to auto-install VCRedist 2022."
                ));
            }
        }

        return report;
    }

    /**
     * Convenience: analyze a single raw log line and add to an existing report.
     * Suitable for streaming analysis without buffering all lines first.
     *
     * @param line   Single log line.
     * @param report Report to append findings to.
     */
    public static void analyzeLine(String line, CrashReport report) {
        if (line == null || line.isEmpty()) return;

        Matcher m;

        // ── Missing DLL (Wine module loader) ──────────────────────────────────
        m = DLL_NOT_FOUND.matcher(line);
        if (m.find()) {
            String dll = m.group(1);
            report.missingDlls.add(dll);
            report.add(new Finding(
                    Severity.ERROR,
                    "Missing DLL",
                    "Could not load: " + dll,
                    "Install the runtime providing " + dll + " into the Wine prefix."
            ));
            return;
        }

        // ── Missing import (Wine import_dll) ─────────────────────────────────
        m = DLL_MISSING_WINE.matcher(line);
        if (m.find()) {
            String dll = m.group(1);
            report.missingDlls.add(dll);
            report.add(new Finding(
                    Severity.ERROR,
                    "DLL Import Failure",
                    "Failed to load library: " + dll,
                    "Check wincomponents settings or install native DLL override."
            ));
            return;
        }

        // ── Box64 missing native library ─────────────────────────────────────
        m = BOX64_MISSING.matcher(line);
        if (m.find()) {
            String lib = m.group(1);
            report.add(new Finding(
                    Severity.ERROR,
                    "Box64 Library Not Found",
                    "Box64 cannot find: " + lib,
                    "Ensure LD_LIBRARY_PATH contains the folder with " + lib + "."
            ));
            return;
        }

        // ── Wine crash (unhandled exception) ─────────────────────────────────
        m = WINE_CRASH.matcher(line);
        if (m.find()) {
            report.add(new Finding(
                    Severity.CRITICAL,
                    "Wine Process Crash",
                    line.trim(),
                    "Check Wine debug logs. Try disabling WINEESYNC or switching to WineD3D."
            ));
            return;
        }

        // ── Vulkan error codes ───────────────────────────────────────────────
        m = VK_ERROR.matcher(line);
        if (m.find()) {
            String code = m.group(1);
            report.add(new Finding(
                    Severity.WARNING,
                    "Vulkan Error",
                    "Vulkan returned: " + code,
                    "Switch to Turnip driver or downgrade DXVK to 1.10.3 for older GPUs."
            ));
            return;
        }

        // ── DXVK DLL not found ───────────────────────────────────────────────
        m = DXVK_NOT_FOUND.matcher(line);
        if (m.find()) {
            report.add(new Finding(
                    Severity.ERROR,
                    "DXVK DLL Missing",
                    line.trim(),
                    "Re-extract the DXVK package via the container settings."
            ));
            return;
        }

        // ── Sandbox / seccomp failure (Chromium) ─────────────────────────────
        m = SANDBOX_FAILURE.matcher(line);
        if (m.find()) {
            report.add(new Finding(
                    Severity.CRITICAL,
                    "Chromium Sandbox Failure",
                    "Sandbox/seccomp restriction detected: " + line.trim(),
                    "Enable ChromiumCompatHelper — add --no-sandbox flag to this shortcut."
            ));
            return;
        }

        // ── Access denied ────────────────────────────────────────────────────
        m = ACCESS_DENIED.matcher(line);
        if (m.find()) {
            report.add(new Finding(
                    Severity.WARNING,
                    "Access Denied",
                    line.trim(),
                    "Check Wine prefix permissions or run wine with WINLATOR_SEB_MODE=1."
            ));
        }
    }
}
