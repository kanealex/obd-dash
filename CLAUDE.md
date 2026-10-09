# OBD Dash — project context

Handoff from a claude.ai chat. Read this before working on `obd-dash.html`.

## Goal
A web app (PWA) running in Chrome on a **Pixel 7** that reads live data from an **ELM327 OBD-II adapter** in a **2009/10 Toyota LandCruiser 200 GXL (VDJ200R, 1VD-FTV V8 diesel)**.

## Hardware / environment
- Adapter: dual-mode ELM327 clone — Bluetooth 2.0 Classic **and** 4.0 BLE. The app uses the **BLE** side only.
- Phone also runs **wireless Android Auto** to the car at the same time. AA's Bluetooth link coexists with the BLE adapter connection; AA takes over the phone's Wi-Fi, so the page must load over mobile data.
- The web app shows on the phone screen only. Android Auto will not display web apps (it needs a native Car App Library app in an approved category).

## Key constraints
- **Web Bluetooth** (Chrome Android) only supports BLE, not Classic SPP.
- It needs a **secure context**: HTTPS or localhost. A plain `http://<LAN IP>` won't work. Plan: host on HTTPS (Netlify Drop, GitHub Pages, or Azure Static Web Apps).
- `requestDevice()` needs a user gesture. Only services listed in `optionalServices` are visible.

## Current state (`obd-dash.html`, single file, no build step)
- Connects with `acceptAllDevices` and the candidate services `0xFFF0`, `0xFFE0`, `0x18F0`, `e7810a71-73ae-499d-8c15-faa9aef0c3f2`. It picks the first service that has both a notify and a write characteristic.
- Command queue is serialised. Responses are buffered until the `>` prompt. Writes are chunked to 20 bytes.
- Init sequence: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP0`, then `0100` (protocol search) and `ATDPN`. `ATSP6` (CAN 11-bit 500k) should work on the LC200 and is faster than auto-detect.
- Polls Mode 01 PIDs 0C, 0D, 05, 0F, 04, 0B, plus `ATRV` for voltage. Skips a PID after 3 failed reads.
- Screen Wake Lock, plus CSV download of the logged rows.
- Auto-connect: on load, `navigator.bluetooth.getDevices()` returns adapters picked before; it reconnects to the last one (id in localStorage) without the picker, and retries every 5 s after an unexpected drop until Disconnect is tapped.
- **Not yet tested on the car.**

## Next steps / ideas
1. Deploy over HTTPS and test in the car. If the log shows "no usable characteristic", add the adapter's service UUID.
2. Make it an installable PWA (manifest + service worker).
3. Toyota-specific Mode 21/22 PIDs (e.g. ATF temp). These are community-documented, so expect trial and error.
4. Optional: stream readings to a backend (Azure) for trip history.
