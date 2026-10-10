package com.ravy.assistant.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/**
 * General "who is using Ravy" sign-in — separate from gmail/GmailAuth.kt,
 * which requests the specific Gmail-reading scope. This one only asks for
 * basic profile + email, the same real Google Sign-In flow used by most
 * Android apps.
 *
 * Email+verification-code sign-in is NOT implemented here. A genuine
 * "send a code, verify it" flow needs a backend that sends emails and
 * checks codes — real server infrastructure, not something that can live
 * inside this Android project alone. Rather than fake that flow with a
 * hardcoded or client-only "verification", it's left as a clearly marked
 * "not yet implemented" placeholder in the sign-in screen. Standing up a
 * proper provider for it later (e.g. Firebase Authentication) is possible,
 * but is a deliberate separate addition, not something to fake here.
 */
object RavyAuth {

    private fun client(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun signInIntent(context: Context): Intent = client(context).signInIntent

    fun currentAccount(context: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun signOut(context: Context, onDone: () -> Unit) {
        client(context).signOut().addOnCompleteListener { onDone() }
    }
}
