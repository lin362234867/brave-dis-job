package com.brave.config;

import com.brave.util.IpUtil;
import com.brave.util.JobUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newFixedThreadPool;

@Slf4j
@Component
@Data
public class ClientConfiguration {

    public static CuratorFramework curatorFramework;

    public InterProcessMutex mutex;

    public static ConcurrentHashMap<String,InterProcessMutex> mutexConcurrentHashMap;

    @Value("${distribute.job.registcenter}")
    private String connect;

    @Value("${server.port}")
    private String port;

    @Value("${brave.switcher}")
    public String switcher;


    private static int BASE_SLEEP_TIME_MS = 2000;
    private static int MAX_RETRIES = 5;

    private ExecutorService pool = newFixedThreadPool(Runtime.getRuntime().availableProcessors());


    @Autowired JobUtil jobUtil;

    /**
     * connect
     */
    public void connect(){
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME_MS, MAX_RETRIES);
        if(curatorFramework == null) {
            curatorFramework = CuratorFrameworkFactory.newClient(connect,retryPolicy);
            curatorFramework.start();
        }
        log.info("curator framework init success!");

    }

    /**
     * 对每个子节点增加watcher，如果节点被删除或者断开，5秒钟后自动重新连接
     * @throws Exception
     */
    public void nodeWatch() {
        log.info("first fuck you...");
        //对每个节点增加watcher
        JobUtil.JOB_NODE_MAP.forEach((job,jobProperty) ->{
            log.info("inner fuck you....");
            final PathChildrenCache
                childrenCache = new PathChildrenCache(curatorFramework,jobProperty.getRoot(),true);
            log.info("inner fuck you:{}",jobProperty.getSubNode());
            try {
                childrenCache.start();
            } catch (Exception e) {
                log.info("add sub node {} watcher exception:{}",job,e);
            }
            childrenCache.getListenable().addListener((client, event) -> {
                switch (event.getType()) {
                    case CONNECTION_LOST:
                        log.info("this  path connection has been lost:{}",event.getData().getPath());
                        reRegisterSubNode(event.getData().getPath());
                        break;
                    case CHILD_REMOVED:
                        log.info("this  path has been removed:{}",event.getData().getPath());
                        TimeUnit.SECONDS.sleep(5);
                        reRegisterSubNode(event.getData().getPath());
                        break;
                    default:
                        break;
                }
            });
        });

    }

    /**
     *
     * @param path
     */
    public void reRegisterSubNode(@NotNull String path){
        if(path.contains(IpUtil.getLocalIP()) && path.contains(port)){
            log.info("{}重新注册！",path);
            try {
                TimeUnit.SECONDS.sleep(5);
                registerLocalNode(path);
            } catch (Exception e) {
                log.info("reRegister subNode exception:{}",e);
            }
        }

    }

    /**
     * 初始化节点
     */
    @PostConstruct
    public void init(){
        if(null != switcher && !"on".equals(switcher)){
            log.info("本台服务总开关关闭。。。。。");
            return;
        }
        //链接
        connect();
        mutexConcurrentHashMap = new ConcurrentHashMap<>();
        //注册节点
        registerAllLocalNode();
        JobUtil.JOB_NODE_MAP.forEach((s, jobProperty) -> {
            try{
                Optional<Stat> statOptional = Optional.ofNullable(curatorFramework.checkExists().forPath(jobProperty.getLock()));
                InterProcessMutex mutex = statOptional.map(stat -> {
                    InterProcessMutex mutex1 = new InterProcessMutex(curatorFramework,jobProperty.getLock());
                    return mutex1;
                }).orElseGet(()->{
                        try {
                            curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(jobProperty.getLock(), new byte[0]);
                            InterProcessMutex mutex2 = new InterProcessMutex(curatorFramework,jobProperty.getLock());
                            return mutex2;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return null;
                    }

                );

                this.mutex = mutex;
                if(mutex != null) {
                    mutexConcurrentHashMap.putIfAbsent(s,mutex);
                }
            }catch(Exception e){
                log.info("初始化client节点异常:{}",e.getMessage());
            }
        });
        try{
            nodeWatch();
        }catch (Exception e) {
            log.info("子节点监听异常:{}",e);
        }
    }

    /**
     * register node to zookeeper
     */
    public void registerAllLocalNode() {

        JobUtil.JOB_NODE_MAP.forEach((s, jobProperty) -> {
            Stat stat;
            Stat stat1;
            try {
                stat = curatorFramework.checkExists().forPath(jobProperty.getRoot());

                if(stat == null) {
                    curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(jobProperty.getRoot());
                }

                stat1 = curatorFramework.checkExists().forPath(jobProperty.getSubNode());
                if(stat1 == null) {
                    curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(jobProperty.getSubNode(),null);
                }
            } catch (Exception e) {
                log.info("register the local node  exception:{}",e);
            }
            /**
             * 监听数据节点的变化情况
             */
            final NodeCache
                nodeCache = new NodeCache(curatorFramework, jobProperty.getSubNode(), false);
            try {
                nodeCache.start(true);
                nodeCache.getListenable().addListener(() -> {
                        try{
                            log.info("path :{} data changed:{} ",new String(nodeCache.getCurrentData().getData()),new String(nodeCache.getCurrentData().getPath()));

//                            jobWorker.work(new String(nodeCache.getCurrentData().getData()),new String(nodeCache.getCurrentData().getPath()));
                        }catch(Exception e){
                            log.info("fuck you");
                        }

                    }
                );
            } catch (Exception e) {
                log.info("监听数据节点异常:{}",e);
            }
        });

    }


    /**
     * 注册节点
     * @param path
     */
    private void registerLocalNode(String path) {
        Stat stat1;
        try {

            stat1 = curatorFramework.checkExists().forPath(path);
            if(stat1 == null) {
                curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(path,null);
            }
        } catch (Exception e) {
            log.info("register the local node  exception:{}",e);
        }

    }


    /**
     * 获取根节点下所有子节点,并拼装成完整节点格式
     * @param node
     * @return
     */
    public Optional<List<String>> getSubNodes(String node) {
        try {
            List<String> subNodes = curatorFramework.getChildren().forPath(node);
            if(subNodes != null && subNodes.size()>0) {
                return Optional.ofNullable(subNodes.stream()
                    .map(no -> { log.info("分片的节点:{}",no);return node + "/" + no; })
                    .collect(Collectors.toList()));
            }

        } catch (Exception e) {
            log.info("获取分片节点出现异常:{}",e.getMessage());
        }
        return Optional.ofNullable(null);
    }

    /**
     * 修改某个节点的data值
     * @param value
     */
    public void setNodeData(@NotNull String path,@NotNull String value) {
        try {
            curatorFramework.setData().forPath(path,value.getBytes());
        } catch (Exception e) {
            log.info("修改节点:{} 出现异常:{}",path,e.getMessage());
        }
    }

    /**
     * 关闭
     */
    public void close() {
        curatorFramework.close();
        mutexConcurrentHashMap = new ConcurrentHashMap<>();
    }

}
