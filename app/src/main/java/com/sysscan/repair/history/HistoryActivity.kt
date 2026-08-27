package com.sysscan.repair.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.R
import com.sysscan.repair.databinding.HistoryActivityBinding
import com.sysscan.repair.databinding.ItemHistoryEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: HistoryActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = HistoryActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val entries = ScanHistoryStore.getAll(this)
        binding.chart.entries = entries

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = HistoryAdapter(entries)

        if (entries.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
        }
    }
}

private class HistoryAdapter(
    private val entries: List<ScanHistoryEntry>
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd/MM 'às' HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = entries[position]
        val b = holder.binding
        b.historyDate.text = dateFormat.format(Date(e.timestamp))
        b.historyScore.text = e.score.toString()
        b.historyDetail.text = buildString {
            append("OK ${e.ok}")
            if (e.warning > 0) append(" · ${e.warning} atenção")
            if (e.critical > 0) append(" · ${e.critical} crítico")
            if (e.hasRoot) append(" · root")
        }
        b.historyScore.setTextColor(
            when {
                e.score >= 80 -> b.historyScore.context.getColor(R.color.ok_green)
                e.score >= 60 -> b.historyScore.context.getColor(R.color.warn_amber)
                else -> b.historyScore.context.getColor(R.color.crit_red)
            }
        )
    }

    class VH(val binding: ItemHistoryEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
