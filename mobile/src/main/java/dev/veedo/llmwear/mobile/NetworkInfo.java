package dev.veedo.llmwear.mobile;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

final class NetworkInfo {
    private NetworkInfo() {
    }

    static String firstIpv4Address() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
