# OT Management — Final Offline Android App

Native Android/Kotlin + Jetpack Compose app for offline OT management.

## Features
- Separate Admin / Employee login
- Admin creates, edits, enables/disables and deletes employees
- Employee login ID = employee name; initial password = employee ID
- Employee sees only their own OT on the device
- OT From / To with automatic hours, including overnight OT
- Default OT rate ₹75/hour; Admin can change rate
- Sunday only: Normal – 1× / Double – 2×
- One OT entry per employee per date
- Employee can delete own OT entry
- 26th → 25th monthly cycles
- Employee monthly folders and Admin monthly reports
- Admin dashboard with total employees, hours and amount
- CSV export and JSON backup/restore
- Persistent login session until logout
- Fully offline; no Supabase, server or internet dependency

## Default Admin
- Login ID: `admin`
- Password: `admin123`

Change the admin password after first login.

## Important
This version stores data locally on each Android device. It does **not** share data between different phones. Online multi-device sync can be added later without changing the OT rules.

## Build
Open the project in Android Studio and run it, or use the included GitHub Actions workflow to build a debug APK.
