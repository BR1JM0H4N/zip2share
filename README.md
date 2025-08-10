# zip2share

![Platform](https://img.shields.io/badge/platform-Android-green.svg)
[![Android CI](https://github.com/BR1JM0H4N/zip2share/actions/workflows/android.yml/badge.svg)](https://github.com/BR1JM0H4N/zip2share/actions/workflows/android.yml)
![Gradle](https://img.shields.io/badge/build%20with-Gradle-02303A?logo=gradle)
![Java](https://img.shields.io/badge/Java-17-blue?logo=java)
![Min SDK](https://img.shields.io/badge/minSdk-21-brightgreen)
![Target SDK](https://img.shields.io/badge/targetSdk-34-blue)
![Version](https://img.shields.io/badge/version-3.0.0-yellow)

![License](https://img.shields.io/github/license/BR1JM0H4N/zip2share)
![Stars](https://img.shields.io/github/stars/BR1JM0H4N/zip2share?style=social)
![Forks](https://img.shields.io/github/forks/BR1JM0H4N/zip2share?style=social)
![Issues](https://img.shields.io/github/issues/BR1JM0H4N/zip2share)
![Pull Requests](https://img.shields.io/github/issues-pr/BR1JM0H4N/zip2share)
![Last Commit](https://img.shields.io/github/last-commit/BR1JM0H4N/zip2share)

A lightweight Android application that zips one or multiple shared files into a temporary archive and opens the system share sheet to re-share the resulting ZIP file.

---

## Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Usage](#usage)
- [Screenshots](#screenshots)
- [Credits](#credits)
- [License](#license)

---

## Features

- Accepts single or multiple shared files via Android share intent
- Compresses files into a timestamped ZIP archive in the app’s cache directory
- Opens the native Android share sheet to re-share the generated ZIP
- Automatically cleans up temporary archives after sharing completes

## Prerequisites

- **Android Studio** (or any compatible IDE) for building and debugging the project. Courtesy of [AndroidIDEOfficial](https://github.com/AndroidIDEOfficial).
- **Android SDK** (API level 21+ recommended)
- **Java 17** or higher

## Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/BR1JM0H4N/zip2share.git
   cd zip2share
   ```
2. Open the project in Android Studio.
3. Let Gradle sync and resolve dependencies.
4. Build and install the app on your device or emulator.

> **Note:** Pre-built Signed APKs are also available in the `assets` section for direct download and installation and unsigned APKs are in `Actions` under workflow > build > Artifacts.

## Usage

1. In any app or file manager, select one or more files and tap the **Share** button.
2. Choose **zip2share** from the share targets.
3. The app will generate a ZIP archive and immediately open the share sheet.
4. Select your desired app (email, messaging, cloud storage, etc.) to send the archive.

## Screenshots

![Screenshot 1](https://github.com/BR1JM0H4N/zip2share/blob/main/Screenshots/20250420_181352.jpg)

![demo-gif](https://github.com/BR1JM0H4N/zip2share/blob/main/Screenshots/demo%20use.gif)

<video src="https://github.com/BR1JM0H4N/zip2share/blob/main/Screenshots/demo-vid.mp4" controls width="600"></video>

## Credits

- Icon and original idea by [AlphaCodec](https://github.com/AlphaCodec)
- Developed using the Android Studio environment provided by [AndroidIDEOfficial](https://github.com/AndroidIDEOfficial)

## License

Custom Non-Commercial License (Modified MIT License)
Copyright (c) 2025 Br1jm0h4n

Permission is hereby granted, free of charge, to any person obtaining a copy
of this Software and associated documentation files (the “Software”), to use,
copy, modify, and distribute copies of the Software solely for non-commercial
purposes, subject to the following conditions:

1. Attribution. The above copyright notice and this permission notice shall be
   included in all copies or substantial portions of the Software. All derived
   works must provide proper credit to the original author, Br1jm0h4n.

2. Non-Commercial Use. The Software, in whole or in part, may not be used for 
   any commercial purpose, including but not limited to selling, licensing, or 
   incorporating it into a product or service offered for profit, without the
   express prior written consent of the copyright holder. Any request for
   commercial usage should be directed to the copyright holder for permission.

3. No Warranty; No Liability. THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY 
   OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES 
   OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. 
   IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
   DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR 
   OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SOFTWARE OR THE 
   USE OR OTHER DEALINGS IN THE SOFTWARE.

By using the Software, you agree to be bound by the terms and conditions of
this License.

