PATHRA – Android build
1. Install Android Studio (Koala or newer). File > Open > select this PathraApp folder. Let Gradle sync (needs internet).
2. Plug in an Android phone (USB debugging on) and press Run. A real phone is needed: SMS, GPS and sensors don't work on the emulator.
3. On first launch allow SMS, Location, Notifications, then "Allow all the time" for location.
4. Register inside the app with an emergency contact phone number (with country code, e.g. +91...).
5. Phone settings > Apps > Pathra > Battery > Unrestricted (otherwise Xiaomi/Oppo/Vivo/Samsung may stop the background service).
To get an installable file: Build > Build APK(s) -> app/build/outputs/apk/debug/app-debug.apk
The web app lives in app/src/main/assets/index.html – edit it there, then Run again.
