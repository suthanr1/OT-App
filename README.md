# OT Management Android Project

Native Android starter project for the OT Management app.

Backend:
- Supabase project URL: https://vosgviopuhieslzajleb.supabase.co
- Edge Function base: https://vosgviopuhieslzajleb.supabase.co/functions/v1
- Do NOT put a secret/service_role key in the Android app.

Current project:
- Android/Kotlin + Jetpack Compose
- Login screen
- Basic employee/admin app shell
- Backend constants prepared for the existing Supabase `api` Edge Function

Next integration:
1. Connect login to POST /api/auth/login.
2. Persist access/refresh session securely.
3. Add employee OT entry and IndexedDB-equivalent local Room queue.
4. Auto-sync queued OT when network returns.
5. Add Admin dashboard and employee management.
6. Build signed/release APK.

The Supabase publishable key must be inserted locally in MainActivity.kt before building.
