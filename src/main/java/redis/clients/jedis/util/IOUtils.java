package redis.clients.jedis.util;

import java.io.IOException;
import java.net.Socket;

public class IOUtils {
  private IOUtils() {
  }

  public static void closeQuietly(Socket sock) {
    // It's same thing as Apache Commons - IOUtils.closeQuietly()
    //如果给定的socket未释放，则进行连接释放
    if (sock != null) {
      try {
        sock.close();
      } catch (IOException e) {
        // ignored
      }
    }
  }
}
