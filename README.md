# V-Sport Gauges

### Cadillac-Inspired Performance Instrumentation for Android Auto

**From a few personal modifications to a fully customized vehicle instrumentation experience.**

## My V-Sport Gauges Setup

![My V-Sport Gauges setup running on the Cadillac Escalade factory OLED display](V-Sport%20Gauges.jpg)

*My V-Sport Gauges setup in my 2023 Cadillac Escalade Sport Platinum, displaying live vehicle information through Android Auto on the factory OLED screen.*

V-Sport Gauges is my personal evolution of the open-source AA Torque application. What began as a few manual source-code modifications to improve the appearance of its gauges developed into a much larger project involving custom instrumentation, smoother performance, advanced peak monitoring, diagnostic data validation, and a dashboard tailored to my Cadillac Escalade.

The project has evolved through repeated experimentation, real-world testing, and collaboration with ChatGPT. I established the requirements, made the initial code changes, tested the application in my vehicle, and directed subsequent development as ChatGPT increasingly assisted with implementation, debugging, and GitHub builds.

The result is an Android Auto instrumentation application designed to complement the vehicle's factory display while retaining the flexibility of Torque Pro.

---

## Origins — Acknowledging the Developers Who Made This Possible

**None of the V-Sport Gauges customizations would have been possible without the original work of Kevin Agronick and the developers whose innovations came before his.**

The primary foundation of this project is [Kevin Agronick's AA Torque](https://github.com/agronick/aa-torque).

Kevin's application made it possible to bring customizable Torque Pro diagnostic information into Android Auto. Its support for custom PIDs, multiple dashboards, themes, expressions, rotary controls, and configuration options provided the platform on which my work was built.

The software's lineage extends further:

- **Martoreto** — Creator of aa-stats, an early Android Auto vehicle-monitoring application.
- **Chillout / jilleb** — Developer of [Performance Monitor](https://github.com/jilleb/mqb-pm), based on aa-stats.
- **Kevin Agronick** — Developer of [AA Torque](https://github.com/agronick/aa-torque), which expanded the concept into a flexible, customizable Torque Pro instrumentation application.

I am grateful to these developers for making their work available to others.

**V-Sport Gauges is a customization and extension of their work, not a replacement for it.**

---

# Before V-Sport Gauges — The CTS-V That Started It All

### A long-standing passion for Cadillac performance instrumentation

My interest in custom automotive instrumentation began long before V-Sport Gauges and my 2023 Cadillac Escalade. As an enthusiast of Cadillac's V-Series performance vehicles, I wanted to see what the engine was actually doing, in real time, on a display that looked like it belonged in the car.

Dodge's SRT and Hellcat Performance Pages offered an appealing example: factory displays devoted to horsepower, torque, operating parameters, and performance measurements. I wanted that same level of visibility in my Cadillacs—not an aftermarket gauge pod, but instrumentation integrated into the factory display.

## My Cadillac CTS-V Coupe — The Original Custom Dashboard

![Custom DashCommand performance gauges in my Cadillac CTS-V Coupe](CTS-V%20Coupe%20Gauges.jpg)

*My original CTS-V Coupe dashboard: custom DashCommand instrumentation projected onto the factory retractable display using a jailbroken iPhone 5.*

My CTS-V Coupe featured a motorized, retractable center-console display. I modified the vehicle's video system to intercept the original display signal and show the output of a dedicated **jailbroken iPhone 5** instead.

The phone ran Palmer Performance Engineering's **DashCommand**, connected to the vehicle through a **Wi-Fi OBD-II adapter**. Rather than adding another visible screen, I made the Cadillac's existing display serve as a custom performance instrument panel.

### Making the system operate automatically

A permanently installed iPhone had to behave like part of the car, not like a phone that required attention every time I drove.

I used a combination of hardware relays and jailbreak-based triggers to coordinate the system: wake the phone when the vehicle started, establish the Wi-Fi connection to the OBD adapter, display the gauges, and return the phone to a low-power state after shutdown.

This required integrating vehicle power behavior, hardware switching, mobile automation, wireless diagnostics, and the factory display. The objective was a seamless, factory-like experience.

### Designing my own DashCommand gauges

Getting the display working was only the beginning.

One of my biggest inspirations was the **Nissan GT-R's distinctive multifunction performance display**, which presented detailed engine and vehicle operating information through purpose-built digital gauges.

I admired how the GT-R combined technical information with a cockpit-like presentation, making engine performance and vehicle dynamics part of the driving experience.

Using Palmer Performance Engineering's **DashXL dashboard editor**, I set out to bring that same philosophy to my Cadillac CTS-V Coupe.

I designed and refined dozens of custom dashboard versions, experimenting with circular gauges, layouts, graphics, scales, colors, and the selection of engine parameters.

I wasn't satisfied with generic OBD gauges. I wanted something that combined the GT-R's performance-focused instrumentation with the character of Cadillac's V-Series.

I used the system extensively while running the CTS-V at the track, where monitoring engine operating conditions and performance-related information was especially valuable.

Those experiences established an approach that would reappear years later in V-Sport Gauges: **select the information that matters, make it visually engaging, and integrate it naturally into the vehicle.**

### The Escalade — A new vehicle, the same ambition

Years later, my 2023 Cadillac Escalade Sport Platinum presented a new opportunity. Its factory OLED infotainment system offered a remarkable display platform, and Cadillac provided built-in readings such as coolant, oil, and transmission temperatures.

But I still missed something from my CTS-V project: a dedicated view of **real-time engine horsepower and torque**, similar in spirit to the SRT Performance Pages.

I knew what I wanted to see. The challenge was finding a practical way to display it without compromising the Escalade's factory appearance.

### From hardware modification to Android Auto

The technical approach had changed dramatically. In the CTS-V, I intercepted the factory video signal and used a jailbroken iPhone, hardware relays, and Wi-Fi diagnostics.

I first tried **DashCommand on an Android phone**, intending to mount the phone near the A-pillar to show a few engine parameters, particularly horsepower. I already had a camera monitor in that area for use while towing my trailer. But swapping devices and adding another non-factory-looking display were not appealing. I wanted the gauges integrated into the Escalade's infotainment screen instead.

**My next attempt was DashCommand on an AI-Box.** The AI-Box used the vehicle's CarPlay connection to display an Android interface, potentially allowing me to reuse the dashboard software I had customized so extensively for the CTS-V. Unfortunately, the AI-Box wasn't robust enough for the job.

I then returned to an old, damaged-screen Android phone I had purchased and repurposed. Instead of adding a separate display, could I use **Android Auto** to project its instrumentation onto the Escalade's factory OLED screen?

DashCommand presented the next obstacle: **its custom dashboards could not be projected through Android Auto**. I needed another OBD-II application and a compatible way to show its data on the vehicle's display.

That search led me first to **Torque Pro** for reading vehicle diagnostics, and then to **Kevin Agronick's open-source AA Torque**, which could display customizable Torque Pro gauges through Android Auto.

Only after finding that software combination did I turn to the deeper question: **could I expose the L87's horsepower and torque rather than settle for conventional temperature and pressure gauges?**

I investigated GM's engine torque-related diagnostic parameters and worked through custom Torque Pro calculations to derive estimates of **crankshaft torque and horsepower**. I also pursued an **L87-specific oil-pressure PID** to obtain a reading better suited to my engine.

Those discoveries gave me the engine information I had wanted to see. Torque Pro and AA Torque gave me a way to bring it onto the Escalade's factory screen—**without intercepting video signals or modifying the display hardware**.

The technologies were different, but the ambition remained the same: **make my Cadillac show me what its engine is doing—with instrumentation worthy of its performance.**

**V-Sport Gauges is not where that interest began. It is the latest evolution of something I started building years earlier.**

---

# The Development of V-Sport Gauges

## Phase 1 — The First Manual Modifications
### October 2026 | Before Version 3.0.0

The project began with a straightforward goal: make AA Torque's instrumentation look more appropriate on the large factory OLED display in my Cadillac Escalade.

I initially made source-code modifications myself, working through the changes with guidance from ChatGPT.

### Refining the round gauges

The first recorded change, in October 2026, modified `TorqueGauge.kt` to reduce the gauge tick count from nine to five.

Later that month, I experimented further by reducing the count from five to three.

These changes began a longer process of experimenting with tick spacing, gauge readability, labels, needle presentation, and the visual balance of the dashboard.

The objective was not simply to fit more information on the screen. I wanted a cleaner, more purposeful instrument display.

### Establishing the V-Sport identity

As the work continued, I was modifying application strings to reflect the V-Sport customization.

Later in October, further changes to the application identity included the name **AA Torque V-Sport**.

The project was beginning to develop an identity distinct from the original application, although it remained entirely dependent on AA Torque's underlying architecture.

### Introducing configurable refresh behavior

This period also marked an important step beyond appearance.

The original `TorqueRefresher.kt` used a fixed 300 ms refresh interval. I began modifying this behavior to allow configuration rather than relying on a permanently fixed value.

This work involved changes to the refresher, settings interface, preferences, and associated resources.

It became the starting point for a much larger investigation into gauge responsiveness and diagnostic polling performance.

### What this phase established

These early manual changes defined the principles that would guide the project:

- Customize the instrumentation for the Cadillac display.
- Improve readability without unnecessary visual clutter.
- Make important behavior configurable.
- Preserve AA Torque's existing functionality.
- Test changes in the actual vehicle rather than relying solely on simulations.

At this stage, the project was still primarily a collection of personal modifications to AA Torque.

That was about to change.

---

## Phase 2 — Establishing V-Sport Gauges
### October 2026 | Versions 3.0–3.1

As my manual changes expanded, the work became a structured GitHub development project. I continued defining requirements and testing in the Escalade, while ChatGPT increasingly assisted with code changes, troubleshooting, and builds.

The interface took shape as a Cadillac-inspired instrument panel with configurable round gauges and a compact horizontal engine-load bar. Work included themed colors, typography, tick labels, bar height, rounded ends, and precise positioning. The horizontal bar's color and text contrast were refined in the 3.0.1 work, and **3.1.0 was released in October 2026**.

Versioned builds and separate test identities helped isolate experiments. The `master` branch became the production release line.

---

## Phase 3 — Persistent Peaks and Gauge Refinements
### November 2026 | Versions 3.2.0–3.2.3

**3.2.0:** I introduced persistent all-time peak tracking for round gauges. A white triangular marker identified the recorded maximum, with storage keyed to the selected PID so values survived dashboard switches and application restarts. Recording could remain active even when the marker was hidden. This work also included restoring existing records and allowing peak values to be edited or reset in settings.

The dashboard continued to evolve visually, including AudiTT tick-label sizing and spacing and adjustments to the horizontal gauge's ticks and position.

**3.2.1:** The release incorporated further horizontal gauge tick placement, including stopping major ticks at the bar midpoint.

**3.2.2–3.2.3:** Peak readouts became more useful. Saved peaks could appear temporarily when a gauge loaded, and a new all-time maximum could trigger an on-screen label. The label was raised and enlarged for readability.

The development of smooth needle movement also distinguished **PID polling** (how often diagnostic values are obtained) from **gauge animation** (how the needle moves between values). A 200 ms polling target was used during this work, while animation timing could be adjusted independently. This distinction became important in the subsequent 3.3 experiments.

---

## Phase 4 — Calibration Experiments and Live Controls
### November 2026 | Versions 3.3.0–3.3.8

**3.3.0 — Automatic calibration:** An experimental first-connection process measured diagnostic polling behavior and saved a recommended interval, rather than assuming every phone, adapter, and vehicle could support the same rate.

**3.3.1 — Calibration Results:** A dedicated on-screen results panel reported calibration information and could be dismissed using the vehicle's rotary controller.

**3.3.2–3.3.3 — Live gauge adjustment:** Long-pressing the V-Series logo was developed as a way to change gauge refresh or animation behavior from an overlay without leaving the dashboard.

**3.3.4–3.3.6 — Calibration refinements:** The experimental calibration logic was extended with ECU-qualified samples, dashboard-change handling, timing improvements, and more detailed results.

**3.3.7–3.3.8 — Change of direction:** Real-world testing led me to **remove automatic polling calibration**, its results screen, and the CAL indicator. In their place, development introduced a **persistent, manually adjustable PID polling interval**, accessible from settings and a clock long-press control. The logo-based gauge control was retained. This is an important distinction: the earlier calibration feature was part of the development history, **not a feature that remained in the later releases**.

These iterations reinforced the value of testing actual ECU communication and keeping polling control separate from the visual behavior of the gauges.

---

## Phase 5 — Peak Visibility, Clock, Compass, and Header
### November 2026 | Versions 3.3.9–3.3.14

**3.3.9–3.3.10:** Peak-label behavior was refined so newly achieved all-time peaks could remain visible until the dashboard changed, with a subsequent compilation fix.

**3.3.11:** The dashboard gained a compact **clock and GPS-derived travel direction**. Unlike engine readings, these values came from the phone rather than Torque Pro. The required location-permission handling was also added.

**3.3.12–3.3.14:** Release workflow and settings refinements continued, including restoring the simulated ECU data toggle and adjusting the V-Series logo's position.

The clock, compass, and Cadillac-inspired branding made the display feel more like an integrated vehicle instrument panel rather than a generic diagnostic application.

---

## Phase 6 — Per-Dashboard Horizontal Gauges
### November 2026 | Versions 3.4.0–3.4.4

**3.4.0:** The horizontal gauge moved into **individual dashboard configuration**. Each dashboard could select its own horizontal PID and reuse relevant round-gauge display options. The renderer gained support for dashboard-specific label, numeric value, tick, and peak-display settings.

**3.4.1:** The Tick Values option was refined to hide numeric tick labels without removing the major and minor tick marks.

**3.4.2–3.4.3:** Configured units were added to the horizontal value, eventually without an intervening space. Obsolete global horizontal-gauge controls, preferences, and service lifecycle handling were removed—not merely hidden.

**3.4.4:** The horizontal value font was matched to the 18sp label font.

This sequence completed the transition from a globally configured bar to a gauge that could be tailored independently for each dashboard.

---

## Phase 7 — Direction Persistence and Horizontal Peak Markers
### November 2026 | Versions 3.4.5–3.4.6

**3.4.5:** The last known GPS travel direction was persisted and restored, so the compass could continue showing the previous heading while a fresh direction was unavailable.

**3.4.6:** The horizontal gauge gained distinct peak indicators: a **colored session marker** and a **white persistent all-time marker**. This let the bar communicate current and previously achieved values without permanently displaying additional numbers.

---

## Phase 8 — Diagnostic Validation and Stable Startup Layout
### November 2026 | Refinements after 3.4.6, before 3.5.0

Testing the Escalade revealed that shutdown and ECU communication changes could produce implausibly large PID values, corrupting gauge readings or recorded peaks.

Validation was tightened to reject non-finite and clearly out-of-range values, including readings outside **twice the configured gauge display limits**. Subsequent corrections preserved legitimate negative readings and transitions toward zero during deceleration, rather than allowing the gauges to freeze above zero.

The dashboard header was also stabilized during ECU connection. The clock, direction, and V-Series branding could remain visible while waiting for diagnostic data, without the previous shift in layout. Clock and direction text were enlarged, and the logo was positioned and clipped to the header boundary.

These changes were aimed at making the instrumentation both more trustworthy and more visually consistent in everyday use.

---

## Phase 9 — Daily Peak Tracking
### November 2026 | Versions 3.5.0–3.5.2

**3.5.0:** The next development series began, preserving the working foreground polling baseline.

**3.5.1:** Date-scoped persistent daily extrema were introduced for gauges, allowing a day's maximum and minimum to be tracked separately from all-time records. Round and horizontal gauge displays were connected to the stored daily values.

**3.5.2:** Day-rollover behavior was corrected so daily markers and stored readings could reset appropriately when the date changed, including the horizontal gauge's daily maximum.

This extended the original all-time peak idea into a more useful record of **today's driving**, without indefinitely carrying daily records forward.

---

## Phase 10 — RPM-Qualified Daily Minimums
### November 2026 | Version 3.5.3

Daily minimum recording raised a specific concern: oil pressure can be low while an engine is starting, and a transient startup reading should not automatically become the day's minimum.

**3.5.3** introduced a deliberately simple qualification: check that engine RPM is above **300**, wait **five seconds**, and confirm RPM is still above **300** before accepting a daily minimum. The implementation does not attempt to prove continuous engine operation between those two checks.

Further commits separated daily maximum and minimum persistence, refined the cyan MIN triangle, avoided duplicate writes from the horizontal gauge, and preserved the horizontal bar's daily maximum tracking independently of round-gauge MIN/MAX/OFF selection.

By this point, the application combined Cadillac-inspired instrumentation, independent polling and animation controls, persistent all-time and daily peak tracking, diagnostic plausibility checks, clock and compass information, and per-dashboard horizontal gauges.

**V-Sport Gauges remains an ongoing project, guided by testing in the Escalade rather than feature additions alone.**

### Subsequent releases

New versions will be documented **below this section in release order**, with their dates, version numbers, significant changes, and any important corrections or removed experiments. This keeps the history chronological as the project continues.

---

# My Development and Testing Environment

| Component | Configuration |
|---|---|
| Vehicle | 2023 Cadillac Escalade Sport Platinum |
| Engine | GM 6.2L L87 V8 |
| Factory display | Cadillac OLED |
| Android phone | Samsung Galaxy S10 |
| Operating system | Android 12 |
| Projection interface | Android Auto |
| Diagnostic application | Torque Pro |
| OBD-II adapter | Vgate vLinker BM Bluetooth |
| Development repository | RACMan2527/aa-torque |

The application may work with other vehicles and configurations, although individual PIDs, performance characteristics, and Android Auto compatibility will vary.

---

# Installation and Configuration

V-Sport Gauges builds upon AA Torque and requires Torque Pro and a compatible OBD-II interface for live diagnostic data.

1. Install and configure [Torque Pro](https://play.google.com/store/apps/details?id=org.prowl.torque).
2. Pair a compatible Bluetooth OBD-II adapter and verify communication with the vehicle.
3. Download the appropriate APK from [V-Sport Gauges Releases](https://github.com/RACMan2527/aa-torque/releases).
4. Install the application and configure Android Auto permissions as required by the device.
5. Configure the desired dashboards, gauges, PIDs, and display settings.
6. Verify the displayed data against known vehicle readings before relying on calculated parameters.

Android Auto installation requirements vary by Android version and device. The [original AA Torque documentation](https://github.com/agronick/aa-torque) provides additional background on installation and configuration.

---

# Development Philosophy

This project began because I wanted something more personalized than the original AA Torque display.

What started as adjusting a few gauge tick marks became an extended process of improving the appearance, performance, accuracy, and usability of the application.

My approach has remained consistent:

**Make the gauges look right. Make them respond smoothly. Make the data meaningful. Keep the implementation as simple and reliable as practical.**

Many of the most satisfying improvements have involved small details: moving a gauge a few pixels, correcting a peak indicator, preserving a compass heading, or preventing a bad diagnostic reading from corrupting a record.

Those details collectively make the dashboard feel more like a cohesive instrument cluster.

The development continues through a collaboration in which I define and evaluate the requirements, and ChatGPT assists with implementation and technical problem-solving.

---

# Credits, Licensing & Disclaimer

**Original software foundation:** [Kevin Agronick — AA Torque](https://github.com/agronick/aa-torque)

**Earlier development lineage:** [Chillout / jilleb — Performance Monitor](https://github.com/jilleb/mqb-pm), based on Martoreto's aa-stats.

**V-Sport Gauges customization:** RACMan2527, with development assistance from ChatGPT.

This project retains its dependency on the original developers' contributions and is subject to applicable upstream open-source licensing and attribution requirements.

V-Sport Gauges is an independent personal customization project. It is not affiliated with, sponsored by, or endorsed by Cadillac, General Motors, Google, or the upstream developers.

Vehicle diagnostic data and calculated performance values are provided for informational purposes. Their accuracy depends on the vehicle, ECU, OBD-II adapter, diagnostic definitions, and configured calculations.

---

**V-Sport Gauges**

*Built upon the innovations of others. Developed through experimentation. Refined through real-world driving.*
