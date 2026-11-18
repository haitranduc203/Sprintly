package com.example.sprintly

import android.graphics.Color
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.ActivityMainBinding
import com.example.sprintly.ui.SessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val session: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val binding = ActivityMainBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = safeArea.left,
                top = safeArea.top,
                right = safeArea.right,
                bottom = safeArea.bottom,
            )
            insets
        }
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collectLatest { state ->
                    showSession(binding, navHost.navController, state)
                }
            }
        }
    }

    private suspend fun showSession(
        binding: ActivityMainBinding,
        nav: NavController,
        state: AuthState,
    ) {
        val current = nav.currentDestination?.id
        val destination = when (state) {
            AuthState.Checking -> null
            is AuthState.SignedIn -> R.id.account_fragment
            is AuthState.SignedOut -> when (current) {
                R.id.register_fragment -> R.id.register_fragment
                else -> R.id.login_fragment
            }
        }

        if (state is AuthState.SignedIn && binding.navHost.isVisible &&
            (current == R.id.login_fragment || current == R.id.register_fragment)
        ) {
            getSystemService(AutofillManager::class.java)?.commit()
        }

        if (destination == null || destination != current) {
            binding.sessionGate.root.isVisible = true
            binding.navHost.isVisible = false
        }
        if (destination == null) return

        if (destination != current) {
            nav.navigate(destination, null, navOptions {
                popUpTo(R.id.main_graph) { inclusive = true }
            })
        }

        // Navigation can restore an old destination before the repository verifies the session.
        nav.currentBackStackEntry?.lifecycle?.withResumed {
            binding.navHost.isVisible = true
            binding.sessionGate.root.isVisible = false
        }
    }
}
