package com.sysscan.repair.battery

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ItemAppBatteryBinding
import com.sysscan.repair.diag.battery.AppBatteryUsage
import com.sysscan.repair.diag.battery.BatteryScoring
import com.sysscan.repair.util.Formatters
import java.util.Locale

/** Lista o ranking de consumo por aplicativo. */
class AppBatteryUsageAdapter(
    private val onClick: (AppBatteryUsage) -> Unit
) : RecyclerView.Adapter<AppBatteryUsageAdapter.VH>() {

    private var items: List<AppBatteryUsage> = emptyList()

    fun submit(newItems: List<AppBatteryUsage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBatteryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val binding = holder.binding
        val context = binding.root.context

        binding.rank.text = (position + 1).toString()
        binding.appName.text = app.label
        binding.appDetail.text = buildString {
            append(app.usageLabel()).append(" em uso")
            if (app.sessions > 0) append(" · ").append(app.sessions).append(" aberturas")
            if (app.totalBytes > 0) append(" · ").append(app.dataLabel())
            append(" · peso ").append(String.format(Locale.US, "%.0f", app.weight)).append("/100")
            app.estimatedMah?.let {
                append(" · ≈ ").append(Formatters.milliampHours(it))
            }
        }

        binding.appShare.text = String.format(Locale.US, "%.0f%%", app.sharePercent)
        binding.appShare.setTextColor(
            ContextCompat.getColor(context, BatteryScoring.levelColorRes(app.weight))
        )

        binding.shareBar.progress = app.sharePercent.coerceIn(0.0, 100.0).toInt()
        binding.shareBar.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, BatteryScoring.levelColorRes(app.weight))
        )

        binding.appIcon.setImageDrawable(iconFor(context, app.packageName))

        val flags = buildList {
            if (app.backgroundSuspect) add("Atividade em segundo plano")
            if (app.ignoresOptimizations && !app.isSystemApp) add("Isento da otimização")
            if (app.isSystemApp) add("App do sistema")
        }
        if (flags.isEmpty()) {
            binding.appFlag.visibility = View.GONE
        } else {
            binding.appFlag.visibility = View.VISIBLE
            binding.appFlag.text = flags.joinToString(" · ")
        }

        binding.root.setOnClickListener { onClick(app) }
    }

    private fun iconFor(context: Context, packageName: String) =
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        }

    class VH(val binding: ItemAppBatteryBinding) : RecyclerView.ViewHolder(binding.root)
}
