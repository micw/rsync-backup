package org.github.rsyncbackup.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.github.rsyncbackup.RSyncBackup;
import org.github.rsyncbackup.impl.BackupConf.ConfHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Runs a given number of backups in parallel
 * @author mwyraz
 */
public class BackupScheduler implements Runnable
{
    protected Logger LOG=LoggerFactory.getLogger(getClass());
    protected final int numberOfParallelBackups;
    protected final List<ConfHost> hostsTodo;
    protected final Map<String,ConfHost> hostsInProgress;
    protected IBackupExecutor executor;
    
    public BackupScheduler(int numberOfParallelBackups, List<ConfHost> hosts, IBackupExecutor executor)
    {
        this.numberOfParallelBackups=numberOfParallelBackups;
        this.hostsTodo=new ArrayList<>(hosts);
        this.hostsInProgress=new HashMap<>();
        this.executor=executor;
    }
    
    public void executeBackups()
    {
        Thread[] threads=new Thread[numberOfParallelBackups];
        
        LOG.info("Starting {} parallel executors",numberOfParallelBackups);
        for (int i=0;i<numberOfParallelBackups;i++)
        {
            threads[i]=new Thread(this,"BackupExecutor "+(i+1)+"/"+numberOfParallelBackups);
            threads[i].start();
        }
        
        boolean running=true;
        LOG.info("Waiting for backups to finish");
        while(running)
        {
            running=false;
            for (Thread thread: threads)
            {
                if (thread.isAlive())
                {
                    running=true;
                    break;
                }
            }
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException ex)
            {
                
            }
        }
        LOG.info("All backups finished");
    }
    
    protected boolean executeNextBackup(String threadName)
    {
        RSyncBackup.setThreadHostname(null); // for correct log target
        ConfHost hostTask=null;
        synchronized(this)
        {
            if (hostsTodo.isEmpty()) return false;
            
            for (ConfHost host: hostsTodo)
            {
                if (hostsInProgress.containsKey(host.scheduleGroup)) continue;
                hostTask=host;
                hostsTodo.remove(host);
                break;
            }
            
            if (hostTask==null) return true; // Retry later
            
            hostsInProgress.put(hostTask.scheduleGroup,hostTask);
        }
        
        try
        {
            if (!hostTask.scheduleEnabled)
            {
                LOG.info("Skipping disabled schedule for {}",hostTask.host,threadName);
            }
            else
            {
                LOG.info("Running {} on {}",hostTask.host,threadName);
                try
                {
                    executor.runBackupForHost(hostTask.host);
                }
                finally
                {
                    RSyncBackup.setThreadHostname(null); // for correct log target
                    LOG.info("Finished {} on {}",hostTask.host,threadName);
                }
            }
        }
        catch (Throwable th)
        {
            LOG.error("Fatal error",th);
        }
        finally
        {
            synchronized(this)
            {
                hostsInProgress.remove(hostTask.scheduleGroup);
            }
        }
        return true;
    }
    
    @Override
    public void run()
    {
        String threadName=Thread.currentThread().getName();
        while (executeNextBackup(threadName))
        {
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException ex)
            {
                // ignored
            }
        }
    }
    
    
}
