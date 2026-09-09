package com.jarvis.assistant

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.jarvis.assistant.databinding.ActivityAccountBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAccountBinding
    private lateinit var auth: FirebaseAuth
    private val webClientId = "1049139523928-8sj88pvmc5u0mgkb4m9ge9ecv82jqn5i.apps.googleusercontent.com"
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) { Toast.makeText(this, "Google account did not provide an ID token.", Toast.LENGTH_SHORT).show(); return@registerForActivityResult }
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener {
                if (it.isSuccessful) { showCurrentAccount(); Toast.makeText(this, "Account switched.", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "Could not switch account.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: ApiException) { Toast.makeText(this, "Account selection cancelled.", Toast.LENGTH_SHORT).show() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance()
        binding.closeButton.setOnClickListener { finish() }
        binding.switchAccountButton.setOnClickListener { launchGoogleAccountPicker() }
        binding.addAccountButton.setOnClickListener { launchGoogleAccountPicker() }
        binding.signOutButton.setOnClickListener { signOut() }
        showCurrentAccount()
    }
    override fun onResume() { super.onResume(); if (::auth.isInitialized) showCurrentAccount() }
    private fun showCurrentAccount() {
        val user = auth.currentUser
        if (user == null) {
            binding.nameText.text = "No account"; binding.emailText.text = "Sign in to JARVIS"
            binding.avatar.setImageResource(android.R.drawable.ic_menu_myplaces); binding.accountStatus.text = "SIGNED OUT"
            binding.signOutButton.visibility = View.GONE; return
        }
        binding.nameText.text = user.displayName ?: "Google user"
        binding.emailText.text = user.email ?: "No email available"
        binding.accountStatus.text = "ACCOUNT ACTIVE"; binding.signOutButton.visibility = View.VISIBLE
        user.photoUrl?.let { loadProfilePhoto(it) } ?: binding.avatar.setImageResource(android.R.drawable.ic_menu_myplaces)
    }
    private fun loadProfilePhoto(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = try {
                val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000; connection.readTimeout = 8000; connection.doInput = true; connection.connect()
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (!isFinishing) binding.avatar.setImageBitmap(bitmap ?: return@withContext)
            }
        }
    }
    private fun launchGoogleAccountPicker() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(webClientId).requestEmail().build()
        signInLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }
    private fun signOut() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(webClientId).requestEmail().build()
        GoogleSignIn.getClient(this, options).signOut().addOnCompleteListener {
            auth.signOut(); Toast.makeText(this, "Signed out of JARVIS.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, WelcomeActivity::class.java)); finishAffinity()
        }
    }
}
