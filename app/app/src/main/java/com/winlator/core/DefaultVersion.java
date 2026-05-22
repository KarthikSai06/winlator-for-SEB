package com.winlator.core;

import com.winlator.container.GraphicsDrivers;

import java.util.Locale;

/**
 * Central registry of default component versions used by Winlator.
 * Update version constants here when bundling new runtime binaries into the APK.
 */
public abstract class DefaultVersion {
    // ─── Core emulation components ──────────────────────────────────────────────
    public static final String BOX64 = "0.4.0";

    // ─── Vulkan / Graphics drivers ───────────────────────────────────────────────
    public static final String TURNIP = "26.1.0";
    public static final String VORTEK = "2.1";
    public static final String ZINK = "22.2.5";
    public static final String VIRGL = "23.1.9";
    public static final String GLADIO = "1.0";

    // ─── DirectX translation layers ──────────────────────────────────────────────
    public static final String D8VK = "1.0";
    /** VKD3D-Proton 3.0b — requires Vulkan 1.3, bundled in APK assets. */
    public static final String VKD3D = "3.0b";
    public static final String WINED3D = WineInfo.MAIN_WINE_VERSION;
    public static final String CNC_DDRAW = "6.6";

    // ─── DXVK versions ───────────────────────────────────────────────────────────
    /** DXVK 1.10.3 — Vulkan 1.1 fallback for older devices. */
    public static final String MINOR_DXVK = "1.10.3";
    /** DXVK 2.6.1 — Vulkan 1.3 / Async shader compilation, for Adreno / Turnip. */
    public static final String MAJOR_DXVK = "2.6.1";

    // ─── Miscellaneous ────────────────────────────────────────────────────────────
    public static final String SOUNDFONT = "SONiVOX-EAS-GM-Wavetable";

    // ─── Wine Staging (bundled in APK) ───────────────────────────────────────────
    /**
     * Wine Staging build bundled into the APK.
     * Wine Staging 10.10 carries patches for improved WebView2, modern TLS,
     * improved Vulkan WSI, and partial Chromium/Electron compatibility.
     */
    public static final String WINE_STAGING = WineInfo.MAIN_WINE_VERSION + "-staging";

    /**
     * Select DXVK version based on the active Vulkan driver.
     * - Turnip (Mesa Adreno): always uses MAJOR_DXVK (Vulkan 1.3 capable).
     * - Vortek: checks the runtime Vulkan API level; falls back to MINOR_DXVK
     *   on devices that only expose Vulkan 1.1 or 1.2.
     */
    public static String DXVK() {
        return DXVK(null);
    }

    public static String DXVK(String vulkanDriver) {
        int vkApiVersion = 0;
        if (vulkanDriver != null && vulkanDriver.equals(GraphicsDrivers.VORTEK)) {
            vkApiVersion = GPUHelper.vkGetApiVersion();
        }
        return vulkanDriver == null
                || vulkanDriver.equals(GraphicsDrivers.TURNIP)
                || vkApiVersion >= GPUHelper.vkMakeVersion(1, 3, 0)
                ? MAJOR_DXVK
                : MINOR_DXVK;
    }

    public static String valueOf(String name) {
        switch (name.toUpperCase(Locale.ENGLISH)) {
            case "BOX64":      return BOX64;
            case "TURNIP":     return TURNIP;
            case "VORTEK":     return VORTEK;
            case "ZINK":       return ZINK;
            case "VIRGL":      return VIRGL;
            case "GLADIO":     return GLADIO;
            case "D8VK":       return D8VK;
            case "VKD3D":      return VKD3D;
            case "WINED3D":    return WINED3D;
            case "CNC_DDRAW":  return CNC_DDRAW;
            case "SOUNDFONT":  return SOUNDFONT;
            default:           return "0.0";
        }
    }
}