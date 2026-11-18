package com.example.sprintly.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sprintly.R
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.FragmentAccountBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AccountFragment : Fragment(R.layout.fragment_account) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentAccountBinding.bind(view)
        val session = ViewModelProvider(requireActivity())[SessionViewModel::class.java]
        ViewCompat.setAccessibilityHeading(binding.accountTitle, true)
        binding.retryButton.setOnClickListener { session.restore() }
        binding.logoutButton.setOnClickListener { session.signOut() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, session.isRestoring, ::Pair).collect { (state, restoring) ->
                    val account = state as? AuthState.SignedIn
                    binding.profileContent.isVisible = account != null
                    if (account != null) {
                        binding.accountName.text = account.user.displayName
                        binding.accountEmail.text = account.user.email
                        binding.retryPanel.isVisible = account.needsRetry
                        binding.retryButton.isEnabled = !restoring
                        binding.retryProgress.isVisible = restoring && account.needsRetry
                    }
                }
            }
        }
    }
}
