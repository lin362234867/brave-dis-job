package com.brave.util;

import com.brave.vo.JobLogPojo;
import com.brave.vo.JobProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newFixedThreadPool;

/**
 * @author junzhang
 */
@Slf4j
@Component
@Data
@ConfigurationProperties("brave")
public class JobUtil {

//    @Autowired JobLogService jobLogService;

    public static String MAIN_JOB_TYPE = "main-job";

    public static String WORKER_JOB_TYPE = "worker-job";

    public static ExecutorService poll = newFixedThreadPool(8);

    public static String DEPOSIT_SWITCHER = "on";

    public static ConcurrentHashMap<String,JobProperty> JOB_NODE_MAP = new ConcurrentHashMap<>();

    @Value("${server.port}")
    public String port;

    public static String NODE = null;

    public List<JobProperty> jobs = new ArrayList<>();

    @PostConstruct
    public void init() {
        clear();
        if(null != jobs && jobs.size() >0) {
            jobs.parallelStream().forEach(jobProperty -> {
                String job = jobProperty.getJob();
                if(jobProperty.getLock() == null || jobProperty.getLock().equals("")) {
                    jobProperty.setLock("/" + job +"-lock");
                }
                String root = "/" + job;
                String subNode = root + "/" + IpUtil.getLocalIP().toString() + "-" + port;
                jobProperty.setRoot(root);
                jobProperty.setSubNode(subNode);
                jobProperty.setExecutor(IpUtil.getLocalIP().toString() + "-" + port);
                jobProperty.setLog("/job/log/"+job);
                JOB_NODE_MAP.putIfAbsent(job,jobProperty);
            });
        }
    }

    /**
     * round robin
     */
    public static Map<String,List<Integer>> allotOfAverage(List<String> users,List<Integer> tasks){
        //保存分配的信息
        Map<String,List<Integer>> allot=new ConcurrentHashMap<>();
        if(users!=null&&users.size()>0&&tasks!=null&&tasks.size()>0){
            for(int i=0;i<tasks.size();i++){
                int j=i%users.size();
                addItem(users, tasks, allot, i, j);
            }
        }
        return allot;
    }

    /**
     * 平均分配
     * @param users
     * @param tasks
     * @return
     */
    public static Map<String,List<Integer>> allotOfAverage_2(List<String> users,List<Integer> tasks) {
        Map<String,List<Integer>> allot=new ConcurrentHashMap<>(); //保存分配的信息
        if(users!=null&&users.size()>0&&tasks!=null&&tasks.size()>0){
            int every_count = tasks.size()/users.size();

            for(int j = 0, i = 1, k=0     ; j < tasks.size(); j++,i++) {
                if(i> every_count ){
                    i = 1;
                    if(k < users.size()-1){
                        k++;
                    }
                }
                addItem(users, tasks, allot, j, k);
            }

        }
        return allot;
    }

    private static void addItem(List<String> users, List<Integer> tasks,
        Map<String, List<Integer>> allot, int j, int k) {
        if(allot.containsKey(users.get(k))){
            List<Integer> list=allot.get(users.get(k));
            list.add(tasks.get(j));
            allot.put(users.get(k), list);
        }else{
            List<Integer> list=new ArrayList<>();
            list.add(tasks.get(j));
            allot.put(users.get(k), list);
        }
    }

    /**
     * 新增job日志
     * @param jobLogPojo
     */
    public  int addJobLog(JobLogPojo jobLogPojo) {
        try{
            log.info("add job log :{}",jobLogPojo);
//            return jobLogService.addJobLog(jobLogPojo);
            return 1;
        }catch(Exception e){
            log.info("新增job日志失败:{},异常是:{}",jobLogPojo,e);
        }
        return 0;
    }

    /**
     *
     * @param jobLogPojo
     */
    public  void modifyJobLog(JobLogPojo jobLogPojo) {
        try{
            log.info("modify job log :{}",jobLogPojo);
//            jobLogService.modifyJobLog(jobLogPojo);
        }catch(Exception e){
            log.info("修改JOB日志失败：{},异常是:{}",jobLogPojo,e);
        }
    }

    /**
     * init the map
     */
    public void clear(){
        JOB_NODE_MAP = null;
        JOB_NODE_MAP = new ConcurrentHashMap<>();
    }

}

