package com.jarvis.assistant

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.jarvis.assistant.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var auth: FirebaseAuth
    private val webClientId = "1049139523928-8sj88pvmc5u0mgkb4m9ge9ecv82jqn5i.apps.googleusercontent.com"

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { authResult ->
                if (authResult.isSuccessful) {
                    animateAndGo()
                } else {
                    Toast.makeText(this, "Sign-in failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: ApiException) {
            Toast.makeText(this, "Sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        if (user != null) {
            val name = user.displayName?.substringBefore(" ") ?: "back"
            binding.subtitleText.text = "Welcome back, $name"
            binding.actionButton.text = "Start Jarvis"
            binding.actionButton.setOnClickListener { animateAndGo() }
        } else {
            binding.subtitleText.text = "Your personal AI assistant"
            binding.actionButton.text = "Sign in with Google"
            binding.actionButton.setOnClickListener { startSignIn() }
        }
    }

    private fun startSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun animateAndGo() {
        val scaleUp = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 1f, 1.15f)
        val scaleUpY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 1f, 1.15f)
        val scaleDown = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 1.15f, 1f)
        val scaleDownY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 1.15f, 1f)
        val set = AnimatorSet()
        set.play(scaleUp).with(scaleUpY)
        set.play(scaleDown).with(scaleDownY).after(scaleUp)
        set.duration = 180
        set.start()

        binding.root.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 320)
    }
}