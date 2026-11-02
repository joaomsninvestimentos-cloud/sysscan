package com.sysscan.repair.security

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ItemSecurityAppBinding

/** Lista os apps analisados, ordenados pelo risco. */
class SecurityAppAdapter(
    private val onClick: (AppSecurityReport) -> Unit
) : RecyclerView.Adapter<SecurityAppAdapter.VH>() {

    private var items: List<AppSecurityReport> = emptyList()

    fun submit(newItems: List<AppSecurityReport>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSecurityAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val binding = holder.binding
        val context = binding.root.context
        val colorRes = RiskRules.colorResFor(app.level)
        val color = ContextCompat.getColor(context, colorRes)

        binding.appName.text = app.label
        binding.appIcon.setImageDrawable(iconFor(context, app.packageName))

        binding.appSignals.text = if (app.signals.isEmpty()) {
            context.getString(R.string.security_risk_label, app.score)
        } else {
            app.signals.take(3).joinToString(" · ") { it.title }
        }

        binding.appMeta.text = buildString {
            append(app.installerLabel)
            if (app.isHidden) append(" · sem ícone")
            if (app.accessibilityService) append(" · acessibilidade")
            if (app.dangerousPermissions.isNotEmpty()) {
                append(" · ").append(app.dangerousPermissions.size).append(" permissão(ões) sensível(is)")
            }
            app.batteryLabel()?.let { append(" · ").append(it) }
        }

        binding.appScore.text = app.score.toString()
        binding.appScore.setTextColor(color)
        binding.appLevel.text = app.level.label
        binding.appLevel.setTextColor(color)

        binding.severityIcon.setImageResource(iconFor(app.level))
        binding.severityIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        val verdict = app.vt
        when {
            verdict == null -> binding.appVt.visibility = View.GONE
            verdict.found && verdict.isBad -> {
                binding.appVt.visibility = View.VISIBLE
                binding.appVt.setTextColor(ContextCompat.getColor(context, R.color.crit_red))
                binding.appVt.text = context.getString(
                    R.string.security_vt_bad, verdict.malicious, verdict.engines
                )
            }
            !verdict.found -> {
                binding.appVt.visibility = View.VISIBLE
                binding.appVt.setTextColor(ContextCompat.getColor(context, R.color.warn_amber))
                binding.appVt.text = context.getString(R.string.security_vt_unknown)
            }
            else -> {
                binding.appVt.visibility = View.VISIBLE
                binding.appVt.setTextColor(ContextCompat.getColor(context, R.color.ok_green))
                binding.appVt.text = context.getString(R.string.security_vt_ok)
            }
        }

        binding.root.setOnClickListener { onClick(app) }
        binding.root.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.card_bg)
        )
    }

    private fun iconFor(level: RiskLevel): Int = when (level) {
        RiskLevel.CRITICAL, RiskLevel.HIGH -> R.drawable.ic_error
        RiskLevel.MEDIUM -> R.drawable.ic_warning
        RiskLevel.LOW, RiskLevel.CLEAN -> R.drawable.ic_info
    }

    private fun iconFor(context: Context, packageName: String) = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
    }

    class VH(val binding: ItemSecurityAppBinding) : RecyclerView.ViewHolder(binding.root)
}
