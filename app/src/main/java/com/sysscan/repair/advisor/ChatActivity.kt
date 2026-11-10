package com.sysscan.repair.advisor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ActivityChatBinding
import com.sysscan.repair.databinding.ItemChatMessageBinding
import com.sysscan.repair.diag.battery.BatteryUsageReport
import com.sysscan.repair.diag.battery.BatteryUsageStore
import com.sysscan.repair.security.SecurityResultsStore

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private var snapshot: ScanSnapshot? = null
    private var battery: BatteryUsageReport? = null
    private var security: SecurityResultsStore.StoredScan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        snapshot = LastScanStore.load(this)
        battery = BatteryUsageStore.load(this)
        security = SecurityResultsStore.load(this)
        adapter = ChatAdapter()
        binding.chatList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { sendCurrent() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent()
                true
            } else false
        }

        binding.chipPlan.setOnClickListener { sendText(getString(R.string.chat_chip_plan)) }
        binding.chipBattery.setOnClickListener { sendText(getString(R.string.chat_chip_battery)) }
        binding.chipSlow.setOnClickListener { sendText(getString(R.string.chat_chip_slow)) }
        binding.chipBatteryApps.setOnClickListener {
            sendText(getString(R.string.chat_chip_battery_apps))
        }
        binding.chipVirus.setOnClickListener { sendText(getString(R.string.chat_chip_virus)) }

        addBot(DeviceAdvisor.greeting(snapshot, battery, security))
    }

    override fun onResume() {
        super.onResume()
        // atualiza o relatório de bateria caso o usuário tenha passado pela tela de consumo
        battery = BatteryUsageStore.load(this) ?: battery
        security = SecurityResultsStore.load(this) ?: security
    }

    private fun sendCurrent() {
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        sendText(text)
    }

    private fun sendText(text: String) {
        addUser(text)
        addBot(DeviceAdvisor.answer(text, snapshot, battery, security))
    }

    private fun addUser(text: String) {
        adapter.add(ChatMessage(text, fromUser = true))
        scrollToEnd()
    }

    private fun addBot(text: String) {
        adapter.add(ChatMessage(text, fromUser = false))
        scrollToEnd()
    }

    private fun scrollToEnd() {
        binding.chatList.post {
            val last = adapter.itemCount - 1
            if (last >= 0) binding.chatList.scrollToPosition(last)
        }
    }
}

data class ChatMessage(
    val text: String,
    val fromUser: Boolean
)

private class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val tv = holder.binding.messageText
        tv.text = msg.text
        val params = tv.layoutParams as android.widget.FrameLayout.LayoutParams
        if (msg.fromUser) {
            params.gravity = android.view.Gravity.END
            tv.setBackgroundResource(R.drawable.bg_chat_user)
            tv.setTextColor(ContextCompat.getColor(tv.context, R.color.white))
        } else {
            params.gravity = android.view.Gravity.START
            tv.setBackgroundResource(R.drawable.bg_chat_bot)
            tv.setTextColor(ContextCompat.getColor(tv.context, R.color.text_primary))
        }
        tv.layoutParams = params
    }

    class VH(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
}
