package com.sysscan.repair

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.databinding.ItemScanCheckBinding
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanSeverity
import com.sysscan.repair.repair.FixResult

class ScanResultsAdapter(
    private val onFix: (ScanCheck) -> Unit
) : RecyclerView.Adapter<ScanResultsAdapter.VH>() {

    private var allItems: List<ScanCheck> = emptyList()
    private var fixResults: Map<String, FixResult> = emptyMap()
    private var severityFilter: ScanSeverity? = null

    fun submit(newItems: List<ScanCheck>, newFixResults: Map<String, FixResult>) {
        allItems = newItems
        fixResults = newFixResults
        notifyDataSetChanged()
    }

    fun setSeverityFilter(severity: ScanSeverity?) {
        severityFilter = severity
        notifyDataSetChanged()
    }

    fun hasItemsFor(severity: ScanSeverity): Boolean =
        allItems.any { it.severity == severity }

    private val visibleItems: List<ScanCheck>
        get() {
            val filter = severityFilter ?: return allItems
            return allItems.filter { it.severity == filter }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanCheckBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = visibleItems.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val check = visibleItems[position]
        val binding = holder.binding

        binding.checkTitle.text = check.title
        binding.checkDetail.text = check.detail

        val (iconRes, colorRes) = visualFor(check.severity)
        binding.severityIcon.setImageResource(iconRes)
        binding.severityIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(binding.root.context, colorRes))
        }

        val fixResult = fixResults[check.id]
        when {
            fixResult != null -> {
                binding.fixedIcon.visibility = if (fixResult.success) View.VISIBLE else View.GONE
                binding.btnFix.visibility = View.GONE
                binding.checkDetail.text = fixResult.message
            }
            check.fixId != null && check.severity != ScanSeverity.OK -> {
                binding.btnFix.visibility = View.VISIBLE
                binding.btnFix.text = "Corrigir"
                binding.btnFix.setOnClickListener { onFix(check) }
                binding.fixedIcon.visibility = View.GONE
            }
            else -> {
                binding.btnFix.visibility = View.GONE
                binding.fixedIcon.visibility = View.GONE
            }
        }
    }

    fun currentFilter(): ScanSeverity? = severityFilter

    private fun visualFor(severity: ScanSeverity): Pair<Int, Int> = when (severity) {
        ScanSeverity.OK -> R.drawable.ic_check_circle to R.color.ok_green
        ScanSeverity.WARNING -> R.drawable.ic_warning to R.color.warn_amber
        ScanSeverity.CRITICAL -> R.drawable.ic_error to R.color.crit_red
        ScanSeverity.INFO -> R.drawable.ic_info to R.color.info_blue
    }

    class VH(val binding: ItemScanCheckBinding) : RecyclerView.ViewHolder(binding.root)
}
