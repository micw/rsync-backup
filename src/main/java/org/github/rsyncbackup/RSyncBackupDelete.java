package org.github.rsyncbackup;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.github.rsyncbackup.keep.IBackupKeepStrategy;
import org.github.rsyncbackup.keep.IntervalKeepStrategy;
import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;


public class RSyncBackupDelete
{
    public static void main(String[] args) throws Exception
    {
        if (args.length==0 && new File(".project").exists() && new File(".classpath").exists()) args=new String[] {"ALL"};
        
        if (args.length==0) throw new RuntimeException("Missing argument: hostname|ALL");

        File allHostDir=new File("hosts");
        if (!allHostDir.isDirectory()) throw new IOException("No such directory: "+allHostDir.getAbsolutePath());
        
        String keepRelative="1h 2h 3h 4h 5h 6h 12h 1d 2d 3d 4d 5d 6d 7d 8d 9d 10d 11d 12d 13d 14d 21d 28d 35d 42d 49d 56d 84d 112d 140d 210d 350d 490d";
        IBackupKeepStrategy keepStrategy=new IntervalKeepStrategy(keepRelative);
        
        
        if ("ALL".equals(args[0]))
        {
            for (File host: allHostDir.listFiles())
            {
                if (!host.isDirectory()) continue;
                deleteBackupsIn(host,keepStrategy);
            }
        }
        else
        {
            deleteBackupsIn(new File(allHostDir,args[0]),keepStrategy);
        }
    }
    
    protected static DateTimeFormatter backupDirFormat=DateTimeFormat.forPattern("'backup-'yyyy-MM-dd-HH:mm:ss");
    
    protected static LocalDateTime getBackupFromDir(File dir)
    {
        if (!dir.isDirectory()) return null;
        try
        {
            return LocalDateTime.parse(dir.getName(),backupDirFormat);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }
    
    protected static String getDirnameFromBackup(LocalDateTime backup)
    {
        return (backupDirFormat.print(backup));
    }
    
    protected static void deleteDirectory(File dir) throws Exception
    {
        Process proc=Runtime.getRuntime().exec(new String[] {"/bin/rm","-rf",dir.getAbsolutePath()});
        proc.waitFor();
        
//        FileUtils.deleteQuietly(dir);
    }
    
    protected static void deleteBackupsIn(File hostDir, IBackupKeepStrategy keepStrategy) throws Exception
    {
        if (!hostDir.isDirectory()) throw new IOException("No such directory: "+hostDir.getAbsolutePath());
        System.err.println("Deleting old backups from "+hostDir.getName());
        
        List<LocalDateTime> backups=new ArrayList<>();
        
        File[] files=hostDir.listFiles();
        if (files!=null) for (File dir: files)
        {
            LocalDateTime backup=getBackupFromDir(dir);
            if (backup!=null) backups.add(backup);
        }
        
        List<LocalDateTime> backupToKeep=keepStrategy.getBackupsToKeep(new LocalDateTime(), backups);
        
        if (!backupToKeep.isEmpty())
        {
            for (LocalDateTime backup: backups)
            {
                if (!backupToKeep.contains(backup))
                {
                    String dirname=getDirnameFromBackup(backup);
                    
                    File backupDir=new File(hostDir,dirname);
                    if (!backupDir.exists())
                    {
                        System.err.println("BUG: Backup in list not found in filesystem: "+backupDir.getAbsolutePath());
                        continue;
                    }
                    
                    System.err.println("  deleting "+dirname);
                    deleteDirectory(backupDir);
                }
            }
            
        }
        System.err.println("done.");
        
    }
}
