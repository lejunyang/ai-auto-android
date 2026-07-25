package dev.aiauto.android.bridge.lan

/**
 * 测试用途：验证 Android LAN 目录拒绝 VPN 和无私网地址的网络，并确保出站 socket
 * 只绑定用户明确选择且身份仍稳定的 Network。
 */

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidLanNetworkTest {
    @Test
    fun `directory exposes only non vpn wifi or ethernet with private addresses`() {
        val manager = mockk<ConnectivityManager>()
        val wifi = network(
            handle = 42,
            manager = manager,
            interfaceName = "wlan0",
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            notVpn = true,
            address = "192.168.50.20",
        )
        val vpn = network(
            handle = 43,
            manager = manager,
            interfaceName = "tun0",
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            notVpn = false,
            address = "10.8.0.2",
        )
        val public = network(
            handle = 44,
            manager = manager,
            interfaceName = "eth0",
            transport = NetworkCapabilities.TRANSPORT_ETHERNET,
            notVpn = true,
            address = "203.0.113.5",
        )
        every { manager.allNetworks } returns arrayOf(
            wifi.network,
            vpn.network,
            public.network,
        )

        val directory = AndroidLanNetworkDirectory(manager)

        assertEquals(
            listOf(LanLocalInterface("android-network-42-wlan0", "wlan0", "wifi")),
            directory.interfaces(),
        )
    }

    @Test
    fun `bind uses exact selected network and fails after identity disappears`() {
        val manager = mockk<ConnectivityManager>()
        val selected = network(
            handle = 42,
            manager = manager,
            interfaceName = "wlan0",
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            notVpn = true,
            address = "192.168.50.20",
        )
        every { manager.allNetworks } returns arrayOf(selected.network)
        every { selected.network.bindSocket(any<Socket>()) } returns Unit
        val directory = AndroidLanNetworkDirectory(manager)
        val identity = LanLocalInterface("android-network-42-wlan0", "wlan0", "wifi")
        val socket = Socket()
        try {
            directory.bind(socket, identity)
            verify(exactly = 1) { selected.network.bindSocket(socket) }
            assertEquals(identity, directory.current(identity))

            every { manager.allNetworks } returns emptyArray()
            assertNull(directory.current(identity))
        } finally {
            socket.close()
        }
    }

    private fun network(
        handle: Long,
        manager: ConnectivityManager,
        interfaceName: String,
        transport: Int,
        notVpn: Boolean,
        address: String,
    ): NetworkFixture {
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        val links = mockk<LinkProperties>()
        val linkAddress = mockk<LinkAddress>()
        every { network.networkHandle } returns handle
        every {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } returns notVpn
        every { capabilities.hasTransport(any()) } answers {
            firstArg<Int>() == transport
        }
        every { links.interfaceName } returns interfaceName
        every { linkAddress.address } returns InetAddress.getByName(address)
        every { links.linkAddresses } returns listOf(linkAddress)
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { manager.getLinkProperties(network) } returns links
        return NetworkFixture(network)
    }

    private data class NetworkFixture(
        val network: Network,
    )
}
