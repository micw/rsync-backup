rsync-backup
============

RSync based high-efficient pull backup for large servers.


Why?
====

I have tried a lot backup solutions, all have their strengths and weaknesses:

Most solutions implement a classical full/incremental backup scheme. That means that you have to do a
full backup from time to time. When dealing with many servers and big volumes you have to deal with large
backup sets and incredible long backup times.

There are some solutions that create one full backup and produce back-in-time-deltas for each backup. A
well-known candidate of this kind is rdiff-backup. It is a really good solution which I have used for a long time.
Rdiff-backup creates a new backup from a previous one plus increments, calculates a "reverse patch" from this new backup
back to the previous backup and stores this reverse patches. This way it's savin much disk space and you can always
access a directory that contains the latest backup. But with growing data size performance became worse. Especially if
a backup failed and rdiff-backup had fix things in the broken backup.

Another good solution I used for about one year, backing up 10 servers with some terabyte of data, was storebackup.
It creates a kind of "incremental change set", uploads it to the server and applies to the previous backup, creating
a new one. To save space, compression and hard links are used. Each individual backup is directly accessible as a directory
in the file system. Unfortunately the process of applying the change set on our server took to long with increasing backup
sizes.

So I decided to create my own backup script, implementing all the good things I have seen in all these solutions.


Features (in progress)
======================

* creates snapshot-style backups using rsync
* simple configuration 
* simple client-deployment: requires only ssh+rsync + a script on the client. 5 minutes to setup a new client.
* save space using hard links
* have one directory per backup to allow super-easy restores
* automatic resume of unfinished backups
* automatic deletion of old backups, using a user-selectable strategy
  (implements the excelent "keepRelative" strategy from storebackup, see http://www.nongnu.org/storebackup/en/node48.html)
* secure pull-backups: backup server has read-only root access to clients volumes
* easy restore of single files, using "cp"
* easy bare-metal-restore of complete systems, using "rsync"
* statistics & diagnostics
** by default monitors count and size of changed files. Warns about big files that are changed from backup to backup
** analyze which files/directories makes a backups large (where space cannot be saved using hard links because of changed files)  
* monitoring (e.g. to zabbix or via email)
* scheduling: run N backups in parallel. Avoid to run many backups in parallel that share the same physical host,
  the same internet connection or other resources.
* secure ssh tunneling: restricted access to configured clients by using one client as ssh proxy 

On the client
=============

* a special entry in /root/.ssh/authorized keys allows the backup server to access the client
* a script "backup_shell.sh" restricts this access to certain actions (e.g. read-only rsync access)
* the script is placed in /backup/backup_shell.sh
* all directories that can be backed up are bind-mounted to /backup/volumes/NAME
* optionally the script "pre_backup.sh" does some stuff like dumping databases. It is triggered from the backup server before each backup 

On the server
=============

* TDB

Progress
========

* configuration: done
* client scripts: done
* backup: done
** save space using hard links: done
** automatic resume: done
* restore: documented, tested
* delete old backups: done
** strategy "interval", ported from storebackup: http://www.nongnu.org/storebackup/en/node48.html
* statistics & disgnostics: in progress
* scheduling: open (simply run RSyncBackup for each client via cron)
* monitoring: open
* secure ssh tunneling: open


Restoring files
===============

* to restore a single file, simply navigate to the backup directory and copy the file back to it's place.
* to restore one or more files including their attributes, run the following command on the client:

rsync -e 'ssh -p22' -avr --delete-during  --rsync-path='rsync --fake-super' 
  BACKUP-SERVER:/path/to/backups/backup-2014-03-03-22\:55\:59/ROOT/ /mnt/
