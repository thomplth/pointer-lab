# Pointer Lab

A tiny, self-contained tool that measures **how many pointer (stylus / mouse / touch) samples per
second your browser actually delivers to JavaScript** — and whether the high-frequency input stream
is reachable from web code at all.

Modern pointing devices sample fast: styluses commonly run 240–1000 Hz, gaming mice 500–8000 Hz. But
browsers dispatch `pointermove` **once per animation frame** (≈ display refresh, so ~60 or ~120 Hz),
which is far below the hardware rate. Two web APIs are meant to recover the lost samples:

- **`getCoalescedEvents()`** — each `pointermove` can carry the sub-samples that arrived since the last
  frame, batched together. If input is *coalesced losslessly*, summing these gives you the full rate.
- **`pointerrawupdate`** — a separate event spec'd to fire *before* frame-aligned coalescing, exposing
  the raw high-rate stream directly.

Whether either actually delivers more than one sample per frame **varies by browser, engine fork, and
device**. Pointer Lab measures all three streams live so you can see, for your exact target, whether
high-frequency input is recoverable in the browser or capped upstream of your JavaScript.

It's useful for anyone building **drawing, handwriting, signature, or high-precision-input** web apps
who needs to know the real input sample rate on a given browser/device.

---

## Two ways to run it

The measurement page is a single self-contained `index.html` (vanilla JS, no build step). It runs the
same way everywhere; the only requirement is a **secure context** (`isSecureContext === true`), which
`pointerrawupdate` needs.

### 1. Hosted web page — any browser, desktop or mobile

Open the deployed URL and draw. HTTPS is a secure context, so all three streams are measurable. This is
the simplest path for desktop browsers and mobile browsers alike.

### 2. Android on-device harness — A/B specific browser engines

A minimal Android app (`app/`) serves the same page from a loopback HTTP server and opens it in a chosen
browser via a **Custom Tab**, so you can compare engines (e.g. Samsung Internet vs Chrome) on the same
device. Loopback (`http://127.0.0.1`) is treated as a secure context by Chromium, so `pointerrawupdate`
works without TLS.

```bash
cd pointer-lab
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.goodnotes.pointerlab/.MainActivity
```

Then tap a browser button and **draw continuously** inside the canvas. Counters update ~2×/sec; use
**Reset** between runs.

Toolchain: Gradle 8.13, AGP 8.11.1, Kotlin 2.2.0, JDK 17, `compileSdk 36` / `minSdk 28` / `targetSdk 35`.

---

## How it works (architecture)

- **`assets/index.html`** — the entire measurement UI + logic: live counters, a drawing canvas, a
  color-coded verdict banner, and a rolling log mirrored to `console.log` (Chromium pipes `console.*`
  to logcat under the `chromium` tag, so on-device runs are capturable over ADB).
- **`LocalServer.kt`** *(Android harness only)* — a [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
  server bound to `127.0.0.1:7799` serving the page from `assets/`. Loopback = secure context, no TLS.
- **`MainActivity.kt`** *(Android harness only)* — starts the server, detects installed browsers, and
  launches the page via `CustomTabsIntent` + `.setPackage(pkg)` to force a specific engine. A Custom Tab
  uses the same Chromium engine + pointer pipeline as a TWA, so it faithfully reflects an installed web
  app's behavior.

---

## Reading the measurements

Draw a continuous stroke. Each card is a per-second rate (or a derived value).

| Card | What it is | What it tells you |
|---|---|---|
| **pointermove** (events/s) | `pointermove` listener invocations per second | Usually ≈ display refresh — the "one event per frame" dispatch rate. |
| **coalesced Σ** (samples/s) | Sum of `getCoalescedEvents().length` across all moves in the second | Total samples the browser is willing to hand you. If ≫ pointermove, the extra samples are recoverable by summing. |
| **coalesced / move** (avg) | `coalesced Σ ÷ pointermove` | **The pivotal number.** ≈ **1.0** → the browser resampled the stream down to display rate before coalescing (lossy — the samples are gone). ≳ **2–4** → lossless coalescing: all hardware samples are present, just batched per frame. |
| **pointerrawupdate** (events/s) | Rate of the `pointerrawupdate` event | The decisive API — spec'd to fire *before* frame-aligned coalescing. If ≫ display refresh, the high-rate stream is reachable in web. If it also ≈ display refresh, the cap is upstream of your JS. |
| **rAF display** (Hz) | `requestAnimationFrame` callbacks/sec | The display refresh — the baseline every other rate is compared against. |
| **pen Hz (coalesced)** | Median inter-timestamp Δt across coalesced samples, snapped to {60,120,240,360,480} | Effective sample rate of the coalesced stream, from timestamps. |
| **pen Hz (rawupdate)** | Same, for the `pointerrawupdate` stream | Effective rate of the raw stream. `n/a` = the event never fired. |
| **predicted** | `getPredictedEvents().length` | Whether the browser itself predicts points (bonus signal). |
| **Δt→Hz histogram** | Buckets each inter-sample gap into 60/120/240/360/480/other | Exposes quantization: all gaps in one bucket = hard-locked to that rate. |
| **isSecureContext / pointerrawupdate supported** (footer) | Capability line | Must read `true` / `supported`, else `pointerrawupdate` is silently unavailable and its measurement is meaningless. |

### The verdict banner

Turns **GREEN** or **RED** based on `max(pointerrawupdate/s, coalesced Σ/s)` vs display refresh:

- 🟢 **RECOVERABLE** — best stream `> 1.8 × display`: meaningfully more than one sample/frame reaches JS.
  The high-rate stream is reachable via `pointerrawupdate` (or by summing `getCoalescedEvents()`).
- 🔴 **CAPPED** — best stream `≈ display` refresh: the browser delivers ~one sample per frame to JS and
  the extra hardware samples are not exposed by any current web API on this browser/device.
- ⚪️ **grey** — not enough samples yet; keep drawing.

---

## Optional ADB cross-checks (Android harness)

On devices where the digitizer runs faster than the display, you can prove the hardware rate
independently and test how the cap behaves:

1. **Secure context** — page footer must show `isSecureContext=true`.
2. **Hardware pointer rate** — while drawing, sample the digitizer's IRQ counter to confirm the device
   itself is sampling faster than the page reports (interrupt name varies by device):
   ```bash
   adb shell "cat /proc/interrupts | grep <digitizer_irq>"   # sample 1s apart; delta ≈ device Hz
   ```
3. **vsync-follow vs fixed ceiling** — pin the panel to a refresh rate and re-draw; if page rates track
   60→120 the cap is vsync-locked, if they stay fixed it's a hard ceiling:
   ```bash
   adb shell cmd display set-user-preferred-display-mode <w> <h> 60  -1 false
   adb shell cmd display set-user-preferred-display-mode <w> <h> 120 -1 false
   ```
4. **Engine A/B** — repeat via the second browser button to isolate one engine's fork from stock Chromium.
5. **Logcat artifact** — capture the page's console mirror: `adb logcat -s chromium:*`.

---

## Example run

Samsung Internet on a 120 Hz Android tablet with a stylus produced:

| Metric | Value |
|---|---|
| pointermove | 120 / s |
| coalesced Σ | 120 / s |
| coalesced / move | 1.00 |
| pointerrawupdate | 120 / s |
| rAF display | 120 Hz |
| isSecureContext | true |

Verdict: 🔴 **CAPPED.** `coalesced/move = 1.00` means the browser resampled to display rate before
coalescing (lossy), and `pointerrawupdate` was pinned to the same 120/s — so on that browser/device the
high-rate stylus stream was **not reachable from web code** by any current API. Run it against your own
target to see whether yours is recoverable (green) or capped (red).

---

## Notes

- No hardcoded secrets, no backend, no network calls — a purely local measurement page.
- The Android app package is `com.goodnotes.pointerlab`; the harness is a diagnostic tool, not a product.
