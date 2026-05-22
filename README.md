# Winlator SEB: Compatibility-Focused Windows Emulation on Android

A specialized fork of **Winlator** engineered to run modern, security-sensitive Windows enterprise software, including **Safe Exam Browser (SEB)**, Chromium/Electron-based applications, and modern .NET runtimes on Android hardware.

---

## 🚀 Key Accomplishments & Technical Overview

This project modernizes the underlying Wine/Android integration layers to bypass typical environment checks and sandbox limitations:

1. **Modular Registry Environment Spoofing (`SEBCompatibilityLayer`)**
   * Automatically generates registry stubs to emulate hardware security standards requested by enterprise software.
   * Injects mock variables for **TPM 2.0** presence, **Secure Boot** state, active **Windows Defender** status, and valid system **Cryptography Machine GUIDs**.
   
2. **Automated Dependency Injection (`DependencyInstaller`)**
   * Integrates seamless installation paths for critical prerequisites like **.NET Framework 4.8**, **Microsoft Visual C++ Redistributable (2015-2022)**, and **Microsoft WebView2 (Chromium-based)** inside Wine.
   
3. **Chromium Sandbox Bypass (`ChromiumCompatHelper`)**
   * Hooks into the Box64 launcher (`GuestProgramLauncherComponent`) to automatically intercept and append `--no-sandbox` and `--in-process-gpu` arguments to Chromium and Electron processes, avoiding hard crashes caused by Linux/Android namespace limitations.

4. **Performance & Stability Fixes**
   * Pre-applied tweaks to disable conflict-prone synchronization layers (`WINEESYNC`, `WINEFSYNC`) specifically for SEB's inter-process communication (IPC) pipe requirements.
   * Corrected underlying compiler issues (including D8 dexing null-pointer exceptions in ALSA audio clients) to compile reliably using newer JDK toolchains.

---

## ⚠️ Important Limitations & Technical Caveats

While significant progress has been made, users should be aware of the following technical constraints:

* **WPF Rendering Incompatibilities:** Safe Exam Browser utilizes Windows Presentation Foundation (WPF) for its user interface. The open-source Wine Mono implementation does not fully support GPU-accelerated WPF, which can lead to black screens. Software rendering overrides must be enabled (see configuration steps).
* **Kernel-Level Integrity Drivers:** Winlator operates entirely in **user-space** on Android. If an organization configures SEB with strict security policies that demand native kernel-level monitoring drivers or deep Windows API hooks, user-mode Wine emulation cannot execute them.
* **Proctoring Restrictions:** Some exam configurations require active webcam/microphone hardware integrations, which may experience virtualization lag or permission blocks.

---

## 🛠️ Step-by-Step Setup & Configuration Guide

Follow these instructions to run Safe Exam Browser within your custom Winlator container:

### 1. Download Prerequisites
Before starting the installation, download these official Microsoft installers on your Android device (they will be accessible under the `D:` drive mapping in Winlator):
* **VC++ Redistributable x86:** [Direct Link](https://aka.ms/vs/17/release/vc_redist.x86.exe)
* **VC++ Redistributable x64:** [Direct Link](https://aka.ms/vs/17/release/vc_redist.x64.exe)
* **Safe Exam Browser (SEB) Installer:** Use the standard Windows `.exe` installer (version 2.4.x or 3.x depending on your target exam).

### 2. Create the Custom Container
Open the compiled Winlator SEB application and configure a new container with the following optimized parameters:
* **Screen Size:** `1280x720` (Standard desktop scaling)
* **Graphics Driver:** `Turnip` (Recommended for Qualcomm Adreno GPUs)
* **DX Wrapper:** `WineD3D` (Do *not* use DXVK; SEB's browser engine requires WineD3D for stability)
* **Windows Version:** `Windows 10`
* **Environment Variables (under the Advanced tab):**
  * Turn **WINEESYNC** to **OFF** (Critical for SEB IPC)
  * Set `LIBGL_ALWAYS_SOFTWARE` = `1`
  * Set `GALLIUM_DRIVER` = `softpipe`

### 3. Apply Registry Fixes (WPF Software Rendering)
To prevent the application window from rendering as a solid black screen:
1. Tap the **Start Menu** in the Wine desktop -> select **Run**.
2. Type `regedit` and press Enter.
3. Navigate to: `HKEY_CURRENT_USER\Software\Microsoft\`.
4. Right-click the `Microsoft` folder -> select **New -> Key** and name it `Avalon.Graphics`.
5. Inside `Avalon.Graphics`, right-click the right-hand pane -> select **New -> DWORD Value**:
   * Name: `DisableHWAcceleration` / Value: `1`
   * Name: `RenderingTier` / Value: `0`
6. Close the Registry Editor.

### 4. Install & Launch
1. Open the File Manager in Winlator, navigate to `D:\Downloads`, and install `vc_redist.x86.exe` followed by `vc_redist.x64.exe`.
2. Run the Safe Exam Browser installer. 
3. Launch SEB using the main entry shortcut:
   `"C:\Program Files\SafeExamBrowser\Application\SafeExamBrowser.exe"`
   *(Do not launch the Client executable directly).*

---

## ⚖️ Disclaimer & Educational Use Warning

> [!WARNING]
> This repository and its modifications are created strictly for **compatibility research, academic analysis, and educational testing purposes**. 
> 
> * **No Integrity Bypass:** This project does not compromise, bypass, or crack any digital rights management (DRM), online authorization servers, or secure exam access keys. 
> * **Institutional Rules:** Attempting to take high-stakes examinations or certifications using a virtualized or emulated environment (such as Winlator) may violate your institution's Honor Code, Terms of Service, or security guidelines, leading to immediate disqualification.
> * **Usage Caution:** The authors of this project do not endorse, encourage, or facilitate academic dishonesty. Use this software responsibly and only in authorized test environments.