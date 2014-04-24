package org.github.rsyncbackup.notify;

import java.util.ArrayList;
import java.util.List;

import org.github.rsyncbackup.RSyncBackup;
import org.github.rsyncbackup.RSyncBackup.BackupStatistics;
import org.github.rsyncbackup.impl.BackupConf.ConfHost;
import org.github.rsyncbackup.tools.ZabbixSender;
import org.github.rsyncbackup.tools.ZabbixSender.ZabbixSenderItem;
import org.github.rsyncbackup.tools.ZabbixSender.ZabbixSenderResponse;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZabbixNotifier
{
    public static void notify(ConfHost conf, BackupStatistics statistics)
    {
        Logger LOG=LoggerFactory.getLogger(ZabbixNotifier.class);
        
        if (conf.notifyZabbixServer==null || conf.notifyZabbixServer.isEmpty()) return;
        if (conf.notifyZabbixHost==null || conf.notifyZabbixHost.isEmpty()) return;
        
        LOG.debug("Sending notify via zabbix");
        
        List<ZabbixSenderItem> items=new ArrayList<>();
        
        if (statistics.backupOk)
        {
            Duration duration=new Duration(statistics.startTime.toDateTime(),statistics.endTime.toDateTime());
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.status", "OK: Backup finished"));
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.duration", ""+duration.getStandardSeconds()));
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.lastSuccessfull", statistics.endTime.toDateTime().toString("yyyy-MM-dd HH:mm:ss")));
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.changedFileCount", ""+statistics.changedFileCount));
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.changedFileSize", ""+statistics.changedFileSize));
        }
        else
        {
            items.add(new ZabbixSenderItem(conf.notifyZabbixHost, "backup.status", "ERR: See log for details"));
        }
        
        int retryCount=(conf.notifyZabbixRetryCount!=null)?conf.notifyZabbixRetryCount:1;
        if (retryCount<1) retryCount=1;
        
        for(int retryNum=1;retryNum<=retryCount;retryNum++)
        {
            try
            {
                // TODO: check zabbix response and log result
                ZabbixSenderResponse response=new ZabbixSender(conf.notifyZabbixServer).sendItems(items);
                LOG.info("Sent notify via zabbix: "+response);
                break;
            }
            catch (Exception ex)
            {
                if (retryNum<retryCount)
                {
                    LOG.warn("Failed to send notify via zabbix - retrying ({})",ex.toString());
                    try
                    {
                        Thread.sleep(500);
                    }
                    catch (InterruptedException iex)
                    {
                        
                    }
                }
                else
                {
                    LOG.warn("Failed to send notify via zabbix",ex);
                }
            }
        }
        
    }
}
