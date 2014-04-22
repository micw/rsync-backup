package org.github.rsyncbackup.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.github.rsyncbackup.keep.IBackupKeepStrategy;
import org.github.rsyncbackup.keep.IntervalKeepStrategy;

import com.esotericsoftware.yamlbeans.YamlReader;

public class BackupConf
{
    public static void main(String[] args) throws Exception
    {
        BackupConf.read(new File("backup.conf"));
    }
    
    public static BackupConf read(File source) throws Exception
    {
        YamlReader reader = new YamlReader(new InputStreamReader(new FileInputStream(source),"utf-8"));
        
        try
        {
            BackupConfHolder holder=reader.read(BackupConfHolder.class);
            
            BackupConf conf=new BackupConf();
            conf.hostMap=new LinkedHashMap<>();
            
            ConfHost hostDefaults=holder.defaults;
            if (hostDefaults==null) hostDefaults=createDefaultHostConf();
            else hostDefaults.applyDefaults(createDefaultHostConf());
            
            
            for (ConfHost hostConf: holder.hosts)
            {
                hostConf.applyDefaults(hostDefaults);
                resolvePlaceholders(hostConf);
                hostConf.initialize();
                conf.hostMap.put(hostConf.host,hostConf);
            }
            
            return conf;
        }
        finally
        {
            reader.close();
        }
    }
    
    protected static ConfHost createDefaultHostConf()
    {
        ConfHost conf=new ConfHost();
        conf.storageDir="hosts";
        conf.hostStorageDir="${storageDir}/${host}";
        conf.cmdNice="/usr/bin/nice -n 19 /usr/bin/ionice -c3";
        conf.cmdRsync="/usr/bin/rsync";
        conf.cmdSsh="/usr/bin/ssh";
        conf.remoteAddress="${host}";
        conf.scheduleGroup="${host}";
        conf.scheduleEnabled=Boolean.TRUE;
        conf.volumes=new ConfVolume[] {new ConfVolume("ROOT","tmp")};
        conf.notifyZabbixServer=null;
        conf.notifyZabbixHost="${host}";
        return conf;
    }
    
    protected Map<String,ConfHost> hostMap;
    
    public List<ConfHost> getAllHosts()
    {
        return new ArrayList<>(hostMap.values());
    }
    
    public ConfHost getForHost(String hostname)
    {
        ConfHost host=hostMap.get(hostname);
        if (host == null) throw new RuntimeException("No configuration for host " + hostname);
        return host;
    }
    
    
    protected static void resolvePlaceholders(Object o) throws Exception
    {
        for (Field field: o.getClass().getFields())
        {
            if (field.getType()!=String.class) continue;
            
            String value=(String) field.get(o);
            if (value==null) continue;
            String newValue=resolvePlaceholders(value, o);
            
            if (!value.equals(newValue)) field.set(o,resolvePlaceholders(value, o));
        }
    }
    
    protected static String getPropertyAsString(Object o, String name)
    {
        try
        {
            Object result=o.getClass().getField(name).get(o);
            if (result==null) return null;
            return result.toString();
        }
        catch (Exception ex)
        {
            return null;
        }
    }
    
    protected static String resolvePlaceholders(String value, Object o) throws Exception
    {
        if (value==null) return null;
        
        StringBuilder newValue=new StringBuilder();
        
        
        int endPos=0;
        int startPos=0;
        for (;;)
        {
            startPos=value.indexOf("${", startPos);
            if (startPos<0) break;
            
            newValue.append(value.substring(endPos, startPos));
            
            endPos=value.indexOf("}", startPos)+1;
            if (endPos<startPos) throw new RuntimeException("Invalid placeholder in "+value);
            
            String placeholder=value.substring(startPos+2,endPos-1);
            
            String placeholderValue=getPropertyAsString(o, placeholder);
            
            if (placeholderValue==null) throw new RuntimeException("Unresolved placeholder '"+placeholder+"' in "+value);

            newValue.append(placeholderValue);
            
            startPos=endPos;
        }
        newValue.append(value.substring(endPos));
        
        return newValue.toString();
    }
    
    public static class ConfHost
    {
        public String host;
        public String remoteAddress;
        public String storageDir;
        public String hostStorageDir;
        public String cmdNice;
        public String cmdRsync;
        public String cmdSsh;
        public String keepStrategy;
        public String scheduleGroup;
        public Boolean scheduleEnabled;
        public Integer remoteSshPort;
        public ConfVolume[] volumes;
        public IBackupKeepStrategy backupKeepStrategy;
        
        public String notifyZabbixServer;
        public String notifyZabbixHost;
        
        protected void applyDefaults(ConfHost defaults)
        {
            if (this.storageDir==null) this.storageDir=defaults.storageDir;
            if (this.remoteAddress==null) this.remoteAddress=defaults.remoteAddress;
            if (this.hostStorageDir==null) this.hostStorageDir=defaults.hostStorageDir;
            if (this.cmdNice==null) this.cmdNice=defaults.cmdNice;
            if (this.cmdRsync==null) this.cmdRsync=defaults.cmdRsync;
            if (this.cmdSsh==null) this.cmdSsh=defaults.cmdSsh;
            if (this.remoteSshPort==null) this.remoteSshPort=defaults.remoteSshPort;
            if (this.volumes==null) this.volumes=defaults.volumes;
            if (this.keepStrategy==null) this.keepStrategy=defaults.keepStrategy;
            if (this.notifyZabbixServer==null) this.notifyZabbixServer=defaults.notifyZabbixServer;
            if (this.notifyZabbixHost==null) this.notifyZabbixHost=defaults.notifyZabbixHost;
            if (this.scheduleGroup==null) this.scheduleGroup=defaults.scheduleGroup;
            if (this.scheduleEnabled==null) this.scheduleEnabled=defaults.scheduleEnabled;
        }
        
        protected void initialize()
        {
            if (keepStrategy!=null)
            {
                String nameArgs[]=keepStrategy.split("\\|",2);
                String name=nameArgs[0].trim();
                
                if (name.equalsIgnoreCase("interval"))
                {
                    backupKeepStrategy=new IntervalKeepStrategy(nameArgs[1]);
                }
                else
                {
                    throw new IllegalArgumentException("Inavlid keepStrategy: "+name);
                }
            }
        }
    }
    
    public static class ConfVolume
    {
        public ConfVolume()
        {
        }
        public ConfVolume(String volume, String... exclude)
        {
            this.volume=volume;
            this.exclude=exclude;
        }
        
        public String volume;
        public String[] exclude;
    }

    public static class BackupConfHolder
    {
        public ConfHost defaults;
        public ConfHost[] hosts;
    }
    
}
