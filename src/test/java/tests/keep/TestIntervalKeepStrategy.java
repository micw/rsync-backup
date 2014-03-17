package tests.keep;

import java.util.ArrayList;
import java.util.List;

import org.github.rsyncbackup.keep.IBackupKeepStrategy;
import org.github.rsyncbackup.keep.IntervalKeepStrategy;
import org.joda.time.LocalDateTime;
import org.junit.Test;

public class TestIntervalKeepStrategy
{
    @Test
    public void testBasic()
    {
        IBackupKeepStrategy strategy=new IntervalKeepStrategy("1h 2h 12h 1d 2d 5d 10d 20d 50d");
        
        LocalDateTime lastBackup=new LocalDateTime(2014,3,1,1,0,0);
        
        List<LocalDateTime> backups=new ArrayList<>();
        for (int i=0;i<25;i++)
        {
            backups.add(lastBackup.minusDays(i));
        }
        
        for (int i=0;i<1;i++)
        {
            System.err.println("----");
            backups=strategy.getBackupsToKeep(new LocalDateTime(2014,3,i+1,3,0,0), backups);
        }
        
        
    }
}
