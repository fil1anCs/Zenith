# Supabase setup

1. Create a Supabase project.
2. Copy the Project URL and the public anon/publishable key from Project Settings → API.
3. Copy `../local.properties.example` to the Android project's `local.properties` and set `supabase.url` and `supabase.anonKey`.
4. Run `migrations/001_zenith_schema.sql` in the Supabase SQL Editor.
5. Rebuild the Android app.

The Android app must never contain the `service_role` key or any secret key. The SQL migration enables RLS on every application table. Review policies before production deployment and add rate limits / moderation functions on trusted server-side infrastructure.
