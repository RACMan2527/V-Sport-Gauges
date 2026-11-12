# V-Sport Gauges — User Guide

**For V-Sport Gauges 3.6.4** · [Back to README](../README.md) · [Development history](../README.md#the-development-of-v-sport-gauges)

This is the **how-to guide** for the features added to the original AA Torque app. It covers the Android Auto display and the settings configured on the Android phone. The diagrams are **recreated explanatory schematics**, not photographs or pixel-perfect screenshots. No Cadillac logo is drawn inside the gauges.

## 1. Understand the round gauges

![Schematic round torque gauge with peak markers](images/gauge-examples.svg)

A round gauge displays its configured Torque Pro PID, label, units, scale, live numeric value and animated needle. Its tick marks are rendered by the app, not baked into a background photograph.

**Example:** the gauge reads **280 lb-ft**. The **red daily MAX triangle** points to **300 lb-ft**, about 30% lower than a 430 lb-ft example: the triangle **moves counterclockwise** on the same 0–500 scale; its physical size does not change. The **white triangle** marks a saved **455 lb-ft all-time maximum**.

| Indicator | Meaning | Persistence |
| --- | --- | --- |
| **White triangle** | Highest saved all-time reading for the selected PID | Retained across sessions and restarts |
| **Theme-colored triangle** (red in the example) | **Daily maximum** when the round gauge's marker mode is **MAX** | New local calendar day starts a fresh daily record |
| **Cyan triangle** | **Daily minimum** when marker mode is **MIN** | New local calendar day starts a fresh daily record; RPM qualification required |
| **`PEAK: 455`** | Numeric **all-time** maximum, not the live value or daily record | Appears briefly on load; persists after a new all-time record until the gauge is reinitialized |

**Important:** A round gauge can show **MAX, MIN, or BOTH** daily markers. The diagram illustrates both colored triangles; the white all-time marker is independent.

### Configure a round gauge

1. On the Android phone, open **V-Sport Gauges → Settings → Dashboards**.
2. Open the desired dashboard and select the **left, center or right round gauge**.
3. Select the **PID**. Configure **Enabled**, **Show label**, **Label**, **Unit**, **Minimum**, **Maximum**, **Whole numbers**, and **Ticks** as needed.
4. Set **High/low markers** (`maxMarksActive`) to **Off**, **Max**, **Min**, or **Both**. This controls the colored triangle. The marker color is theme-dependent for MAX; MIN is cyan.
5. Set **High/low values** (`maxValuesActive`) independently if you want the separate numeric high/low readout. This is **not** the `PEAK:` all-time label.
6. Enable **Show all-time peak marker** to display the **white triangle** when a saved peak exists.

These **high/low selectors are available for round gauges**. They are intentionally hidden in the **horizontal-gauge** settings, where daily MAX is handled separately.

### Saved all-time peak and the `PEAK:` label

- All-time values are saved against the complete Torque PID identifier and can be shared by gauges using that PID.
- **Show all-time peak marker** controls visibility of the **white triangle**, not whether the app records a peak.
- **All-time peak value** displays the saved value for the PID and lets you **manually enter a replacement**. Entering **0** stores a zero-valued peak (it does **not** delete the saved record). A subsequent higher valid reading can then establish a new maximum.
- When a saved peak is loaded, **`PEAK: <value>`** appears for approximately **three seconds**.
- When a **new all-time maximum** is reached while the all-time marker is enabled, the `PEAK:` label remains visible until the gauge is reinitialized. It is not a continuously displayed live measurement.

### Daily minimum startup protection

A candidate daily minimum is not accepted immediately. The app checks that **RPM exceeds 300**, waits **five seconds**, and checks **RPM above 300** again before recording that candidate. It does **not** monitor RPM continuously between the checks. This avoids accepting an unqualified low reading during engine startup.

Daily records are scoped to the **local calendar date** and the PID/conversion. They do not replace the saved all-time maximum.

### Daily record notification

Daily MIN/MAX records continue updating with every accepted improvement. The round gauge's unit label blinks for **3 seconds** (half a second visible, half a second hidden) only when the record improves by at least **5% of that gauge's configured range** relative to the last notification baseline, and at least **60 seconds** have passed since that PID/direction last blinked. The first observed record establishes a baseline without blinking. Improvements during cooldown are recorded but do not queue a later notification; a subsequent qualifying record change is needed. MIN and MAX are gated independently, and dashboards sharing a PID/conversion share notification state during the app session. This notification does not alter the live numeric value, needle, daily triangles, or all-time `PEAK:` label.

## 2. Horizontal gauge — one per dashboard

![Schematic horizontal engine-load gauge](images/horizontal-gauge.svg)

The horizontal gauge has its **own configuration on each dashboard**, rather than a global horizontal-gauge setting.

1. On the phone, open **Settings → Dashboards → [dashboard] → Horizontal Gauge**.
2. Select its PID, label, unit, minimum and maximum, and choose whether to show the label, whole numbers and tick marks.
3. Use **Show all-time peak marker** for the **white all-time triangle** on the bar.
4. The bar tracks a **daily MAX** marker independently of the round-gauge MIN/MAX choice. The filled portion follows the **current reading**, not the peak.
5. The current numeric reading displays the configured unit **without an added space** (for example, `68%`).

The bar's appearance, tick marks and colored peak marker are theme/rendering dependent. The diagram is a functional explanation, not a screenshot. The horizontal settings intentionally do **not** expose round-gauge MIN/MAX selectors.

## 3. Choose the Cadillac appearance

On the Android phone, open **V-Sport Gauges → Settings → Appearance**.

- **Theme → Cadillac V-Sport** selects the new Cadillac-named theme. Its gauge appearance currently inherits the **Audi TT** theme unchanged; the original Audi TT selection remains available.
- **Font → Cadillac Escalade** selects the same underlying **Volkswagen Thesis** typeface under a separate Cadillac-named option. The original Volkswagen Thesis option remains available.
- The **V-Series logo in the dashboard header is visible only with the Cadillac V-Sport theme**. Changing the font alone does not show or hide the logo.
- Theme and font choices are saved in the app's existing settings and are included in normal settings export/import. An imported configuration restores its saved selections.

The Cadillac V-Sport theme does not place a logo inside the round gauges. Its header logo can be held for three seconds to open the gauge-refresh slider. With any other theme, the logo is hidden and that logo-based shortcut is unavailable; the refresh interval remains configurable in phone settings.

## 4. Adjust the gauges live on Android Auto

The two three-second press-and-hold gestures change **different** behaviors. The V-Series logo gesture is available **only with the Cadillac V-Sport theme**, because other themes hide the logo. Both sliders operate over **50–500 ms** and are also represented in the phone's settings.

| Press and hold for 3 seconds | On-screen panel | What it changes | Initial default |
| --- | --- | --- | --- |
| **V-Series logo in the header** | **Gauge Refresh Interval** | Animated needle transition duration; **not** ECU polling | **300 ms** |
| **Clock in the header** | **PID Polling Interval** | How frequently the app requests diagnostic data from Torque/ECU | **200 ms** |

**Using the logo slider:** move the slider to preview the animation timing immediately. Press **SAVE** to persist it, or **CANCEL** (or tap outside) to restore the value from before the panel opened.

**Using the clock slider:** moving it immediately changes and reschedules polling; the value is written to preferences as you move it. Press **SAVE** to retain the value or **CANCEL** (or tap outside) to restore the starting value. Leaving the dashboard also closes an open slider and restores its initial value.

**Practical distinction:** a smoother-looking needle does not prove the ECU is being polled more often. Lower polling intervals increase diagnostic request frequency and may not be sustainable with every adapter and PID set.

The earlier **automatic polling calibration and Calibration Results screen were removed**; do not look for them in the current release.

### Enlarge the round gauges on Android Auto

Press and hold the **center of the left or middle round gauge for 3 seconds** to change the gauge size. There are three mutually exclusive modes: **all normal**, **center enlarged**, and **all enlarged**. The selected mode is saved and restored after restarting the app.

| Current mode | Hold left gauge (3 seconds) | Hold middle gauge (3 seconds) |
| --- | --- | --- |
| All normal (100%) | All enlarged (110%) | Center enlarged |
| Center enlarged | All enlarged (110%) | All normal (100%) |
| All enlarged (110%) | All normal (100%) | All normal (100%) |

The whole round gauge is scaled, including its needle, ticks, numeric reading, daily and all-time peak triangles, and temporary `PEAK:` text. The horizontal gauge does not scale. The round gauges and four smaller display fields have also been moved 10dp upward together, while the horizontal gauge retains its position.


## 5. Change dashboards and views

- **Swipe horizontally** across the dashboard to switch between configured dashboards.
- **Rotary/directional inputs** can also change dashboards: left/up and right/down navigate in opposite directions.
- **Vertical swipe** or the supported **center/select button** toggles the alternate chart view.
- Dashboard titles, round gauges, four smaller display fields and horizontal gauge are configured for each dashboard on the phone.
- The **clock and direction** remain part of the dashboard header while ECU data is being connected. The **V-Series logo** also remains visible during connection when Cadillac V-Sport is selected; it is hidden for other themes. A connection-state/title change should not require the entire layout to move.

The precise vehicle rotary mappings can vary by Android Auto receiver; the list above describes the events handled by the app code.

## 6. Clock, travel direction and startup

- The **clock** comes from the phone, not a Torque Pro PID.
- **Travel direction** is derived from phone GPS movement; when a fresh direction is unavailable, the last known direction is retained.
- Engine data requires **Torque Pro**, a working OBD connection, and the requested PID. The gauges may wait for ECU data while the header remains visible.
- The app considers the ECU **connected** after receiving a qualifying nonzero reading; a zero-only stream may still display as waiting. That status rule does not mean every legitimate zero value is invalid.

## 7. Data quality, custom PIDs and calculated power

- V-Sport Gauges rejects **non-finite readings**, invalid converted values, certain shutdown sentinel values and readings **above twice the configured display maximum** before they update the gauge or peak records.
- Negative readings are **allowed** for PIDs that legitimately produce them. The visual gauge may clamp a reading to the dial minimum without changing the underlying value.
- This is a **plausibility filter**, not a guarantee that every ECU/adapter error is detected.
- Custom Torque Pro PIDs and formulas can supply calculated **horsepower and torque**. These are **ECU-based estimates**, not dynamometer measurements. Verify the PID definition, units and formula for your vehicle.

## 8. Troubleshooting

| Symptom | Check |
| --- | --- |
| Gauge stays empty or waiting | Torque Pro connection, adapter, ECU status, PID availability and whether any nonzero value has arrived |
| White peak triangle missing | The gauge's **Show all-time peak marker** option and whether a saved value exists |
| `PEAK:` number disappears after startup | Expected: a loaded all-time peak is shown for approximately three seconds |
| Red/cyan marker absent | Round-gauge **High/low markers** mode; daily record may not yet exist |
| Daily MIN not updating | Two qualifying RPM readings **above 300**, five seconds apart |
| Peak marker unexpectedly at end of scale | Check PID formula, units, saved peak and configured maximum |
| Gauge freezes after a spike | Check PID formula, display maximum and the implausible-reading filter |
| Polling slider changes response time | This is expected: polling actually reschedules diagnostic requests |
| Needle slider changes animation but not PID speed | This is expected: animation and polling are independent |

## Connection indicators

During startup, **Connecting to ECU** appears with a small cyan rotating spinner when Torque is available but ECU communication is not yet established. The spinner and message are centered together and disappear when the existing connection state reports connected.

The amber **Broken Link** symbol appears beside the time and direction display if the app remains in **Connecting to Torque** for five seconds. It clears immediately when Torque connects; it stays hidden during normal **Connecting to ECU** startup. After ECU connection, three consecutive unsuccessful Torque PID requests for an active gauge cause the indicator to appear; a successful response for that gauge clears its failure streak. The symbol disappears when no active gauge has a persistent request failure.

The indicator reports detectable Torque service/PID request failures. It does not prove that Torque's adapter or ECU is connected, and valid zero readings alone do not trigger the warning. The existing **Connecting to ECU** text remains a startup-only message.

## 9. Feature status and scope

This guide describes features **verified in the current `master` source**, including `DashboardFragment.kt`, `TorqueGauge.kt`, `TorqueLoadBar.kt`, `TorqueRefresher.kt`, `TorqueData.kt`, `DailyPeakStore.kt`, `AllTimePeakStore.kt`, `SettingsPIDFragment.kt` and the settings XML. It is **not** a claim of fresh in-vehicle testing.

The original AA Torque features (themes, alarms, formulas, dashboard fields and chart views) remain available subject to their own configuration. For the history of additions, revisions and removed experiments, see the [development history](../README.md#the-development-of-v-sport-gauges).


### Exporting and importing PID polling settings

Starting in **3.5.17**, a settings export includes the manually selected **PID Polling Interval** as well as gauge animation settings. Importing the file restores the saved polling interval (for example, 125 ms) and applies it to active polling. Older exports that do not contain this setting restore the 200 ms default.


After importing settings, the PID polling slider immediately reflects the restored value, even when the settings screen was already open.
