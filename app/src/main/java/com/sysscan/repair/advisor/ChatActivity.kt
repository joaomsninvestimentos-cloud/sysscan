package com.sysscan.repair.advisor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sysscan.repair.R
import com.sysscan.repair.databinding.ActivityChatBinding
import com.sysscan.repair.databinding.ItemChatMessageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private var snapshot: ScanSnapshot? = null
    private val history = mutableListOf<Pair<String, String>>()
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        snapshot = LastScanStore.load(this)
        adapter = ChatAdapter()
        binding.chatList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatList.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLlmSettings.setOnClickListener { showLlmSettings() }
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
        binding.chipRoot.setOnClickListener { sendText(getString(R.string.chat_chip_root)) }

        refreshModeLabel()
        addBot(DeviceAdvisor.greeting(snapshot))
    }

    private fun refreshModeLabel() {
        binding.chatMode.text = if (LlmSettings.isConfigured(this)) {
            getString(R.string.chat_mode_llm)
        } else {
            getString(R.string.chat_mode_local)
        }
    }

    private fun sendCurrent() {
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        sendText(text)
    }

    private fun sendText(text: String) {
        if (sending) return
        addUser(text)
        history.add("user" to text)
        if (LlmSettings.isConfigured(this)) {
            sending = true
            binding.btnSend.isEnabled = false
            addBot(getString(R.string.chat_thinking))
            val prior = history.toList()
            lifecycleScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    try {
                        LlmClient.ask(this@ChatActivity, text, snapshot, prior.dropLast(1))
                    } catch (_: Exception) {
                        DeviceAdvisor.answer(text, snapshot)
                    }
                }
                adapter.replaceLastBot(reply)
                history.add("assistant" to reply)
                sending = false
                binding.btnSend.isEnabled = true
                scrollToEnd()
            }
        } else {
            val reply = DeviceAdvisor.answer(text, snapshot)
            addBot(reply)
            history.add("assistant" to reply)
        }
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

    private fun showLlmSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.chat_llm_key)
            setText(LlmSettings.apiKey(this@ChatActivity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.chat_llm_url)
            setText(LlmSettings.baseUrl(this@ChatActivity))
        }
        val modelInput = EditText(this).apply {
            hint = getString(R.string.chat_llm_model)
            setText(LlmSettings.model(this@ChatActivity))
        }
        layout.addView(keyInput)
        layout.addView(urlInput)
        layout.addView(modelInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_settings)
            .setMessage(R.string.chat_llm_help)
            .setView(layout)
            .setPositiveButton(R.string.chat_save) { _, _ ->
                LlmSettings.save(
                    this,
                    keyInput.text.toString(),
                    urlInput.text.toString(),
                    modelInput.text.toString()
                )
                refreshModeLabel()
                Toast.makeText(this, R.string.chat_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    fun replaceLastBot(text: String) {
        val idx = items.indexOfLast { !it.fromUser }
        if (idx >= 0) {
            items[idx] = ChatMessage(text, fromUser = false)
            notifyItemChanged(idx)
        }
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
