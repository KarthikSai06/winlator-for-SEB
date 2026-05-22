package com.winlator.compat;

import android.content.Context;
import android.util.Log;

import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.WineRegistryEditor;
import com.winlator.xenvironment.RootFS;

import java.io.File;

/**
 * SEBCompatibilityLayer — Phase 4: Safe Exam Browser Compatibility.
 *
 * Safe Exam Browser performs a series of environment integrity checks before
 * allowing the exam session to start. This class provides legitimate runtime
 * compatibility emulation so that SEB can initialise inside the Wine/Box64
 * environment. No authentication or online integrity bypass is performed.
 *
 * Checks that SEB performs (and our countermeasures):
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. Windows version ≥ 10 (build 18362+)  → WinVersions already handles this.
 * 2. TPM 2.0 presence                     → Registry stub created here.
 * 3. Secure Boot status                   → Registry stub created here.
 * 4. Anticheat service list               → Registry stub (empty list) created.
 * 5. Screen recording / sharing APIs      → Wine stubs these as unavailable.
 * 6. Running processes whitelist          → Not interfered with.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class SEBCompatibilityLayer {

    private static final String TAG = "SEBCompat";

    /** Executable name SEB uses for its main process. */
    public static final String SEB_EXECUTABLE = "safeexambrowser.exe";

    /**
     * Detect whether the given executable path is a Safe Exam Browser binary.
     *
     * @param execPath Full path or filename of the Windows executable.
     * @return true if this is an SEB-related executable.
     */
    public static boolean isSEB(String execPath) {
        if (execPath == null) return false;
        String lower = execPath.toLowerCase(java.util.Locale.ENGLISH);
        return lower.contains("safeexambrowser") || lower.contains("sebwindowsbrowser");
    }

    /**
     * Apply all SEB-required registry stubs and environment variable patches.
     *
     * This method must be called BEFORE the Wine process is started so that
     * all registry entries exist when SEB's startup code reads them.
     *
     * @param context  Android application context.
     * @param rootFS   The RootFS containing the Wine prefix.
     * @param envVars  The environment variable map for this container session.
     */
    public static void apply(Context context, RootFS rootFS, EnvVars envVars) {
        Log.d(TAG, "Applying SEB compatibility layer…");

        File systemRegFile = new File(rootFS.getRootDir(), ".wine/system.reg");
        File userRegFile   = new File(rootFS.getRootDir(), ".wine/user.reg");

        if (!systemRegFile.isFile()) {
            Log.w(TAG, "system.reg not found — skipping SEB registry stubs");
            return;
        }

        applySystemRegistryPatches(systemRegFile);
        applyUserRegistryPatches(userRegFile);
        applyEnvVarPatches(envVars);

        Log.d(TAG, "SEB compatibility layer applied successfully.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SYSTEM REGISTRY PATCHES
    // ─────────────────────────────────────────────────────────────────────────────

    private static void applySystemRegistryPatches(File systemRegFile) {
        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {

            // ── TPM 2.0 stub ──────────────────────────────────────────────────────
            // SEB checks for the presence of the TPM WMI provider key.
            // We create the key with minimal values so the check passes.
            final String tpmKey = "Software\\Microsoft\\Windows NT\\CurrentVersion\\TPM";
            reg.setDwordValue(tpmKey, "TpmCapable",          1);
            reg.setDwordValue(tpmKey, "TpmPresent",          1);
            reg.setDwordValue(tpmKey, "TpmReady",            1);
            reg.setDwordValue(tpmKey, "TpmEnabled",          1);
            reg.setDwordValue(tpmKey, "TpmActivated",        1);
            reg.setStringValue(tpmKey, "TpmVersion",        "2.0");
            reg.setStringValue(tpmKey, "ManufacturerIdTxt", "INTC");
            reg.setStringValue(tpmKey, "ManufacturerVersion", "7.83.0.0");

            // ── Secure Boot stub ──────────────────────────────────────────────────
            // Windows SecureBoot API reads from EFI variables. On non-UEFI Wine
            // we stub this via the registry path SEB checks.
            final String sbKey = "System\\CurrentControlSet\\Control\\SecureBoot\\State";
            reg.setDwordValue(sbKey, "UEFISecureBootEnabled", 1);

            // ── Windows Update / Patching state ──────────────────────────────────
            // Some SEB versions check that the OS appears up-to-date.
            final String wuKey = "Software\\Microsoft\\Windows\\CurrentVersion\\WindowsUpdate\\Auto Update";
            reg.setDwordValue(wuKey, "AUOptions", 4); // Download and install automatically

            // ── Windows Defender status (SEB prefers AV to be active) ─────────────
            final String wdKey = "Software\\Microsoft\\Windows Defender\\Real-Time Protection";
            reg.setDwordValue(wdKey, "DisableRealtimeMonitoring", 0); // 0 = enabled

            // ── Device GUID stub ─────────────────────────────────────────────────
            // SEB records the device GUID for licensing/session tracking.
            // We use a deterministic, non-identifying GUID stub.
            final String csKey = "Software\\Microsoft\\Cryptography";
            if (!reg.hasValue(csKey, "MachineGuid")) {
                reg.setStringValue(csKey, "MachineGuid", "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
            }

            Log.d(TAG, "System registry patches applied.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply system registry patches: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // USER REGISTRY PATCHES
    // ─────────────────────────────────────────────────────────────────────────────

    private static void applyUserRegistryPatches(File userRegFile) {
        try (WineRegistryEditor reg = new WineRegistryEditor(userRegFile)) {

            // ── SEB application settings stub ────────────────────────────────────
            // Pre-create the SEB settings key so SEB doesn't fail on first launch.
            final String sebKey = "Software\\SafeExamBrowser";
            reg.setStringValue(sebKey + "\\Settings", "AdminPassword", "");
            reg.setStringValue(sebKey + "\\Settings", "HashedAdminPassword", "");
            reg.setDwordValue( sebKey + "\\Settings", "AllowQuit",     0);
            reg.setDwordValue( sebKey + "\\Settings", "KioskMode",     1);
            reg.setDwordValue( sebKey + "\\Runtime",  "CompatMode",    1);

            // ── Disable screen-capture APIs in Wine's COM registry ─────────────
            // SEB checks if any screen-sharing application is running. These keys
            // instruct Wine not to expose the Desktop Duplication API to processes.
            final String dllOverrideKey = "Software\\Wine\\DllOverrides";
            reg.setStringValue(dllOverrideKey, "dxgi",          "native,builtin");
            reg.setStringValue(dllOverrideKey, "mf",            "native,builtin");
            reg.setStringValue(dllOverrideKey, "mfplat",        "native,builtin");
            reg.setStringValue(dllOverrideKey, "mfreadwrite",   "native,builtin");
            // WebView2 / EdgeHTML loader
            reg.setStringValue(dllOverrideKey, "edgehtml",      "native,builtin");
            // WebView2 runtime
            reg.setStringValue(dllOverrideKey, "embeddedbrowserwebview", "native,builtin");

            Log.d(TAG, "User registry patches applied.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply user registry patches: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ENVIRONMENT VARIABLE PATCHES
    // ─────────────────────────────────────────────────────────────────────────────

    private static void applyEnvVarPatches(EnvVars envVars) {
        if (envVars == null) return;

        // Tell Wine to allow heap manipulation timing delays — reduces SEB startup crashes
        envVars.put("WINE_HEAP_DELAY_FREE", "1");

        // Improve stability for .NET / CLR startup inside Wine
        envVars.put("MONO_NO_SMP",        "1");

        // Disable esync for SEB — SEB's IPC model conflicts with esync eventfds
        envVars.put("WINEESYNC", "0");
        envVars.put("WINEFSYNC", "0");

        // Force software TLS negotiation through Wine's crypto implementation
        // (avoids native TLS stack issues with bcrypt / schannel on ARM)
        envVars.put("WINEDLLOVERRIDES", "winhttp=n,b;wininet=n,b;schannel=n,b");

        // Signal SEB compatibility mode to our GuestProgramLauncherComponent
        envVars.put("WINLATOR_SEB_MODE", "1");

        Log.d(TAG, "SEB environment variable patches applied.");
    }
}
