# V-Sport Gauges

### Cadillac-Inspired Performance Instrumentation for Android Auto

**Real-time vehicle performance monitoring. Customizable instrumentation. A factory-inspired driving experience.**

## My V-Sport Gauges Setup

![V-Sport Gauges running on the factory OLED display of a 2023 Cadillac Escalade Sport Platinum](V-Sport%20Gauges.jpg)

*My V-Sport Gauges setup running on the factory OLED display of a 2023 Cadillac Escalade Sport Platinum.*

V-Sport Gauges is a personalized evolution of the open-source AA Torque application, developed for a 2023 Cadillac Escalade Sport Platinum with the 6.2L L87 V8. It combines Torque Pro vehicle diagnostics with Cadillac V-Series-inspired instrumentation, responsive gauges, performance calculations, and additional monitoring features.

**The goal:** Make aftermarket instrumentation look, feel, and perform as though it belongs in the vehicle.

## Origins & Acknowledgments

**V-Sport Gauges would not exist without the work of Kevin Agronick and the developers whose innovations preceded his.**

[Kevin Agronick's AA Torque](https://github.com/agronick/aa-torque) is the direct software foundation of this project. Kevin developed a highly customizable way to display Torque Pro data within Android Auto, including custom PIDs, configurable dashboards, gauge themes, expressions, and rotary controller support.

The project's earlier lineage includes [Chillout's Performance Monitor](https://github.com/jilleb/mqb-pm), itself based on Martoreto's aa-stats.

**None of the personalization, performance enhancements, or additional features of V-Sport Gauges would have been possible without these developers' creativity, technical achievements, and generosity in sharing their work.** Their contributions are gratefully acknowledged.

## The Evolution of V-Sport Gauges

The project began by adapting AA Torque's appearance and layout to complement the Escalade's factory OLED display. Refinements to typography, gauge arrangement, needles, and colors led to a broader effort to improve the instrumentation experience itself.

Subsequent work focused on smoother gauge movement, more responsive acquisition, calibration, calculated performance data, peak tracking, and protection against invalid ECU readings.

The development philosophy remains simple: preserve the flexibility of AA Torque, make the interface feel native to the vehicle, and favor reliable, efficient operation over unnecessary complexity.

## Features & Enhancements

### Cadillac-inspired dashboard

- Refined round gauges, labels, ticks, needles, and themed colors
- Custom horizontal gauge and multiple personalized dashboard layouts
- Integrated clock, directional heading, and V-Series branding
- Configurable parameters, ranges, and dashboard arrangements

### Responsive instrumentation

- Separate diagnostic sampling and animated gauge movement
- Adjustable animation timing
- Automatic communication calibration and a calibration results display
- Optimizations developed through testing with Torque Pro and a Bluetooth OBD-II adapter

### Vehicle performance and diagnostics

The Escalade configuration includes calculated horsepower and torque, engine load, oil pressure and temperature, coolant and transmission temperatures, and trip information. GM-specific PIDs and Torque Pro expressions allow additional customization.

**Calculated horsepower and torque are estimates derived from ECU data, not dynamometer measurements.**

### Peak monitoring and data protection

- Persistent peak values and colored peak indicators
- Temporary peak readouts when a dashboard loads or a new maximum occurs
- Filtering of invalid, non-finite, and implausible diagnostic readings
- Additional daily maximum/minimum tracking is part of ongoing development

## From Concept to Reality

V-Sport Gauges has evolved through repeated design experiments and in-vehicle testing. Concepts explored alternative gauge faces, tick marks, typography, needles, and dashboard layouts.

Development concept artwork can be added here in a future documentation update, clearly labeled as conceptual rather than a screenshot of the running application.

## Development & Testing Environment

| Component | Configuration |
| --- | --- |
| Vehicle | 2023 Cadillac Escalade Sport Platinum |
| Engine | GM 6.2L L87 V8 |
| Display | Factory Cadillac OLED |
| Android device | Samsung Galaxy S10 |
| Android version | Android 12 |
| Vehicle interface | Android Auto |
| Diagnostic software | Torque Pro |
| OBD-II adapter | Vgate vLinker BM Bluetooth |

Other vehicle and Android configurations may work, depending on available diagnostic data and Android Auto compatibility.

## Installation & Configuration

1. Install and configure [Torque Pro](https://play.google.com/store/apps/details?id=org.prowl.torque).
2. Pair and verify a compatible OBD-II adapter.
3. Download the appropriate APK from [V-Sport Gauges Releases](https://github.com/RACMan2527/aa-torque/releases).
4. Install the APK and enable the application in Android Auto where supported.
5. Configure dashboards, gauges, and PIDs, then verify live data in the vehicle.

Android Auto places restrictions on applications installed outside the Play Store. Installation methods vary by device and software version. For detailed background, see the [original AA Torque installation guide](https://github.com/agronick/aa-torque#instructions).

## Development Philosophy

V-Sport Gauges is an ongoing personal project shaped by practical use and experimentation. Many improvements are small refinements to alignment, responsiveness, clarity, and usability rather than new features.

**The objective is not simply to display more information. It is to display the right information, in the right way, at the right time.**

## Credits, License & Disclaimer

- **Kevin Agronick:** [AA Torque](https://github.com/agronick/aa-torque), the principal software foundation
- **Chillout / jilleb:** [Performance Monitor](https://github.com/jilleb/mqb-pm)
- **Martoreto:** aa-stats, an earlier project in the development lineage

Applicable upstream license terms and attribution requirements remain in effect. V-Sport Gauges is an independent personal customization project and is not affiliated with or endorsed by Cadillac, General Motors, Google, or the upstream developers. Diagnostic readings are provided for informational purposes and depend on the vehicle, adapter, and configured calculations.
