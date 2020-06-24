package redis.clients.jedis;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.IOUtils;
import redis.clients.jedis.util.RedisInputStream;
import redis.clients.jedis.util.RedisOutputStream;
import redis.clients.jedis.util.SafeEncoder;
/**
 * Jedis客户端实现连接服务器的类
 * 其中Client类和Jedis类的服务器连接操作皆是通过此类实现的
 */
public class Connection implements Closeable {

  private static final byte[][] EMPTY_ARGS = new byte[0][];

  private JedisSocketFactory jedisSocketFactory;
  private Socket socket;
  private RedisOutputStream outputStream;
  private RedisInputStream inputStream;
  //标识连接是否处于断开状态
  private boolean broken = false;

  public Connection() {
    this(Protocol.DEFAULT_HOST);
  }

  public Connection(final String host) {
    this(host, Protocol.DEFAULT_PORT);
  }

  public Connection(final String host, final int port) {
    this(host, port, false);
  }

  public Connection(final String host, final int port, final boolean ssl) {
    this(host, port, ssl, null, null, null);
  }

  public Connection(final String host, final int port, final boolean ssl,
      SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
      HostnameVerifier hostnameVerifier) {
    //构造函数使用默认的DefaultJedisSocketFactory套接字工厂来完成连接的初始化创建
    this(new DefaultJedisSocketFactory(host, port, Protocol.DEFAULT_TIMEOUT,
        Protocol.DEFAULT_TIMEOUT, ssl, sslSocketFactory, sslParameters, hostnameVerifier));
  }

  public Connection(final JedisSocketFactory jedisSocketFactory) {
    this.jedisSocketFactory = jedisSocketFactory;
  }

  public Socket getSocket() {
    return socket;
  }

  public int getConnectionTimeout() {
    return jedisSocketFactory.getConnectionTimeout();
  }

  public int getSoTimeout() {
    return jedisSocketFactory.getSoTimeout();
  }

  public void setConnectionTimeout(int connectionTimeout) {
    jedisSocketFactory.setConnectionTimeout(connectionTimeout);
  }

  public void setSoTimeout(int soTimeout) {
    jedisSocketFactory.setSoTimeout(soTimeout);
  }

  public void setTimeoutInfinite() {
    try {
      if (!isConnected()) {
        connect();
      }
      socket.setSoTimeout(0);
    } catch (SocketException ex) {
      broken = true;
      throw new JedisConnectionException(ex);
    }
  }

  public void rollbackTimeout() {
    try {
      socket.setSoTimeout(jedisSocketFactory.getSoTimeout());
    } catch (SocketException ex) {
      broken = true;
      throw new JedisConnectionException(ex);
    }
  }

  public void sendCommand(final ProtocolCommand cmd, final String... args) {
    final byte[][] bargs = new byte[args.length][];
    for (int i = 0; i < args.length; i++) {
      bargs[i] = SafeEncoder.encode(args[i]);
    }
    sendCommand(cmd, bargs);
  }

  public void sendCommand(final ProtocolCommand cmd) {
    sendCommand(cmd, EMPTY_ARGS);
  }

  public void sendCommand(final ProtocolCommand cmd, final byte[]... args) {
    try {
      //建立socket连接
      connect();
      //发送命令
      Protocol.sendCommand(outputStream, cmd, args);
    } catch (JedisConnectionException ex) {
      /*
       * When client send request which formed by invalid protocol, Redis send back error message
       * before close connection. We try to read it to provide reason of failure.
       */
      try {
        //读取服务端返回的错误信息，并进行相应的记录和抛出
        String errorMessage = Protocol.readErrorLineIfPossible(inputStream);
        if (errorMessage != null && errorMessage.length() > 0) {
          ex = new JedisConnectionException(errorMessage, ex.getCause());
        }
      } catch (Exception e) {
        /*
         * Catch any IOException or JedisConnectionException occurred from InputStream#read and just
         * ignore. This approach is safe because reading error message is optional and connection
         * will eventually be closed.
         */
      }
      // Any other exceptions related to connection?
      broken = true;
      throw ex;
    }
  }

  public String getHost() {
    return jedisSocketFactory.getHost();
  }

  public void setHost(final String host) {
    jedisSocketFactory.setHost(host);
  }

  public int getPort() {
    return jedisSocketFactory.getPort();
  }

  public void setPort(final int port) {
    jedisSocketFactory.setPort(port);
  }

  public void connect() {
    //如果当前socket未处于连接状态，则进行连接
    if (!isConnected()) {
      try {
        //使用jedisSocketFactory创建socket对象
        socket = jedisSocketFactory.createSocket();
        //创建RedisOutputStream和RedisInputStream对象
        // （这两个流对象在系统流基础上做了封装，增加了缓冲区，大小为8196字节）
        outputStream = new RedisOutputStream(socket.getOutputStream());
        inputStream = new RedisInputStream(socket.getInputStream());
      } catch (IOException ex) {
        broken = true;
        throw new JedisConnectionException("Failed connecting to "
            + jedisSocketFactory.getDescription(), ex);
      }
    }
  }

  @Override
  public void close() {
    disconnect();
  }

  public void disconnect() {
    //当前处于连接状态
    if (isConnected()) {
      try {
        //刷新缓冲区，在关闭之前将输出流缓冲区的内容全部刷出
        outputStream.flush();
        //关闭socket连接
        socket.close();
      } catch (IOException ex) {
        //关闭socket时发生异常，则记录标识，并抛出异常
        broken = true;
        throw new JedisConnectionException(ex);
      } finally {
        //最后再次去关闭socket，防止之前发生异常关闭失败
        IOUtils.closeQuietly(socket);
      }
    }
  }

  public boolean isConnected() {
    //Jedis保持连接的条件：socket不为空&&socket处于绑定某地址的状态&&socket未处于关闭状态
    //&&socket是连接状态&&socket的输入和输出两部分都处于连接状态
    return socket != null && socket.isBound() && !socket.isClosed() && socket.isConnected()
        && !socket.isInputShutdown() && !socket.isOutputShutdown();
  }

  public String getStatusCodeReply() {
    //刷新输出流缓冲区，保证要发送的信息全部发送完毕（无缓存未发送的数据）
    flush();
    //获取服务器返回操作执行状态码
    final byte[] resp = (byte[]) readProtocolWithCheckingBroken();
    //如果为null，则直接返回null
    if (null == resp) {
      return null;
    } else {
      //否则，将服务器结果解码后返回
      return SafeEncoder.encode(resp);
    }
  }

  public String getBulkReply() {
    final byte[] result = getBinaryBulkReply();
    if (null != result) {
      return SafeEncoder.encode(result);
    } else {
      return null;
    }
  }

  public byte[] getBinaryBulkReply() {
    flush();
    return (byte[]) readProtocolWithCheckingBroken();
  }

  public Long getIntegerReply() {
    flush();
    return (Long) readProtocolWithCheckingBroken();
  }

  public List<String> getMultiBulkReply() {
    return BuilderFactory.STRING_LIST.build(getBinaryMultiBulkReply());
  }

  @SuppressWarnings("unchecked")
  public List<byte[]> getBinaryMultiBulkReply() {
    flush();
    return (List<byte[]>) readProtocolWithCheckingBroken();
  }

  @Deprecated
  public List<Object> getRawObjectMultiBulkReply() {
    return getUnflushedObjectMultiBulkReply();
  }

  @SuppressWarnings("unchecked")
  public List<Object> getUnflushedObjectMultiBulkReply() {
    return (List<Object>) readProtocolWithCheckingBroken();
  }

  public List<Object> getObjectMultiBulkReply() {
    //刷新缓冲区，防止指令存储在写缓冲区中没有发送到服务器
    flush();
    //读取服务器返回的执行结果信息
    return getUnflushedObjectMultiBulkReply();
  }

  @SuppressWarnings("unchecked")
  public List<Long> getIntegerMultiBulkReply() {
    flush();
    return (List<Long>) readProtocolWithCheckingBroken();
  }

  public Object getOne() {
    flush();
    return readProtocolWithCheckingBroken();
  }

  public boolean isBroken() {
    return broken;
  }

  protected void flush() {
    try {
      outputStream.flush();
    } catch (IOException ex) {
      broken = true;
      throw new JedisConnectionException(ex);
    }
  }
  /**读取协议和检查中断*/
  protected Object readProtocolWithCheckingBroken() {
    //检查中断
    //如果当前连接已经断开，则不能够做任何操作，抛出相应的异常
    if (broken) {
      throw new JedisConnectionException("Attempting to read from a broken connection");
    }
    //读取协议
    try {
      //读取服务器返回信息
      return Protocol.read(inputStream);
    } catch (JedisConnectionException exc) {
      //执行读取操作过程中，如果发生异常，则标识断开连接
      broken = true;
      throw exc;
    }
  }

  public List<Object> getMany(final int count) {
    flush();
    final List<Object> responses = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      try {
        responses.add(readProtocolWithCheckingBroken());
      } catch (JedisDataException e) {
        responses.add(e);
      }
    }
    return responses;
  }
}
