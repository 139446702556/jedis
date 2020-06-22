package redis.clients.jedis;

import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
/**JedisSocketFactory接口的默认实现类*/
public class DefaultJedisSocketFactory implements JedisSocketFactory {

  private String host;
  private int port;
  private int connectionTimeout;
  private int soTimeout;
  private boolean ssl;
  private SSLSocketFactory sslSocketFactory;
  private SSLParameters sslParameters;
  private HostnameVerifier hostnameVerifier;

  public DefaultJedisSocketFactory(String host, int port, int connectionTimeout, int soTimeout,
      boolean ssl, SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
      HostnameVerifier hostnameVerifier) {
    this.host = host;
    this.port = port;
    this.connectionTimeout = connectionTimeout;
    this.soTimeout = soTimeout;
    this.ssl = ssl;
    this.sslSocketFactory = sslSocketFactory;
    this.sslParameters = sslParameters;
    this.hostnameVerifier = hostnameVerifier;
  }

  @Override
  public Socket createSocket() throws IOException {
    Socket socket = null;
    try {
      //创建新的socket对象
      socket = new Socket();
      // ->@wjw_add
      //进行socket属性配置
      socket.setReuseAddress(true);
      socket.setKeepAlive(true); // Will monitor the TCP connection is
      // valid
      socket.setTcpNoDelay(true); // Socket buffer Whetherclosed, to
      // ensure timely delivery of data
      socket.setSoLinger(true, 0); // Control calls close () method,
      // the underlying socket is closed
      // immediately
      // <-@wjw_add
      //socket建立连接
      socket.connect(new InetSocketAddress(getHost(), getPort()), getConnectionTimeout());
      //设置读取数据超时时间
      socket.setSoTimeout(getSoTimeout());
      //如果连接需要加密，即需要ssl连接
      if (ssl) {
        //如果没有初始化sslSocketFactory对象，则进行初始化（使用默认的ssl套接字工厂）
        if (null == sslSocketFactory) {
          sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        //创建ssl套接字连接
        socket = sslSocketFactory.createSocket(socket, getHost(), getPort(), true);
        //如果有sslParameters，则设置
        if (null != sslParameters) {
          ((SSLSocket) socket).setSSLParameters(sslParameters);
        }
        //如果存在验证器，则对当前主机以及socket会话进行校验，失败则抛出对应异常
        if ((null != hostnameVerifier)
            && (!hostnameVerifier.verify(getHost(), ((SSLSocket) socket).getSession()))) {
          String message = String.format(
            "The connection to '%s' failed ssl/tls hostname verification.", getHost());
          throw new JedisConnectionException(message);
        }
      }
      //返回创建好的socket对象
      return socket;
    } catch (Exception ex) {
      //如果创建socket过程中发生异常，则关闭掉刚创建的socket对象
      if (socket != null) {
        socket.close();
      }
      throw ex;
    }
  }

  @Override
  public String getDescription() {
    return host + ":" + port;
  }

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public void setHost(String host) {
    this.host = host;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public void setPort(int port) {
    this.port = port;
  }

  @Override
  public int getConnectionTimeout() {
    return connectionTimeout;
  }

  @Override
  public void setConnectionTimeout(int connectionTimeout) {
    this.connectionTimeout = connectionTimeout;
  }

  @Override
  public int getSoTimeout() {
    return soTimeout;
  }

  @Override
  public void setSoTimeout(int soTimeout) {
    this.soTimeout = soTimeout;
  }
}
