package com.winlator.compat;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineRegistryEditor;
import com.winlator.xenvironment.RootFS;

import java.io.File;

/**
 * DependencyInstaller — Phase 3: Automated Runtime Dependency Installer.
 *
 * Modern Windows applications (Electron, SEB, .NET 6+ apps) require a number of
 * Visual C++ Redistributable runtimes and WebView2 components. This class
 * provides silent installation of these runtimes into the Wine prefix on
 * first launch, so the user does not need to manually run any installers.
 *
 * Installation strategy:
 * ──────────────────────────────────────────────────────────────────────────────
 * 1. Extract bundled runtime DLL archives from APK assets into system32/syswow64.
 * 2. Set the correct registry keys so Wine reports these packages as "installed".
 * 3. Track installed state via the container's extra data to avoid reinstalling.
 * ──────────────────────────────────────────────────────────────────────────────
 */
public class DependencyInstaller {

    private static final String TAG = "DependencyInstaller";

    /**
     * Installs all required runtimes for Chromium/Electron/SEB into the Wine prefix.
     * Safe to call multiple times — tracks which packages are already installed.
     *
     * @param context       Android application context.
     * @param rootFS        The RootFS of the current container.
     * @param forceReinstall Set true to reinstall even if already present.
     */
    public static void installAll(Context context, RootFS rootFS, boolean forceReinstall) {
        Log.d(TAG, "Checking/installing runtime dependencies…");

        installVCRedist2015_2022(context, rootFS, forceReinstall);
        installDotNet48Runtime(context, rootFS, forceReinstall);
        installWebView2Stub(context, rootFS, forceReinstall);

        Log.d(TAG, "Runtime dependency check complete.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Visual C++ Redistributables 2015-2022
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Installs Visual C++ Redistributable 2015-2022 (x64 + x86) DLLs.
     *
     * These DLLs are required by virtually every modern Electron app and by SEB.
     * Files: vcruntime140.dll, msvcp140.dll, msvcp140_1.dll, msvcp140_2.dll,
     *        concrt140.dll, vccorlib140.dll, vcruntime140_1.dll
     */
    private static void installVCRedist2015_2022(Context context, RootFS rootFS, boolean force) {
        final String markerKey = "vcredist2022";
        File markerFile = new File(rootFS.getRootDir(), ".wine/.compat/" + markerKey);

        if (!force && markerFile.exists()) {
            Log.d(TAG, "VCRedist 2015-2022 already installed, skipping.");
            return;
        }

        Log.i(TAG, "Installing Visual C++ Redistributables 2015-2022…");

        File windowsDir = new File(rootFS.getRootDir(), RootFS.WINEPREFIX + "/drive_c/windows");
        File system32   = new File(windowsDir, "system32");
        File syswow64   = new File(windowsDir, "syswow64");

        // Extract x64 DLLs from bundled assets
        boolean ok64 = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context,
                "compat/vcredist2022-x64.tzst", system32);

        // Extract x86 DLLs from bundled assets
        boolean ok32 = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context,
                "compat/vcredist2022-x86.tzst", syswow64);

        if (ok64 && ok32) {
            registerVCRedistInRegistry(rootFS);
            writeMarker(markerFile);
            Log.i(TAG, "VCRedist 2015-2022 installed successfully.");
        } else {
            Log.w(TAG, "VCRedist 2015-2022 asset not found in APK — skipping.");
        }
    }

    private static void registerVCRedistInRegistry(RootFS rootFS) {
        File systemRegFile = new File(rootFS.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {
            // 64-bit entry
            final String key64 = "Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\X64";
            reg.setDwordValue(key64, "Installed", 1);
            reg.setDwordValue(key64, "Major",     14);
            reg.setDwordValue(key64, "Minor",     38);
            reg.setDwordValue(key64, "Bld",       3468);

            // 32-bit entry
            final String key32 = "Software\\WOW6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\X86";
            reg.setDwordValue(key32, "Installed", 1);
            reg.setDwordValue(key32, "Major",     14);
            reg.setDwordValue(key32, "Minor",     38);
            reg.setDwordValue(key32, "Bld",       3468);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register VCRedist in registry: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // .NET Framework 4.8 Runtime (via Wine Mono fallback)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Registers .NET Framework 4.8 as installed in the registry.
     *
     * Wine Mono handles the actual .NET runtime emulation. However, many installers
     * and applications check the registry "Release" DWORD to confirm that .NET 4.8
     * is installed before proceeding. This creates that registry key.
     */
    private static void installDotNet48Runtime(Context context, RootFS rootFS, boolean force) {
        final String markerKey = "dotnet48";
        File markerFile = new File(rootFS.getRootDir(), ".wine/.compat/" + markerKey);

        if (!force && markerFile.exists()) {
            Log.d(TAG, ".NET 4.8 registry entry already present, skipping.");
            return;
        }

        Log.i(TAG, "Registering .NET Framework 4.8 in Wine registry…");

        File systemRegFile = new File(rootFS.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {

            // Main .NET 4.8 installation key
            final String net48Key = "Software\\Microsoft\\NET Framework Setup\\NDP\\v4\\Full";
            reg.setDwordValue(net48Key, "Install",  1);
            reg.setDwordValue(net48Key, "Release",  528049); // .NET 4.8 on Win10
            reg.setStringValue(net48Key, "Version", "4.8.04084");
            reg.setStringValue(net48Key, "SKUName", "Full");

            // WoW6432Node (32-bit app registry view)
            final String net48Key32 = "Software\\WOW6432Node\\Microsoft\\NET Framework Setup\\NDP\\v4\\Full";
            reg.setDwordValue(net48Key32, "Install",  1);
            reg.setDwordValue(net48Key32, "Release",  528049);
            reg.setStringValue(net48Key32, "Version", "4.8.04084");
            reg.setStringValue(net48Key32, "SKUName", "Full");

            writeMarker(markerFile);
            Log.i(TAG, ".NET 4.8 registry entry created.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register .NET 4.8: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WebView2 Runtime Stub
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a WebView2 runtime presence stub in the registry.
     *
     * Safe Exam Browser and many Electron apps query the registry to verify that
     * Microsoft Edge WebView2 Runtime is installed. This stub reports a compatible
     * version so that the application's installer/launcher proceeds.
     *
     * The actual browser rendering is handled by Wine's built-in Chromium bridge
     * (or by the Chromium binary bundled with the application itself).
     */
    private static void installWebView2Stub(Context context, RootFS rootFS, boolean force) {
        final String markerKey = "webview2stub";
        File markerFile = new File(rootFS.getRootDir(), ".wine/.compat/" + markerKey);

        if (!force && markerFile.exists()) {
            Log.d(TAG, "WebView2 stub already present, skipping.");
            return;
        }

        Log.i(TAG, "Installing WebView2 runtime registry stub…");

        File systemRegFile = new File(rootFS.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor reg = new WineRegistryEditor(systemRegFile)) {

            // WebView2 Runtime — 64-bit
            final String wv2Key64 = "Software\\Microsoft\\EdgeUpdate\\Clients\\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
            reg.setStringValue(wv2Key64, "pv",      "120.0.2210.133");
            reg.setStringValue(wv2Key64, "name",    "Microsoft Edge WebView2 Runtime");
            reg.setStringValue(wv2Key64, "lang",    "en");

            // WebView2 Runtime — 32-bit view
            final String wv2Key32 = "Software\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
            reg.setStringValue(wv2Key32, "pv",      "120.0.2210.133");
            reg.setStringValue(wv2Key32, "name",    "Microsoft Edge WebView2 Runtime");
            reg.setStringValue(wv2Key32, "lang",    "en");

            // Policy key — disable WebView2 update checks (avoids network errors on Android)
            final String policyKey = "Software\\Policies\\Microsoft\\Edge\\WebView2";
            reg.setDwordValue(policyKey, "AutomaticUpdates", 0);
            reg.setDwordValue(policyKey, "BrowserExecutableFolder", 0);

            writeMarker(markerFile);
            Log.i(TAG, "WebView2 stub installed.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to install WebView2 stub: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private static void writeMarker(File markerFile) {
        markerFile.getParentFile().mkdirs();
        FileUtils.writeString(markerFile, String.valueOf(System.currentTimeMillis()));
    }
}
