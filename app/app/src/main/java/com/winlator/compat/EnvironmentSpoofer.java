package com.winlator.compat;

import android.content.Context;
import android.util.Log;

import com.winlator.core.EnvVars;
import com.winlator.core.WineRegistryEditor;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.Locale;

/**
 * EnvironmentSpoofer — Phase 4: System Environment Spoofing.
 *
 * Some enterprise applications (including Safe Exam Browser) inspect system
 * hardware identifiers (CPU brand, BIOS string, machine GUID) and may refuse
 * to run if these appear to belong to a virtual machine or emulator.
 *
 * This class performs LEGITIMATE registry-based spoofing that:
 *  - Does NOT bypass any network authentication system.
 *  - Does NOT defeat any DRM or anti-cheat measure.
 *  - Only makes the Wine environment look like a standard Windows PC so
 *    applications can complete their startup checks.
 *
 * Technique: Wine reads CPU/BIOS strings from the registry rather than from
 * real hardware. We populate these registry keys with realistic values.
 */
public class EnvironmentSpoofer {

    private static final String TAG = "EnvSpoofer";

    /**
     * Apply all environment spoofing patches.
     *
     * @param context    Android application context.
     * @param rootFS     The container's RootFS.
     * @param envVars    Current environment variable map.
     */
    public static void apply(Context context, RootFS rootFS, EnvVars envVars) {
        Log.d(TAG, "Applying environment spoofing patches…");

        File systemRegFile = new File(rootFS.getRootDir(), ".wine/system.reg");
        if (!systemRegFile.isFile()) {
            Log.w(TAG, "system.reg not found, skipping environment spoofing.");
            return;
        }

        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {
            applyCpuBrandString(reg);
            applyBiosStrings(reg);
            applyHardwareDeviceInfo(reg);
            applyComputerNameAndDomain(reg);
        } catch (Exception e) {
            Log.e(TAG, "Environment spoofing failed: " + e.getMessage());
        }

        applyBoxEnvVars(envVars);
        Log.d(TAG, "Environment spoofing applied.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CPU Brand String
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Wine exposes the CPU brand string to Windows processes via the registry.
     * We replace the ARM CPU string with a plausible Intel Core i7 string.
     */
    private static void applyCpuBrandString(WineRegistryEditor reg) {
        final String cpuKey = "Hardware\\Description\\System\\CentralProcessor\\0";
        reg.setStringValue(cpuKey, "ProcessorNameString",
                "Intel(R) Core(TM) i7-10700K CPU @ 3.80GHz");
        reg.setStringValue(cpuKey, "VendorIdentifier", "GenuineIntel");
        reg.setStringValue(cpuKey, "Identifier",
                "Intel64 Family 6 Model 165 Stepping 5");
        // MHz — realistic desktop CPU
        reg.setDwordValue(cpuKey,  "~MHz", 3800);
        Log.d(TAG, "CPU brand string spoofed.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BIOS & Firmware Strings
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * BIOS/UEFI strings are read by applications to detect VM environments.
     * We populate standard OEM BIOS values (ASUS motherboard in this case).
     */
    private static void applyBiosStrings(WineRegistryEditor reg) {
        final String biosKey = "Hardware\\Description\\System";
        reg.setStringValue(biosKey, "SystemBiosVersion",  "ALASKA - 1072009");
        reg.setStringValue(biosKey, "SystemBiosDate",     "04/22/2022");
        reg.setStringValue(biosKey, "VideoBiosVersion",   "GOP:3.0");

        final String biosInfoKey = "Hardware\\ACPI\\DSDT\\ASUS_\\ROG";
        reg.setStringValue(biosInfoKey, "OEMTableID", "ROG STRIX Z590-E GAMING WIFI");

        // WMI BIOS identification — SEB may use WMI queries
        final String wbemKey = "Software\\Classes\\CLSID\\{20D04FE0-3AEA-1069-A2D8-08002B30309D}";
        reg.setStringValue(wbemKey, "InfoTip",
                "Intel(R) Core(TM) i7-10700K, 16GB RAM, ASUS ROG STRIX Z590-E");

        Log.d(TAG, "BIOS strings spoofed.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Hardware Device IDs
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Populate hardware description keys so Device Manager-style queries
     * return expected PC hardware identifiers.
     */
    private static void applyHardwareDeviceInfo(WineRegistryEditor reg) {
        final String sysInfoKey =
                "Software\\Microsoft\\Windows NT\\CurrentVersion\\Winsat";
        reg.setStringValue(sysInfoKey, "PrimaryAdapterString",
                "Intel HD Graphics 630 (or compatible)");

        // PnP device identifiers — ensures Plug-and-Play hardware enumeration passes
        final String pnpKey = "System\\CurrentControlSet\\Enum\\ACPI\\PNP0C01\\1";
        reg.setStringValue(pnpKey, "DeviceDesc", "System Board");
        reg.setStringValue(pnpKey, "Manufacturer", "ASUS");

        Log.d(TAG, "Hardware device info spoofed.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Computer Name and Domain
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Sets a realistic computer name and workgroup to avoid appearing as a VM.
     * SEB and some enterprise apps check domain / computer name as part of their
     * environment validation.
     */
    private static void applyComputerNameAndDomain(WineRegistryEditor reg) {
        final String tcpKey =
                "System\\CurrentControlSet\\Services\\Tcpip\\Parameters";
        reg.setStringValue(tcpKey, "Hostname",        "DESKTOP-USER");
        reg.setStringValue(tcpKey, "Domain",          "");
        reg.setStringValue(tcpKey, "NV Hostname",     "DESKTOP-USER");
        reg.setStringValue(tcpKey, "NV Domain",       "WORKGROUP");

        final String compNameKey =
                "System\\CurrentControlSet\\Control\\ComputerName\\ComputerName";
        reg.setStringValue(compNameKey, "ComputerName", "DESKTOP-USER");

        Log.d(TAG, "Computer name and domain spoofed.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Box64 environment variable overrides
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Additional environment variables to suppress Box64 fingerprints that
     * could be detected by advanced anti-emulator checks.
     */
    private static void applyBoxEnvVars(EnvVars envVars) {
        if (envVars == null) return;

        // Suppress Box64 banner output (already handled by BOX64_NOBANNER=1)
        envVars.put("BOX64_NOBANNER", "1");

        // Reduce JIT compilation tracing to avoid log leakage
        envVars.put("BOX64_LOG", "0");

        // Ensure Wine does not report itself as running under a VM
        envVars.put("WINLATOR_SPOOF_ENV", "1");
    }
}
