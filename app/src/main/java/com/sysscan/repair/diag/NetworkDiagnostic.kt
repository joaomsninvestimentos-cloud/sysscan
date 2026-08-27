package com.sysscan.repair.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sysscan.repair.model.ScanCategory
import com.sysscan.repair.model.ScanCheck
import com.sysscan.repair.model.ScanCheckBuilder
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkDiagnostic(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun check(): List<ScanCheck> {
        val results = mutableListOf<ScanCheck>()

        val network = connectivityManager.activeNetwork
        val caps = network?.let {
            connectivityManager.getNetworkCapabilities(it)
        }

        val connected = caps != null &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

        results.add(
            when {
                network == null -> ScanCheckBuilder.critical(
                    "network_conn", ScanCategory.NETWORK, "Sem conexão de rede",
                    "Nenhuma rede ativa detectada. Serviços de sincronização e atualização ficam indisponíveis.",
                    "network_open_settings"
                )
                !connected -> ScanCheckBuilder.warning(
                    "network_conn", ScanCategory.NETWORK, "Rede sem internet",
                    "Conectado, porém sem acesso validado à internet.", "network_open_settings"
                )
                else -> ScanCheckBuilder.ok(
                    "network_conn", ScanCategory.NETWORK, "Conexão de rede",
                    "Rede ativa e com acesso à internet."
                )
            }
        )

        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Rede móvel"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Desconhecido"
        }

        results.add(
            if (network == null) {
                ScanCheckBuilder.critical(
                    "network_type", ScanCategory.NETWORK, "Tipo de rede",
                    "Sem rede disponível.", "network_open_settings"
                )
            } else {
                ScanCheckBuilder.ok(
                    "network_type", ScanCategory.NETWORK, "Tipo de rede",
                    "Conectado via $transport."
                )
            }
        )

        results.add(checkDns())

        return results
    }

    private fun checkDns(): ScanCheck {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r).apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<InetAddress?> { InetAddress.getByName("dns.google") }
            val resolved = future.get(6, TimeUnit.SECONDS)
            if (resolved != null) {
                ScanCheckBuilder.ok(
                    "network_dns", ScanCategory.NETWORK, "Resolução de DNS",
                    "DNS respondendo normalmente."
                )
            } else {
                ScanCheckBuilder.warning(
                    "network_dns", ScanCategory.NETWORK, "DNS sem resposta",
                    "Falha ao resolver nomes de domínio.", "network_dns_fix"
                )
            }
        } catch (e: Exception) {
            ScanCheckBuilder.warning(
                "network_dns", ScanCategory.NETWORK, "DNS sem resposta",
                "Não foi possível resolver um host de teste (${e.javaClass.simpleName}).",
                "network_dns_fix"
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
