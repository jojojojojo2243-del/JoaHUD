package com.joassam.hud;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 핫스팟/와이파이의 모든 브로드캐스트 주소 + 자기 자신(테스트용)으로 UDP 전송 */
public class UdpSender {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public static void send(final String json) {
        sendBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON(길안내) 또는 JPEG(티맵 화면) 바이트 전송 */
    public static void sendBytes(final byte[] data) {
        EXEC.execute(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                for (InetAddress a : targets()) {
                    try {
                        sock.send(new DatagramPacket(data, data.length, a, NavData.PORT));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
        });
    }

    private static Set<InetAddress> targets() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try {
            set.add(InetAddress.getByName("127.0.0.1"));
            set.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                        set.add(ia.getBroadcast());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return set;
    }
}
