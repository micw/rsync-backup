package org.github.rsyncbackup.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostDir
{
    protected Logger LOG=LoggerFactory.getLogger(getClass());
    
    final File hostDir;
    public HostDir(File hostDir)
    {
        if (!hostDir.isDirectory()) throw new RuntimeException("No such directory: "+hostDir);
        this.hostDir=hostDir.getAbsoluteFile();
    }
    
    public File updateCurrentDirLink() throws IOException
    {
        LocalDateTime latestBackup=getLatestBackup();
        
        File currentDirLink=new File(hostDir,"current");
        
        if (latestBackup==null)
        {
            if (currentDirLink.exists())
            {
                LOG.info("Removing old link: "+currentDirLink);
                currentDirLink.delete();
            }
            return null;
        }
        
        Path latestBackupDir=new File(getDirnameFromBackup(latestBackup)).toPath();
        
        if (Files.isSymbolicLink(currentDirLink.toPath()))
        {
            Path oldLink=Files.readSymbolicLink(currentDirLink.toPath());
            if (oldLink.equals(latestBackupDir)) return currentDirLink; // already linked
            
            currentDirLink.delete();
        }
        
        LOG.info("Linking {} -> {}",currentDirLink,latestBackupDir);
        Files.createSymbolicLink(currentDirLink.toPath(),latestBackupDir);
        
        return currentDirLink;
    }
    public File getBackupSyncDir()
    {
        return new File(hostDir,".sync");
    }
    public File getBackupDir(LocalDateTime backup)
    {
        return new File(hostDir,getDirnameFromBackup(backup));
    }
    protected static DateTimeFormatter backupDirFormat=DateTimeFormat.forPattern("'backup-'yyyy-MM-dd-HH:mm:ss");

    public LocalDateTime getLatestBackup()
    {
        List<LocalDateTime> backups=listBackups();
        if (backups.isEmpty()) return null;
        return Collections.max(backups);
    }
    
    public List<LocalDateTime> listBackups()
    {
        return listBackups(hostDir);
    }
    
    public LocalDateTime setBackupDone() throws IOException
    {
        LocalDateTime backup=new LocalDateTime();
        
        File syncDir=getBackupSyncDir();
        if (!syncDir.isDirectory()) throw new IOException("Unable to move .sync to new location. No such directory: "+syncDir);
        
        File newBackupDir=new File(hostDir,getDirnameFromBackup(backup));
        
        if (newBackupDir.exists()) throw new IOException("Unable to move .sync to new location. Directory already exists: "+syncDir);
        
        if (!syncDir.renameTo(newBackupDir)) throw new IOException("Unable to move .sync to new location. Rename failed.");
        
        updateCurrentDirLink();
        
        return backup;
    }
    
    public static List<LocalDateTime> listBackups(File hostDir)
    {
        List<LocalDateTime> backups=new ArrayList<>();
        
        File[] files=hostDir.listFiles();
        if (files!=null) for (File dir: files)
        {
            LocalDateTime backup=getBackupFromDir(dir);
            if (backup!=null) backups.add(backup);
        }
        
        Collections.sort(backups);
        
        return backups;
    }
    
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

}
