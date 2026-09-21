package com.suthan.otmanagement

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest

val supabase = createSupabaseClient(
    supabaseUrl = "https://lexrjynnikkydnyhewvm.supabase.co",
    supabaseKey = "sb_publishable_IBZ3bsbWriJuWT0i6kYUgQ_P8zDwt1P"
) {
    install(Auth)
    install(Postgrest)
}
