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
import androidx.navigation.NavDestination.Companion.hierarchy
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
    private var routedUserId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routedUserId = savedInstanceState?.getString(ROUTED_USER_KEY)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ROUTED_USER_KEY, routedUserId)
    }

    private suspend fun showSession(
        binding: ActivityMainBinding,
        nav: NavController,
        state: AuthState,
    ) {
        val currentDestination = nav.currentDestination
        val current = currentDestination?.id
        val inWorkspace = currentDestination?.hierarchy?.any {
            it.id == R.id.authenticated_graph
        } == true
        val destination = when (state) {
            AuthState.Checking -> null
            is AuthState.SignedIn -> {
                val keepDestination = inWorkspace && routedUserId == state.user.id
                routedUserId = state.user.id
                if (keepDestination) current else R.id.authenticated_graph
            }
            is AuthState.SignedOut -> {
                routedUserId = null
                if (current == R.id.register_fragment) current else R.id.login_fragment
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

    private companion object {
        const val ROUTED_USER_KEY = "routedUserId"
    }
}
