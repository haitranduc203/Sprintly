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
import androidx.navigation.fragment.findNavController
import com.example.sprintly.R
import com.example.sprintly.auth.AuthState
import com.example.sprintly.databinding.FragmentAccountBinding
import com.example.sprintly.ui.workspace.initials
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AccountFragment : Fragment(R.layout.fragment_account) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentAccountBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.accountName, true)
        val session = ViewModelProvider(requireActivity())[SessionViewModel::class.java]
        binding.accountToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.retryButton.setOnClickListener { session.restore() }
        binding.logoutButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.auth_logout_confirm)
                .setNegativeButton(R.string.workspace_cancel, null)
                .setPositiveButton(R.string.auth_logout) { _, _ -> session.signOut() }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.state, session.isRestoring, ::Pair).collect { (state, restoring) ->
                    val account = state as? AuthState.SignedIn
                    binding.profileContent.isVisible = account != null
                    if (account != null) {
                        binding.accountAvatar.text = initials(account.user.displayName)
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
