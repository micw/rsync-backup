package org.github.rsyncbackup;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.github.rsyncbackup.impl.BackupConf;
import org.github.rsyncbackup.impl.BackupConf.ConfHost;
import org.github.rsyncbackup.impl.BackupConf.ConfVolume;
import org.github.rsyncbackup.impl.BackupScheduler;
import org.github.rsyncbackup.impl.HostDir;
import org.github.rsyncbackup.impl.IBackupExecutor;
import org.github.rsyncbackup.notify.ZabbixNotifier;
import org.joda.time.Duration;
import org.joda.time.LocalDateTime;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;

public class RSyncBackup implements IBackupExecutor
{
    public static Logger LOG;
    public static void main(String[] args) throws Exception
    {
        // FIXME: LOCKING!
        try
        {
            RSyncBackup backup=new RSyncBackup(args);
            
            if (args.length == 0)
            {
                System.err.println("Missing argument: hostname|ALL [maxParallel]");
                System.exit(1);
            }
            if (args[0].equalsIgnoreCase("ALL"))
            {
                int maxParallel=(args.length==1)?1:Integer.parseInt(args[1]);
                
                BackupScheduler scheduler=new BackupScheduler(maxParallel, backup.conf.getAllHosts(), backup);
                scheduler.executeBackups();
            }
            else
            {
                backup.runBackupForHost(args[0]);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Fatal error",ex);
            System.exit(1);
        }
        
    }
    
    final BackupConf conf;
    final File confDir;
    final File sshPrivateKeyFile;
    
    public RSyncBackup(String[] args) throws Exception
    {
        setThreadHostname(null);
        confDir = new File("conf").getAbsoluteFile();
        
        File loggerConf=new File(confDir,"logback.xml");
        
        if (loggerConf.isFile())
        {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            try
            {
                JoranConfigurator configurator = new JoranConfigurator();
                configurator.setContext(context);
                context.reset(); 
                configurator.doConfigure(loggerConf);
            }
            catch (JoranException je)
            {
              // StatusPrinter will handle this
            }
            StatusPrinter.printInCaseOfErrorsOrWarnings(context);
        }
        
        LOG=LoggerFactory.getLogger(RSyncBackup.class);
        

        LOG.debug("Using config dir: {}",confDir);

        sshPrivateKeyFile = new File(confDir, "backup_ssh_private_key");
        if (!sshPrivateKeyFile.isFile()) throw new RuntimeException("Missing ssh keyfile: " + sshPrivateKeyFile);
        Files.setPosixFilePermissions(sshPrivateKeyFile.toPath(), PosixFilePermissions.fromString("rw-------"));

        LOG.debug("Using ssh private key: {}",sshPrivateKeyFile);
        
        LOG.debug("Reading backup.conf");
        conf = BackupConf.read(new File(confDir, "backup.conf"));
    }
    
    public void runBackupForHost(String hostname) throws Exception
    {
        BackupStatistics statistics=new BackupStatistics();
        statistics.startTime=new LocalDateTime();
        
        ConfHost host = conf.getForHost(hostname);
        setThreadHostname(hostname);
        
        LOG.info("Starting backup");
        
        HostDir hostDir = new HostDir(new File(host.hostStorageDir));

        File currentBackupLink = hostDir.updateCurrentDirLink();
        File syncDir = hostDir.getBackupSyncDir();
        
        if (syncDir.exists())
        {
            LOG.debug("Resuming previous backup to {}",syncDir);
        }
        else
        {
            syncDir.mkdirs();
        }

        Map<String, String> env = new HashMap<>();
        env.put("SSH_AUTH_SOCK", "");

        statistics.backupOk=true;
        
        int exitCode=executeCommand("SSH-TEST", createCmdSsh(host, "NOOP"), env, null);
        if (exitCode!=0)
        {
            statistics.backupErrors.add("Error when running remote NOOP command. Exit code "+exitCode);
            LOG.warn("Error when running remote NOOP command. Exit code "+exitCode);
        }
        
        exitCode=executeCommand("PRE_BACKUP", createCmdSsh(host, "PRE_BACKUP"), env, null);
        if (exitCode!=0)
        {
            statistics.backupOk=false;
            statistics.backupErrors.add("Error when running remote PRE_BACKUP command. Exit code "+exitCode);
            LOG.warn("Error when running remote PRE_BACKUP command. Exit code "+exitCode);
        }
        
        
        List<String> cmdRsyncPreBackup = new ArrayList<>();
        appendCommand(cmdRsyncPreBackup, host.cmdRsync);
        
        for (ConfVolume volume : host.volumes)
        {
            List<String> cmdRsync = new ArrayList<>();
            appendCommand(cmdRsync, host.cmdNice);
            appendCommand(cmdRsync, host.cmdRsync);

            cmdRsync.add("-a"); // Archive
            cmdRsync.add("-v"); // Verbose
            cmdRsync.add("--fake-super"); // Store attributes as xattr (requires
                                          // storage dir mounted with user_xattr
                                          // option!)
            cmdRsync.add("--delete");
            cmdRsync.add("--numeric-ids"); // don't map IDs to backup host's
                                           // users/groups
            cmdRsync.add("--relative");
            cmdRsync.add("--sparse");
            if (currentBackupLink != null)
            {
                cmdRsync.add("--link-dest");
                cmdRsync.add(new File(currentBackupLink, volume.volume).getAbsolutePath());
            }
            cmdRsync.add("--delete-excluded");
            if (volume.exclude != null) for (String exclude : volume.exclude)
            {
                cmdRsync.add("--exclude");
                cmdRsync.add(exclude);
            }

            cmdRsync.add("--rsh");
            cmdRsync.add(dumpCommand(createCmdSsh(host, null), null));

            cmdRsync.add("root@" + host.remoteAddress + ":/" + volume.volume + "/");

            cmdRsync.add(syncDir.getAbsolutePath() + "/"+ volume.volume + "/");

            try
            {
                exitCode=executeCommand("RSYNC",cmdRsync, env, null);
                
                if (exitCode==0)
                {
                    LOG.info("Rsync exited with status 0 - backup succeeded.");
                }
                else if (exitCode==24)
                {
                    LOG.info("Rsync exited with status 24 - backup succeeded but some files vanished during transfer");
                }
                else
                {
                    statistics.backupOk=false;
                    statistics.backupErrors.add("Errors in rsync for "+volume.volume+": exit code "+exitCode);
                    
                    LOG.warn("Rsync exited with status {} - backup failed",exitCode);
                }
            }
            catch (Exception ex)
            {
                statistics.backupOk=false;
                statistics.backupErrors.add("Errors in rsync for "+volume.volume+": "+ex);
                LOG.warn("Error during command execution - backup failed",ex);
            }
        }
        
        statistics.endTime=new LocalDateTime();
        
        if (statistics.backupOk)
        {
            LocalDateTime backup=hostDir.setBackupDone();
            
            deleteOldBackupsForHost(hostname);
            
            updateBackupStatistics(hostDir, backup, statistics);
            
            long size=statistics.changedFileSize;
            int unit=0;
            String[] units={"bytes", "KB","MB","GB","TB"};
            while (size>10240)
            {
                size=size/1024;
                unit++;
            }
            
            String sizeStr=size+" "+units[unit];
            
            Duration duration=new Duration(statistics.startTime.toDateTime(),statistics.endTime.toDateTime());
            
            PeriodFormatter periodFormatter=new PeriodFormatterBuilder()
                .appendDays().appendSuffix("d").appendSeparator(" ")
                .appendHours().appendSuffix("h").appendSeparator(" ")
                .appendMinutes().appendSuffix("m").appendSeparator(" ")
                .appendSeconds().appendSuffix("s")
                .toFormatter();
            
            LOG.info("Statistics: {} files changed, using {} of disk space. Duration: {}",statistics.changedFileCount,sizeStr,periodFormatter.print(duration.toPeriod()));
        }
        
        ZabbixNotifier.notify(host,statistics);
        
        LOG.info("Backup finished.");
    }
    
    protected List<String> createCmdSsh(ConfHost host, String remoteCommand)
    {
        List<String> cmdSsh = new ArrayList<>();
        cmdSsh.add(host.cmdSsh);
        if (host.remoteSshPort != null && host.remoteSshPort > 0)
        {
            cmdSsh.add("-p");
            cmdSsh.add("" + host.remoteSshPort);

        }
        cmdSsh.add("-i");
        cmdSsh.add(sshPrivateKeyFile.toString());

        cmdSsh.add("-o");
        cmdSsh.add("UserKnownHostsFile " + new File(confDir, "backup_ssh_known_hosts"));
        cmdSsh.add("-o");
        cmdSsh.add("HashKnownHosts no");
        
        if ("NOOP".equals(remoteCommand)) // Fake-Command: adds the host's ssh key to the authorized keys if it is not already there
        {
            cmdSsh.add("-o");
            cmdSsh.add("StrictHostKeyChecking no");
        }
        
        if (remoteCommand!=null)
        {
            cmdSsh.add("root@"+host.remoteAddress);
            cmdSsh.add(remoteCommand);
        }
        return cmdSsh;
    }

    protected void updateBackupStatistics(HostDir hostDir, LocalDateTime backup, BackupStatistics statistics) throws Exception
    {
        List<String> cmdFind=new ArrayList<>();
        
        cmdFind.add("/usr/bin/find");
        cmdFind.add(hostDir.getBackupDir(backup).getAbsolutePath());
        cmdFind.add("-type");
        cmdFind.add("f");
        cmdFind.add("-links");
        cmdFind.add("1");
        cmdFind.add("-printf");
        cmdFind.add("%s %p\n");
        
        FindFilesCommandOutputConsumer consumer=new FindFilesCommandOutputConsumer();
        
        executeCommand("FIND", cmdFind, null, consumer);
        
        statistics.changedFileCount=consumer.totalCount;
        statistics.changedFileSize=consumer.totalSize;
    }
    
    protected void deleteOldBackupsForHost(String hostname) throws Exception
    {
        ConfHost host = conf.getForHost(hostname);
        setThreadHostname(hostname);
        
        LOG.info("Deleting old backups");
        
        if (host.backupKeepStrategy==null)
        {
            LOG.warn("No keepStrategy defined. Keeping all backups forever");
            return;
        }
        
        HostDir hostDir = new HostDir(new File(host.hostStorageDir));
        List<LocalDateTime> backups=hostDir.listBackups();
        
        List<LocalDateTime> backupToKeep=host.backupKeepStrategy.getBackupsToKeep(new LocalDateTime(), backups);
        
        if (!backupToKeep.isEmpty())
        {
            for (LocalDateTime backup: backups)
            {
                if (!backupToKeep.contains(backup))
                {
                    File backupDir=hostDir.getBackupDir(backup);
                    
                    if (!backupDir.exists())
                    {
                        LOG.error("BUG: Backup in list not found in filesystem: "+backupDir.getAbsolutePath());
                        continue;
                    }
                    
                    LOG.info("Deleting old backup {}",backup);
                    deleteDirectory(backupDir);
                }
            }
            
        }
    }
    
    protected static void deleteDirectory(File dir) throws Exception
    {
        List<String> cmdDelete=new ArrayList<>();
        cmdDelete.add("/bin/rm");
        cmdDelete.add("-rf");
        cmdDelete.add(dir.getAbsolutePath());
        
        executeCommand("DELETE", cmdDelete, null, null);
    }
    
    
    public static void setThreadHostname(String hostname)
    {
        Thread.currentThread().setName(hostname==null?"global":hostname);
    }

    protected static int executeCommand(String logName, List<String> cmdList, Map<String, String> env, CommandOutputConsumer outputConsumer) throws Exception
    {
        LOG.info("Executing {}: {}",logName, dumpCommand(cmdList, env));
        
        ProcessBuilder bp = new ProcessBuilder(cmdList);
        bp.redirectErrorStream(true);

        if (env != null)
        {
            bp.environment().putAll(env);
        }

        Process proc = bp.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        String line;

        while ((line = in.readLine()) != null)
        {
            if (outputConsumer!=null) outputConsumer.consume(line);
            else LOG.debug("{}: {}",logName,line); 
        }

        for (int i = 0; i < 10; i++)
        {
            try
            {
                return proc.exitValue();
            }
            catch (IllegalThreadStateException ex)
            {
                // ignored
                try
                {
                    Thread.sleep(500);
                }
                catch (InterruptedException iex)
                {
                    break;
                }
            }
        }

        proc.destroy();
        throw new RuntimeException("Process did not terminate and was killed!");
    }

    protected static String dumpCommand(List<String> cmdList, Map<String, String> env)
    {
        StringBuilder sb = new StringBuilder();

        if (env != null)
        {
            for (Entry<String, String> envEntry : env.entrySet())
            {
                if (sb.length() > 0) sb.append(" ");
                sb.append(envEntry.getKey()).append("=\"").append(envEntry.getValue()).append("\"");
            }
        }

        StringBuilder sbCmd = new StringBuilder();
        for (String cmd : cmdList)
        {
            if (cmd == null || cmd.length() == 0) continue;

            boolean quote = false;
            sbCmd.setLength(0);
            for (char c : cmd.toCharArray())
            {
                switch (c)
                {
                    case '\"':
                    case '\\':
                        sbCmd.append('\\');
                    case ' ':
                    case '\t':
                    case '\r':
                    case '\n':
                        quote = true;
                    default:
                        sbCmd.append(c);
                }
            }

            if (sb.length() > 0) sb.append(" ");

            if (quote) sb.append('"');
            sb.append(sbCmd);
            if (quote) sb.append('"');
        }
        return sb.toString();
    }

    protected static void appendCommand(List<String> cmdList, String command)
    {
        if (command == null || command.trim().isEmpty()) return;
        for (String part : command.split(" "))
        {
            part = part.trim();
            if (part.length() != 0) cmdList.add(part);
        }
    }

    protected static interface CommandOutputConsumer
    {
        public void consume(String line);
    }
    
    protected static class FindFilesCommandOutputConsumer implements CommandOutputConsumer
    {
        protected int totalCount=0;
        protected long totalSize=0;
        
        @Override
        public void consume(String line)
        {
            int pos=line.indexOf(' ');
            if (pos<0) return;
            try
            {
                long sizeInBytes=Long.parseLong(line.substring(0,pos));
                totalCount++;
                totalSize+=sizeInBytes;
                
                if (sizeInBytes>1024*1024*100)
                {
                    LOG.info("Changed file with {} bytes: {}",sizeInBytes,line.substring(pos+1));
                }
            }
            catch (NumberFormatException ex)
            {
                // ignored
            }
        }
    }
    
    public static class BackupStatistics
    {
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public int changedFileCount;
        public long changedFileSize;
        public boolean backupOk;
        public List<String> backupErrors=new ArrayList<>();
    }
}
