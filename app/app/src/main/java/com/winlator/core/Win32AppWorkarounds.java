package com.winlator.core;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.DXWrappers;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;

import java.util.Locale;

public class Win32AppWorkarounds {
    private final short taskAffinityMask;
    private final short taskAffinityMaskWoW64;
    private final XServerDisplayActivity activity;

    private interface Workaround {}

    private static class MultiWorkaround implements Workaround {
        private final Workaround[] list;

        public MultiWorkaround(Workaround... list) {
            this.list = list;
        }
    }

    private interface WindowWorkaround extends Workaround {
        void apply(Window window);
    }

    private interface EnvVarsWorkaround extends Workaround {
        void apply(EnvVars envVars);
    }

    private interface ScreenSizeWorkaround extends Workaround {
        String getValue();
    }

    private interface DXWrapperWorkaround extends Workaround {
        String getValue();
    }

    private interface WinComponentsWorkaround extends Workaround {
        void setValue(KeyValueSet wincomponents);
    }

    public Win32AppWorkarounds(XServerDisplayActivity activity) {
        this.activity = activity;
        Container container = activity.getContainer();
        taskAffinityMask = (short)ProcessHelper.getAffinityMask(container.getCPUList(true));
        taskAffinityMaskWoW64 = (short)ProcessHelper.getAffinityMask(container.getCPUListWoW64(true));
    }

    private void applyWorkaround(Workaround workaround) {
        if (workaround instanceof EnvVarsWorkaround) {
            ((EnvVarsWorkaround)workaround).apply(activity.getOverrideEnvVars());
        }
        else if (workaround instanceof ScreenSizeWorkaround) {
            activity.setScreenInfo(new ScreenInfo(((ScreenSizeWorkaround)workaround).getValue()));
        }
        else if (workaround instanceof DXWrapperWorkaround) {
            activity.setDXWrapper(((DXWrapperWorkaround)workaround).getValue());
        }
        else if (workaround instanceof WinComponentsWorkaround) {
            KeyValueSet wincomponents = new KeyValueSet(Container.DEFAULT_WINCOMPONENTS);
            ((WinComponentsWorkaround)workaround).setValue(wincomponents);
            activity.setWinComponents(wincomponents.toString());
        }
    }

    public void applyStartupWorkarounds(String className) {
        Workaround workaround = getWorkaroundFor(className);
        if (workaround == null) return;

        if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround)workaround).list) applyWorkaround(workaround2);
        }
        else applyWorkaround(workaround);
    }

    private void setProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = activity.getWinHandler();

        if (className.equals("steam.exe")) return;

        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        }
        else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    public void applyWindowWorkarounds(Window window) {
        Workaround workaround = getWorkaroundFor(window.getClassName());
        if (workaround instanceof WindowWorkaround) {
            ((WindowWorkaround)workaround).apply(window);
        }
        else if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround) workaround).list) {
                if (workaround2 instanceof WindowWorkaround) {
                    ((WindowWorkaround)workaround2).apply(window);
                    break;
                }
            }
        }

        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        boolean canApplyProcessAffinity = window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id;
        if (canApplyProcessAffinity) {
            int processAffinity = window.isWoW64() ? taskAffinityMaskWoW64 : taskAffinityMask;
            if (processAffinity != 0) setProcessAffinity(window, processAffinity);
        }
    }

    private Workaround getWorkaroundFor(String className) {
        String appIdentifier;
        if (className.startsWith("steam://")) {
            appIdentifier = className.substring(className.lastIndexOf("/") + 1);
        }
        else appIdentifier = className.toLowerCase(Locale.ENGLISH);

        switch (appIdentifier) {
            case "sonicgenerations.exe":
            case "71340":
            case "valkyria.exe":
            case "294860":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEESYNC", "0");
            case "blacklist_game.exe":
            case "blacklist_dx11_game.exe":
                return (EnvVarsWorkaround) (envVars) -> envVars.put("WINEOVERRIDEAFFINITYMASK", taskAffinityMaskWoW64);
            case "fate.exe":
                return (ScreenSizeWorkaround) () -> "1024x768";
            case "ffxii_tza.exe":
                ScreenInfo screenInfo = activity.getScreenInfo();
                return (ScreenSizeWorkaround) () -> (screenInfo.width+4)+"x"+(screenInfo.height+4);
            case "chronocross_launcher.exe":
                return (WindowWorkaround) (window) -> window.attributes.setTransparent(true);
            case "dino.exe":
            case "dino2.exe":
            case "bof4.exe":
                return (WinComponentsWorkaround) (wincomponents) -> wincomponents.put("directshow", "1");
            case "discipl2.exe":
                return (DXWrapperWorkaround) () -> DXWrappers.WINED3D;

            // ── Phase 4: Safe Exam Browser ────────────────────────────────────────
            // SEB conflicts with WINEESYNC and WINEFSYNC due to its own IPC model.
            // Also disable DXVK and use WineD3D since SEB uses Chromium's own GPU.
            case "safeexambrowser.exe":
            case "sebwindowsbrowser.exe":
                return new MultiWorkaround(
                    (EnvVarsWorkaround) (envVars) -> {
                        envVars.put("WINEESYNC", "0");
                        envVars.put("WINEFSYNC", "0");
                        envVars.put("WINE_HEAP_DELAY_FREE", "1");
                        // Chromium in SEB requires these Mesa flags
                        envVars.put("MESA_GL_VERSION_OVERRIDE", "4.5");
                        envVars.put("MESA_GLSL_VERSION_OVERRIDE", "450");
                    },
                    (DXWrapperWorkaround) () -> DXWrappers.WINED3D
                );

            // ── Phase 3: Common Electron apps ─────────────────────────────────────
            // VS Code, Discord, Slack use Chromium's GPU process which conflicts
            // with DXVK — route them through WineD3D instead.
            case "code.exe":
            case "discord.exe":
            case "slack.exe":
            case "teams.exe":
            case "notion.exe":
            case "obsidian.exe":
                return new MultiWorkaround(
                    (EnvVarsWorkaround) (envVars) -> {
                        envVars.put("WINEESYNC", "0");
                        envVars.put("GALLIUM_DRIVER", "softpipe"); // Fallback for Chromium GPU
                    },
                    (DXWrapperWorkaround) () -> DXWrappers.WINED3D
                );

            default:
                return null;
        }
    }
}