package com.example.sprintly.ui.workspace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.sprintly.R
import com.example.sprintly.databinding.ItemWorkspaceCardBinding
import com.example.sprintly.databinding.ItemWorkspaceFooterBinding
import com.example.sprintly.databinding.ItemWorkspaceRowBinding
import com.example.sprintly.workspace.Member
import com.example.sprintly.workspace.Project
import com.example.sprintly.workspace.Workspace

internal val Workspace.isOwner: Boolean get() = role.equals("OWNER", ignoreCase = true)
internal val Member.isOwner: Boolean get() = role.equals("OWNER", ignoreCase = true)

private fun roleLabel(isOwner: Boolean) =
    if (isOwner) R.string.workspace_owner else R.string.workspace_member

internal class WorkspaceCardsAdapter(
    private val open: (Workspace) -> Unit,
) : ListAdapter<Workspace, WorkspaceCardsAdapter.Holder>(
    WorkspaceDiff,
) {
    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemWorkspaceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            workspaceName.text = item.name
            workspaceDescription.text = item.description
            workspaceDescription.isVisible = !item.description.isNullOrBlank()
            workspaceRole.setText(roleLabel(item.isOwner))
            workspaceMark.text = initials(item.name)
            val background = if (item.isOwner) R.color.orbix_workspace_mark_owner else R.color.orbix_selection_container
            val foreground = if (item.isOwner) R.color.orbix_on_primary else R.color.orbix_selection
            workspaceMark.backgroundTintList = root.context.tint(background)
            workspaceMark.setTextColor(root.context.getColor(foreground))
            root.setOnClickListener { open(item) }
        }
    }

    class Holder(val binding: ItemWorkspaceCardBinding) : RecyclerView.ViewHolder(binding.root)
}

internal class WorkspaceFooterAdapter(
    private val createWorkspace: () -> Unit,
) : RecyclerView.Adapter<WorkspaceFooterAdapter.Holder>() {
    private var empty = false
    private var canCreate = false

    fun update(empty: Boolean, canCreate: Boolean) {
        if (this.empty == empty && this.canCreate == canCreate) return
        this.empty = empty
        this.canCreate = canCreate
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemWorkspaceFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        ViewCompat.setAccessibilityHeading(binding.workspaceEmptyTitle, true)
        binding.workspaceCreate.setOnClickListener { createWorkspace() }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.workspaceEmpty.isVisible = empty
        holder.binding.workspaceCreate.isEnabled = canCreate
    }

    class Holder(val binding: ItemWorkspaceFooterBinding) : RecyclerView.ViewHolder(binding.root)
}

internal data class WorkspaceRow(
    val id: String,
    val title: String,
    val description: String?,
    val role: Int? = null,
    val canRemove: Boolean = false,
) {
    constructor(project: Project) : this(project.id, project.name, project.description)

    constructor(member: Member, canRemove: Boolean) : this(
        member.id,
        member.displayName,
        member.email,
        roleLabel(member.isOwner),
        canRemove && !member.isOwner,
    )
}

internal class WorkspaceRowsAdapter(
    private val open: ((WorkspaceRow) -> Unit)? = null,
    private val remove: ((WorkspaceRow) -> Unit)? = null,
) : ListAdapter<WorkspaceRow, WorkspaceRowsAdapter.Holder>(
    WorkspaceRowDiff,
) {
    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemWorkspaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.binding) {
            rowTitle.text = row.title
            rowDescription.text = row.description
            rowDescription.isVisible = !row.description.isNullOrBlank()
            rowAvatar.isVisible = row.role != null
            rowAvatar.text = initials(row.title)
            rowRole.isVisible = row.role != null
            row.role?.let(rowRole::setText)
            rowMore.isVisible = row.canRemove && remove != null
            rowMore.contentDescription = root.context.getString(R.string.workspace_member_actions, row.title)
            rowMore.setOnClickListener {
                rowMore.showActions(R.string.workspace_member_remove to { remove?.invoke(row) })
            }
            if (open != null) {
                root.setOnClickListener { open.invoke(row) }
                root.isFocusable = true
            }
        }
    }

    class Holder(val binding: ItemWorkspaceRowBinding) : RecyclerView.ViewHolder(binding.root)
}

private object WorkspaceDiff : DiffUtil.ItemCallback<Workspace>() {
    override fun areItemsTheSame(oldItem: Workspace, newItem: Workspace): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Workspace, newItem: Workspace): Boolean =
        oldItem == newItem
}

private object WorkspaceRowDiff : DiffUtil.ItemCallback<WorkspaceRow>() {
    override fun areItemsTheSame(oldItem: WorkspaceRow, newItem: WorkspaceRow): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: WorkspaceRow, newItem: WorkspaceRow): Boolean =
        oldItem == newItem
}
