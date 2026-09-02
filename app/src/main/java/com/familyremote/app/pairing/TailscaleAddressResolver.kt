package com.familyremote.app.pairing

import com.familyremote.app.common.Constants
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Detects this device's own Tailscale IPv4 address, per PLAN.md §3.
 * Prefers an interface literally named "tailscale0"; otherwise falls back to the first IPv4
 * address found anywhere in Tailscale's CGNAT range (100.64.0.0/10).
 */
object TailscaleAddressResolver {

    fun resolve(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return null

        val byName = interfaces.firstOrNull { it.name == TAILSCALE_INTERFACE_NAME }
        byName?.let { iface -> findCgnatAddress(iface)?.let { return it } }

        for (iface in interfaces) {
            findCgnatAddress(iface)?.let { return it }
        }
        return null
    }

    private fun findCgnatAddress(iface: NetworkInterface): String? {
        val addresses = runCatching { iface.inetAddresses?.toList() }.getOrNull() ?: return null
        for (address in addresses) {
            if (address is Inet4Address && isTailscaleCgnatAddress(address)) {
                return address.hostAddress
            }
        }
        return null
    }

    private fun isTailscaleCgnatAddress(address: Inet4Address): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toUnsigned()
        val second = bytes[1].toUnsigned()
        return first == Constants.TAILSCALE_CGNAT_FIRST_OCTET &&
            second in Constants.TAILSCALE_CGNAT_SECOND_OCTET_MIN..Constants.TAILSCALE_CGNAT_SECOND_OCTET_MAX
    }

    private fun Byte.toUnsigned(): Int = this.toInt() and 0xFF

    private const val TAILSCALE_INTERFACE_NAME = "tailscale0"
}
