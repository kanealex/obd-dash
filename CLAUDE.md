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
- After init it reads the supported-PID bitmaps (0100/0120/0140…), then fuel type (51), OBD standard (1C), VIN (0902), and status/readiness (0101).
- `PIDS` holds about 35 standard Mode 01 gauges, each tagged with a group (`GROUPS`). `fast` gauges are polled every cycle and the rest two per cycle in rotation. A PID is skipped after 3 failed reads. `DERIVED` adds boost (MAP − baro), instant economy (5E fuel rate, or MAF at stoichiometric for petrol only) and battery voltage (`ATRV`).
- Tabs: Live (speed/RPM dials plus grouped tiles with 2-minute sparklines; tap one for a 1/5/15-minute chart), Trip (distance, time, averages, fuel, speed chart), Codes (Mode 03/07/0A with descriptions, per-code Hide, Clear all via Mode 04), Vehicle (VIN, protocol, readiness monitors), Settings (metric/imperial, refresh rate, gauge picker). Settings live in localStorage.
- `DTC_TEXT` has about 520 generic SAE J2012 descriptions. Manufacturer codes fall back to a category from `dtcFallback()`. OBD-II has no per-code clear, so Hide is UI-only.
- Multi-frame (ISO-TP `0:`/`1:`) responses are joined in `messages()`. Mode 03 parsing assumes a count byte on CAN protocols (6–C).
- Screen Wake Lock, plus CSV download of the logged rows (metric units).
- Auto-connect: on load, `navigator.bluetooth.getDevices()` returns adapters picked before; it reconnects to the last one (id in localStorage) without the picker, and retries every 5 s after an unexpected drop until Disconnect is tapped.
- **Not yet tested on the car.**

## Next steps / ideas
1. Deploy over HTTPS and test in the car. If the log shows "no usable characteristic", add the adapter's service UUID.
2. Make it an installable PWA (manifest + service worker).
3. Toyota-specific Mode 21/22 PIDs (e.g. ATF temp). These are community-documented, so expect trial and error.
4. Optional: stream readings to a backend (Azure) for trip history.
