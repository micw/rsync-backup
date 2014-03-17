package org.github.rsyncbackup.keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.LocalDateTime;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy from http://storebackup.org/ (lib/storeBackupLib.pl, "sub checkBackups")
 * 
 * Documentation:
 * http://www.nongnu.org/storebackup/en/node48.html
 * http://www.nongnu.org/storebackup/de/node48.html
 * 
 * @author mwyraz
 */
public class IntervalKeepStrategy implements IBackupKeepStrategy
{
    protected Logger LOG=LoggerFactory.getLogger(getClass());
    
    protected final List<Period> keepDurations;
    
    protected final PeriodFormatter periodFormatter;
    
    public IntervalKeepStrategy(String intervals)
    {
        periodFormatter=new PeriodFormatterBuilder()
            .appendMonths().appendSuffix("m")
            .appendWeeks().appendSuffix("w")
            .appendDays().appendSuffix("d")
            .appendHours().appendSuffix("h")
            .toFormatter();
        
        keepDurations=new ArrayList<>();
        Duration lastDuration=new Duration(0);
        for (String iv: intervals.split("\\s+"))
        {
            Period period=periodFormatter.parsePeriod(iv);
            if (period.toStandardDuration().isShorterThan(lastDuration)) throw new IllegalArgumentException("intervals must be in order: "+iv+" ("+intervals+")");
            keepDurations.add(period);
        }
    }
    
    @Override
    public List<LocalDateTime> getBackupsToKeep(LocalDateTime currentDate, List<LocalDateTime> availableBackups)
    {
        if (availableBackups.size()==0)
        {
            LOG.debug("No backups available");
            return availableBackups;
        }
        if (availableBackups.size()==1)
        {
            LOG.debug("Keeping the only available backup");
            return availableBackups;
        }
        
        availableBackups=new ArrayList<LocalDateTime>(availableBackups);
        Collections.sort(availableBackups);
        Collections.reverse(availableBackups);
        
        Map<LocalDateTime,String> backupsToKeep=new TreeMap<>();
        
        /*
         * Always keep most recent backup (we don't know when the next backup will be
         * made, so we cannot judge if we may need it or not)
         * 
         */

        LOG.debug("Always keep the first backup {}",availableBackups.get(0));
        addBackup(backupsToKeep,availableBackups.get(0),"most recent");
        
        Duration offset=new Duration(0);
        
        period: for (int dur=0;dur<keepDurations.size()-1;dur++)
        {
            Period perFrom=keepDurations.get(dur);
            Period perTo=keepDurations.get(dur+1);
            Duration durFrom=perFrom.toStandardDuration();
            Duration durTo=perTo.toStandardDuration();
            
            String periodStr=periodFormatter.print(perFrom)+"-"+periodFormatter.print(perTo)+"/"+
                    durationToString(durFrom)+"-"+durationToString(durTo);
            
            LOG.debug("Examining period {}",periodStr);

            LocalDateTime lastBackup=availableBackups.get(availableBackups.size()-1);
            
            int backupNum=0;
            LocalDateTime backup=null;
            
            backup: for (backupNum=0;backupNum<availableBackups.size();backupNum++)
            {
                backup=availableBackups.get(backupNum);

                Duration backupAge=getBackupAge(currentDate, backup);
                String backupAgeStr=durationToString(backupAge);
                
                // Keep first backup that is older than the beginning of the current period
                if (!backupAge.isShorterThan(durFrom.plus(offset)))
                {
                    // If the backup is actually too old for this period, make sure that the
                    // following intervals are shifted by the same amount
                    if (!backupAge.isShorterThan(durTo.plus(offset)))
                    {
                        offset=backupAge.minus(durTo);
                        LOG.info("  no backup for period {}, choosing next older backup {} with age {} instead",periodStr,backup,backupAgeStr);
                        LOG.info("    using an offset of {} for all older backups",durationToString(offset));
                        addBackup(backupsToKeep,backup,periodStr+" (nearest older)");
                        
                    }
                    else
                    {
                        LOG.debug("  backup for period {} found: {}",periodStr,backup);
                        addBackup(backupsToKeep,backup,periodStr+" (exact match)");
                    }
                    break backup;
                }
                else if (backup==lastBackup)
                {
                    // If we didn't find any backup old enough, we take the oldest one instead
                    addBackup(backupsToKeep,backup,periodStr+" (oldest possible)");
                    LOG.info("  no backup for period {}, choosing oldest backup {} with age {} instead",periodStr,backup,backupAgeStr);
                }
            }
            
            LOG.debug("  period {} is satisfied by backup {}",periodStr,backup);
            
            
            /*
             * The following loop goes forward in time, starting from the backup
             * that at the time of this run satisfies the current period to the
             * most recent backup.
             * 
             * 
             * For each backup $backup, it is checked if the backup will at some
             * point in the future be needed to satisfy the period. If so, it is
             * marked as 'candidate' for keeping.
             * 
             * A backup $prevBackup is required for a period, if the backup that
             * satisfied the period in the last iteration ($keptBackup) is going
             * to run out of the period before the next backup ($backup) is
             * entering the period.
             */
            
            int i=backupNum;
            LocalDateTime keptBackup=backup;
            LocalDateTime prevBackup;
            
            Duration expires=durTo.minus(getBackupAge(currentDate, keptBackup));
            
            LOG.debug("  backup {} will leave period in {}.",backup,durationToString(expires));
            
            while (i>0)
            {
                prevBackup=backup;
                backup=availableBackups.get(--i);
                
                // Determine number of seconds until the next more recent backup will be old enough for the period
                Duration remaining=durFrom.minus(getBackupAge(currentDate, backup));
                
                if (expires.getMillis()<0)
                {
                    // If the backup has already expired, then we obviously need the next one
                    addBackup(backupsToKeep,backup,periodStr+" (candidate)");
                    keptBackup=backup;
                    expires=durTo.minus(getBackupAge(currentDate, keptBackup));
                    Duration keptBackupAge=new Interval(keptBackup.toDateTime(),currentDate.toDateTime()).toDuration();
                    LOG.info("  Has already left period. Keeping {}. Will leave period in {}",backup,durationToString(keptBackupAge));
                }
                else if (!expires.isLongerThan(remaining))
                {
                    // If the backup last marked to keep for this period will be too old before the current
                    // backup is old enough, also mark the previous backup for keeping.

                    LOG.info("  backup {} will enter period in {} - this is too late, trying to keep intermediate backup.",
                            backup,durationToString(remaining));
                    
                    if (keptBackup==prevBackup)
                    {
                        LOG.warn("  There will be no backup for period{} in {} days. This is usually caused by backups not being done regularly enough.",
                                periodStr, expires.toPeriod().getDays());
                        
                        // At least we try to minimize the gap
                        addBackup(backupsToKeep,backup,periodStr+" (candidate)");
                        
                        keptBackup=backup;
                        expires=durTo.minus(getBackupAge(currentDate, keptBackup));
                        LOG.debug("  Marking {} to minimze gap. Will leave period in {}.",backup,durationToString(expires));
                    }
                    else
                    {
                        addBackup(backupsToKeep,prevBackup,periodStr+" (candidate)");
                        keptBackup=prevBackup;
                        expires=durTo.minus(getBackupAge(currentDate, keptBackup));
                        LOG.debug("  Marking {}. Will leave period in {}.",backup,durationToString(expires));
                    }
                    
                }
                else
                {
                    LOG.debug("  backup {} will enter period in {} - no need to keep intermediate backup.",backup,durationToString(remaining));
                }
            }
        }
        
        if (LOG.isDebugEnabled())
        {
            for (LocalDateTime backup: availableBackups)
            {
                if (backupsToKeep.containsKey(backup))
                {
                    LOG.debug("backup {}: [age {}] {}",backup,durationToString(getBackupAge(currentDate, backup)),backupsToKeep.get(backup));
                }
                else
                {
                    LOG.debug("backup {}: DELETE",backup);
                }
            }
        }
        
        return new ArrayList<>(backupsToKeep.keySet());
    }
    
    protected void addBackup(Map<LocalDateTime,String> backupsToKeep, LocalDateTime backup, String message)
    {
        String oldMessage=backupsToKeep.get(backup);
        if (oldMessage!=null) message=oldMessage+" | "+message;
        backupsToKeep.put(backup, message);
    }
    
    protected String durationToString(Duration duration)
    {
        return periodFormatter.print(duration.toPeriod().normalizedStandard());
    }
    
    protected Duration getBackupAge(LocalDateTime currentDate, LocalDateTime backup)
    {
        return new Duration(currentDate.toDateTime().getMillis()-backup.toDateTime().getMillis());
    }
}
