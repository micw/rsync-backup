package org.github.rsyncbackup.impl;

public interface IBackupExecutor
{
    public void runBackupForHost(String hostname) throws Exception;
}
