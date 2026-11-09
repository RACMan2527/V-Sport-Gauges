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
### October 10–28, 2026 | Before Version 3.0.0

The project began with a straightforward goal: make AA Torque's instrumentation look more appropriate on the large factory OLED display in my Cadillac Escalade.

I initially made source-code modifications myself, working through the changes with guidance from ChatGPT.

### Refining the round gauges

The first recorded change, on October 10, modified `TorqueGauge.kt` to reduce the gauge tick count from nine to five.

On October 15, I experimented further by reducing the count from five to three.

These changes began a longer process of experimenting with tick spacing, gauge readability, labels, needle presentation, and the visual balance of the dashboard.

The objective was not simply to fit more information on the screen. I wanted a cleaner, more purposeful instrument display.

### Establishing the V-Sport identity

By October 19, I was modifying application strings to reflect the V-Sport customization.

On October 28, further changes to the application identity included the name **AA Torque V-Sport**.

The project was beginning to develop an identity distinct from the original application, although it remained entirely dependent on AA Torque's underlying architecture.

### Introducing configurable refresh behavior

October 28 also marked an important step beyond appearance.

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

## Phase 2 — From Manual Modifications to V-Sport Gauges
### Late October 2026 | Versions 3.0–3.1

As the modifications became more extensive, development evolved from individual manual edits into a more structured GitHub workflow.

I continued to establish the design requirements, review proposed changes, and test the results. ChatGPT began taking a more active role in implementing the modifications and preparing builds.

### Creating a cohesive dashboard

The interface evolved toward a Cadillac-inspired layout combining multiple round gauges with a horizontal performance gauge.

The horizontal gauge was particularly important because it offered a different way to display rapidly changing information, such as engine load, without consuming another large circular instrument.

Its design went through numerous refinements:

- Compact horizontal presentation
- Rounded bar ends
- Improved tick positioning
- More readable labels and values
- Better use of themed colors
- Adjustments to vertical alignment and spacing

Even small changes of one or two density-independent pixels became meaningful when viewed on the Escalade's large, high-resolution display.

### Developing the visual identity

Work continued on gauge typography, tick labels, themed colors, needle presentation, and dashboard proportions.

The goal was a unified instrument panel rather than a collection of unrelated gauges.

The Cadillac-inspired design gradually became a recognizable characteristic of V-Sport Gauges.

### Establishing release management

The project adopted versioned builds and GitHub-based development.

This made it easier to test changes, retain working versions, and distinguish experimental modifications from production releases.

The `master` branch ultimately became the production release branch, while isolated test builds could be installed alongside the primary application.

---

## Phase 3 — Making the Gauges More Responsive
### Versions 3.2–3.3

Once the dashboard's appearance was taking shape, attention shifted to the behavior of the gauges.

A recurring problem was that gauge movement did not appear as smooth as the live instrumentation in Torque Pro.

### Separating data acquisition from animation

One of the most important technical changes was recognizing that the frequency of diagnostic readings and the visual movement of the needles should not be treated as the same setting.

The development separated two functions:

**PID acquisition** — How frequently the application requests or processes diagnostic data from Torque Pro.

**Gauge animation** — How the displayed needle transitions between received values.

The implementation used a fixed 200 ms sampling target during this development phase, while a separate adjustable animation duration controlled needle movement.

This allowed smoother visual transitions without unnecessarily slowing diagnostic updates.

### Adjustable gauge animation

A configurable animation control was introduced, allowing the responsiveness of the display to be tuned.

The objective was to balance smooth movement against the need to show changing vehicle conditions promptly.

This was especially important for rapidly varying values such as horsepower, torque, and engine load.

### Automatic communication calibration

Version 3.3.0 introduced automatic PID polling calibration.

Instead of assuming every phone, OBD adapter, and vehicle combination could support the same polling interval, the application began measuring communication performance and retaining a calibrated value.

The calibration system was developed to account for the active dashboard and the PIDs being monitored.

### Calibration results display

Version 3.3.1 expanded this feature by adding a dedicated Calibration Results display.

The results could be reviewed directly on the Android Auto screen, with rotary-controller input used to dismiss the panel and return to the normal dashboard.

This turned a background technical process into something that could be observed and evaluated during testing.

### Live adjustment controls

Further development explored adjusting gauge behavior directly from the dashboard, including a long-press interaction with the V-Series logo.

This required additional work on overlays, input handling, and interaction with Android Auto.

The goal was to make tuning possible without repeatedly navigating through conventional settings screens.

---

## Phase 4 — Advanced Peak Monitoring
### Versions 3.2–3.5

A major part of the project became the ability to remember meaningful vehicle performance readings rather than displaying only the current value.

### Persistent all-time peaks

Version 3.2 introduced persistent peak recording for round gauges.

Peak values were associated with the selected PID, allowing a recorded maximum to survive dashboard changes and application restarts.

A white triangular marker provided a visual reference to the recorded maximum.

Peak tracking could continue even when the marker itself was not displayed.

### Temporary peak readouts

The application was further refined to show a peak value temporarily when a dashboard loaded or a new all-time maximum was recorded.

This made peak information available without requiring permanent numeric clutter around the gauges.

### Horizontal gauge peak indicators

The horizontal gauge received its own peak-display capabilities.

Different marker colors distinguished session information from persistent all-time records.

This allowed the horizontal gauge to communicate both the current reading and previously achieved values.

### Daily maximum and minimum tracking

Version 3.5 expanded peak monitoring beyond all-time records.

Daily maximum and minimum values were introduced, with persistent date-scoped storage and rollover handling.

This made it possible to retain meaningful information from a day's driving without carrying those values indefinitely into future days.

### Protecting minimum readings during engine startup

An important issue emerged with minimum values, especially for oil pressure.

A low reading during engine startup should not automatically become the day's recorded minimum.

Version 3.5.3 introduced a specific validation method: the engine RPM must exceed 300, followed by a second qualifying RPM check five seconds later before accepting a minimum.

This was intentionally kept simple, without adding unnecessary logic to monitor continuous engine operation between the checks.

The feature illustrates the development philosophy: address real operating conditions with targeted, understandable changes.

---

## Phase 5 — Improving Diagnostic Reliability

A sophisticated dashboard is only useful when the displayed data can be trusted.

Testing revealed that ECU disconnection, engine shutdown, and certain diagnostic responses could produce abnormal values.

Some readings were far outside plausible operating ranges and could corrupt recorded peaks.

### Rejecting invalid values

Data validation was introduced to prevent unsuitable readings from reaching the gauges and peak-recording logic.

This work included handling non-finite values and rejecting readings that exceeded defined plausibility limits.

A later change rejected readings outside twice the configured display limits before they could update peak records.

### Preserving legitimate zero and negative readings

Filtering introduced another challenge.

During deceleration, some calculated engine parameters legitimately approach zero or become negative.

An overly aggressive filter could cause a gauge to freeze above zero rather than follow the actual reading.

The validation logic was revised to preserve legitimate deceleration behavior while still rejecting abnormal data.

### Engine shutdown protection

Testing also exposed occasional extreme values when the Escalade was turned off and diagnostic communication ended.

These cases reinforced the importance of validating readings before allowing them to alter persistent performance records.

### The result

Diagnostic validation became an integral part of V-Sport Gauges, rather than a separate cosmetic feature.

The objective was to improve the usefulness of the displayed information without sacrificing responsiveness.

---

## Phase 6 — Cadillac-Specific Performance Instrumentation

The project also expanded beyond displaying standard Torque Pro parameters.

Custom GM diagnostic PIDs and calculated expressions were explored to provide information relevant to the Escalade's L87 engine.

### Horsepower and torque

Custom calculations were developed to estimate engine torque and horsepower using ECU-derived information.

The calculations use available torque-related parameters and engine RPM.

Because these values are calculated from diagnostic data, they are estimates rather than direct dynamometer measurements.

### Engine and drivetrain monitoring

The customized dashboards have included parameters such as:

- Calculated engine horsepower and torque
- Engine load
- Engine oil pressure
- Engine oil temperature
- Coolant temperature
- Transmission temperature
- Trip information and fuel economy
- Vehicle travel direction

The specific parameters can vary by dashboard and user configuration.

### A dashboard for actual driving

The selection of information reflects the vehicle's real use, including ordinary driving, performance monitoring, and towing.

Rather than trying to show every available PID, the goal has been to make important vehicle information visible and understandable at a glance.

---

## Phase 7 — Refining the Complete Dashboard
### Versions 3.3–3.4

As the instrumentation matured, considerable attention went into making the dashboard feel more integrated with the vehicle.

### Clock and compass

A clock and directional heading were incorporated into the display.

The direction feature uses phone-derived location information rather than a Torque Pro PID.

Later development added persistence of the last known travel direction, allowing the heading to remain visible when a new direction was not yet available.

### V-Series branding

A Cadillac V-Series-inspired logo was integrated into the dashboard header.

Its positioning, size, and clipping behavior were refined to fit within the available screen area.

### Stable header during ECU connection

Another refinement addressed layout movement during ECU connection.

The header was adjusted so that the clock, compass, and logo could remain visible while diagnostic communication was being established.

This helped prevent the dashboard from visibly shifting when connection status changed.

### Independent horizontal gauge configuration

Version 3.4 moved the horizontal gauge from global configuration into the individual dashboard settings.

Each dashboard could therefore have its own horizontal gauge configuration.

Obsolete global controls and associated lifecycle code were subsequently removed.

Additional options refined labels, numeric values, units, tick visibility, and peak indicators.

This represented a move toward more consistent configuration of round and horizontal instruments.

---

## Phase 8 — Daily Instrumentation and Continued Refinement
### Version 3.5.3 | November 2026

By version 3.5.3, V-Sport Gauges had evolved substantially beyond its initial manual changes.

The application combined:

- Customized Cadillac-inspired instrumentation
- Multiple configurable dashboards
- Separately managed sampling and animation
- Automatic diagnostic polling calibration
- Persistent peak records
- Daily maximum and minimum tracking
- RPM-qualified minimum validation
- Session and all-time peak indicators
- Diagnostic plausibility filtering
- Clock and compass integration
- Per-dashboard horizontal gauges
- Production and isolated test builds

The development has also involved many smaller refinements to layout, fonts, colors, spacing, input handling, and startup behavior.

Some experiments were revised or abandoned when testing revealed problems. That iterative process has been essential to improving the application.

**V-Sport Gauges remains an ongoing project, with changes guided by real-world testing rather than feature additions alone.**

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
