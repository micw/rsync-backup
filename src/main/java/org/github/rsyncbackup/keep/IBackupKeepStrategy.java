package org.github.rsyncbackup.keep;

import java.util.List;

import org.joda.time.LocalDateTime;

public interface IBackupKeepStrategy
{
    public List<LocalDateTime> getBackupsToKeep(LocalDateTime currentDate, List<LocalDateTime> availableBackups);
}
