package com.serialtransfer.app;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lightweight UDP discovery for hotspot and home LAN. Server advertises no secrets. */
final class LanDiscovery {
    static final int PORT = 8766;
    static final String REQUEST = "MEDIATEKA_DISCOVER_1";
    static final String ANSWER = "MEDIATEKA_HERE_1";
    private LanDiscovery() { }

    static List<String> find(Context context) {
        Set<String> found = new LinkedHashSet<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setBroadcast(true);
            socket.setSoTimeout(650);
            byte[] request = REQUEST.getBytes(StandardCharsets.US_ASCII);
            Set<String> broadcasts = new LinkedHashSet<>();
            broadcasts.add("255.255.255.255");
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                    int value = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                    broadcasts.add(ipv4(value));
                }
            } catch (Exception ignored) { }
            for (String b : broadcasts) {
                try {
                    socket.send(new DatagramPacket(request, request.length,
                            InetAddress.getByName(b), PORT));
                } catch (Exception ignored) { }
            }
            long stop = android.os.SystemClock.elapsedRealtime() + 1900;
            byte[] answer = new byte[72];
            while (android.os.SystemClock.elapsedRealtime() < stop && found.size() < 12) {
                DatagramPacket packet = new DatagramPacket(answer, answer.length);
                try {
                    socket.receive(packet);
                    String result = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.US_ASCII);
                    InetAddress sender = packet.getAddress();
                    if (ANSWER.equals(result) && sender != null
                            && (sender.isSiteLocalAddress() || sender.isLoopbackAddress())) {
                        String candidate = sender.getHostAddress();
                        if (candidate.matches("[0-9.]{7,15}")) found.add(candidate);
                    }
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } catch (Exception ignored) { }
        finally { if (socket != null) socket.close(); }
        return new ArrayList<>(found);
    }

    private static String ipv4(int value) {
        return (value & 255) + "." + ((value >>> 8) & 255) + "."
                + ((value >>> 16) & 255) + "." + ((value >>> 24) & 255);
    }
}
