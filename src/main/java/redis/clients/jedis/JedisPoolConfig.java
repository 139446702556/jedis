package redis.clients.jedis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

public class JedisPoolConfig extends GenericObjectPoolConfig {
  public JedisPoolConfig() {
    // defaults to make your life with connection pool easier :)
    //如果为true，表示有一个idle object evitor线程对idle object进行扫描，如果validate失败，此object会被从pool中drop掉；这一项只有在timeBetweenEvictionRunsMillis大于0时才有意义；默认是false
    //在空闲时检查有效性，默认为false
    setTestWhileIdle(true);
    //设置逐出连接的最小空闲时间
    setMinEvictableIdleTimeMillis(60000);
    //表示idle object evitor两次扫描之间要sleep的毫秒数，逐出扫描的时间间隔（毫秒），如果为负数，则不运行逐出线程，默认为-1
    setTimeBetweenEvictionRunsMillis(30000);
    //每次逐出检查时，逐出的最大数目；如果为负数就是：1/abs(n),默认为3
    setNumTestsPerEvictionRun(-1);
  }
}
